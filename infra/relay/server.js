const fs = require('fs')
const https = require('https')
const WebSocket = require('ws')
const Database = require('better-sqlite3')

const DOMAIN = 'cryptsafe-relay.duckdns.org'
const TTL_MS = 7 * 24 * 3600 * 1000   // 7 дней

// === К6: офлайн-очередь (SQLite) ===
const db = new Database('/root/queue.db')
db.pragma('journal_mode = WAL')
db.exec(`CREATE TABLE IF NOT EXISTS queue (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  recipient TEXT NOT NULL,
  sender TEXT NOT NULL,
  payload TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  ttl_expiry INTEGER NOT NULL
)`)
const qInsert = db.prepare('INSERT INTO queue (recipient,sender,payload,created_at,ttl_expiry) VALUES (?,?,?,?,?)')
const qSelect = db.prepare('SELECT id,sender,payload FROM queue WHERE recipient=? ORDER BY created_at ASC')
const qDelete = db.prepare('DELETE FROM queue WHERE id=?')
const qCleanup = db.prepare('DELETE FROM queue WHERE ttl_expiry < ?')

// === P4: prekeys (X3DH) — в той же queue.db ===
db.exec(`CREATE TABLE IF NOT EXISTS prekeys (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  recipient TEXT NOT NULL,
  key_type TEXT NOT NULL,
  key_id INTEGER NOT NULL,
  key_value TEXT NOT NULL,
  signature TEXT,
  created_at INTEGER NOT NULL
)`)
db.exec('CREATE INDEX IF NOT EXISTS idx_prekeys_recipient ON prekeys(recipient)')

// upload: relay = глупая почта, подписи НЕ проверяет
const pkInsert = db.prepare(
  'INSERT INTO prekeys (recipient,key_type,key_id,key_value,signature,created_at) VALUES (?,?,?,?,?,?)')
// при повторной публикации SPK/IK — заменяем старые (personality стабильна)
const pkDeleteType = db.prepare('DELETE FROM prekeys WHERE recipient=? AND key_type=?')
// request: IK и SPK не удаляются
const pkGetIK  = db.prepare("SELECT key_value FROM prekeys WHERE recipient=? AND key_type='IK' LIMIT 1")
const pkGetSPK = db.prepare("SELECT key_value,signature,key_id FROM prekeys WHERE recipient=? AND key_type='SPK' LIMIT 1")
// OPK: атомарный выбор+удаление (one-time, без окна гонки)
const pkTakeOPK = db.prepare(
  "DELETE FROM prekeys WHERE id=(SELECT id FROM prekeys WHERE recipient=? AND key_type='OPK' LIMIT 1) RETURNING key_id,key_value")
const pkCountOPK = db.prepare("SELECT COUNT(*) AS n FROM prekeys WHERE recipient=? AND key_type='OPK'")

// TTL-чистка при старте + раз в час
function cleanupTTL() {
    const removed = qCleanup.run(Date.now()).changes
    if (removed > 0) console.log(`[TTL] удалено просроченных: ${removed}`)
}
cleanupTTL()
setInterval(cleanupTTL, 3600 * 1000)

// выдать накопленное для recipient, удалить выданное
function flushQueue(socket) {
    const rows = qSelect.all(socket.senderId)
    for (const row of rows) {
        if (socket.readyState === WebSocket.OPEN) {
            socket.send(JSON.stringify({
                type: 'msg',
                from: row.sender,
                to: socket.senderId,
                payload: row.payload
            }))
            qDelete.run(row.id)
        }
    }
    if (rows.length > 0) console.log(`[QUEUE] выдано ${socket.senderId}: ${rows.length}`)
}

const server = https.createServer({
    cert: fs.readFileSync(`/etc/letsencrypt/live/${DOMAIN}/fullchain.pem`),
    key: fs.readFileSync(`/etc/letsencrypt/live/${DOMAIN}/privkey.pem`)
})

const wss = new WebSocket.Server({ server })
const clients = new Set()

wss.on('connection', (socket, req) => {
    clients.add(socket)
    const ip = req.socket.remoteAddress
    console.log(`[+] Connected: ${ip} | Total: ${clients.size}`)
    clients.forEach(other => {
        if (other !== socket && other.readyState === WebSocket.OPEN && other.pubKey) {
            socket.send(JSON.stringify({ type: 'pubkey', key: other.pubKey, senderId: other.senderId }))
        }
    })
    socket.on('message', (data) => {
        try {
            const msg = JSON.parse(data.toString())
            if (msg.type === 'pubkey') {
                socket.pubKey = msg.key
                socket.senderId = msg.senderId
                console.log(`[KEY] Received pubkey: ${msg.key.slice(0,16)}...`)
                clients.forEach(client => {
                    if (client !== socket && client.readyState === WebSocket.OPEN) {
                        client.send(JSON.stringify({ type: 'pubkey', key: msg.key, senderId: msg.senderId }))
                    }
                })
                // К6: клиент представился -> выдать накопленную очередь
                if (socket.senderId) flushQueue(socket)
                return
            }
            if (msg.type === 'prekeys_upload') {
                // публичная связка Боба: {senderId, keys:[{type,id,value,sig?}]}
                const owner = msg.senderId
                if (!owner || !Array.isArray(msg.keys)) return
                const now = Date.now()
                // IK/SPK заменяем (стабильны), OPK добавляем в пул
                const insertAll = db.transaction(() => {
                    for (const k of msg.keys) {
                        if (k.type === 'IK' || k.type === 'SPK') pkDeleteType.run(owner, k.type)
                        pkInsert.run(owner, k.type, k.id|0, k.value, k.sig || null, now)
                    }
                })
                insertAll()
                // no-log на диск (митигация слежки): только счётчик в памяти
                console.log(`[PREKEY] upload ${owner}: ${msg.keys.length} ключей`)
                return
            }

            if (msg.type === 'prekeys_request') {
                const target = msg.targetId
                if (!target) return
                const ik  = pkGetIK.get(target)
                const spk = pkGetSPK.get(target)
                const opk = pkTakeOPK.get(target)   // атомарно берёт+удаляет, или undefined
                socket.send(JSON.stringify({
                    type: 'prekeys_response',
                    targetId: target,
                    ik:  ik  ? ik.key_value : null,
                    spk: spk ? { value: spk.key_value, sig: spk.signature, keyId: spk.key_id } : null,
                    opk: opk ? { id: opk.key_id, value: opk.key_value } : null
                }))
                console.log(`[PREKEY] request ${target}: opk=${opk ? 'выдан' : 'НЕТ'}`)
                return
            }

            if (msg.type === 'msg') {
                const target = msg.to
                if (!target) { console.log('[!] msg без to — дроп'); return }
                let delivered = false
                clients.forEach(client => {
                    if (client.senderId === target && client.readyState === WebSocket.OPEN) {
                        client.send(JSON.stringify({
                            type: 'msg',
                            from: socket.senderId,
                            to: target,
                            payload: msg.payload
                        }))
                        delivered = true
                    }
                })
                if (delivered) {
                    console.log(`[>] msg ${socket.senderId}->${target}`)
                } else {
                    // К6: получатель офлайн -> в очередь
                    const now = Date.now()
                    qInsert.run(target, socket.senderId, msg.payload, now, now + TTL_MS)
                    console.log(`[QUEUE] ${socket.senderId}->${target} в очередь (офлайн)`)
                }
                return
            }
        } catch (e) {}
        console.log(`[?] неопознанный пакет ${data.length}b — игнор`)
    })
    socket.on('close', () => {
        clients.delete(socket)
        console.log(`[-] Disconnected | Total: ${clients.size}`)
    })
    socket.on('error', (err) => console.log(`[!] Error: ${err.message}`))
})

const PORT = 8080
server.listen(PORT, '0.0.0.0', () => {
    console.log(`[SERVER] Secure WSS relay + offline queue running on wss://${DOMAIN}:${PORT}`)
})
