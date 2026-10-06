const fs = require('fs')
const https = require('https')
const WebSocket = require('ws')
const Database = require('better-sqlite3')
const FACADE_HTML = "<!DOCTYPE html><html><head><title>Welcome</title></head><body><h1>It works!</h1><p>This is the default web page for this server.</p><p>The web server software is running but no content has been added, yet.</p></body></html>"
const SECRET_PATH = process.env.LCS_WS_PATH || "/api/v1/sync"

const DOMAIN = process.env.LCS_DOMAIN || 'cryptsafe-relay.duckdns.org'
const TTL_MS = (Number(process.env.LCS_TTL_DAYS) || 7) * 24 * 3600 * 1000   // 7 дней

// === К6: офлайн-очередь (SQLite) ===
const db = new Database(process.env.LCS_DB_PATH || '/opt/libcryptsafe/queue.db')
db.pragma('journal_mode = WAL')
db.exec(`CREATE TABLE IF NOT EXISTS queue (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  recipient TEXT NOT NULL,
  payload TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  ttl_expiry INTEGER NOT NULL
)`)
// Blind Delivery: колонка sender УБРАНА. Relay не хранит граф кто->кому.
// Отправитель зашит в зашифрованный payload (ik_sign_a), виден только получателю.
const qInsert = db.prepare('INSERT INTO queue (recipient,payload,created_at,ttl_expiry) VALUES (?,?,?,?)')
const qSelect = db.prepare('SELECT id,payload FROM queue WHERE recipient=? ORDER BY created_at ASC')
const qDelete = db.prepare('DELETE FROM queue WHERE id=?')
const qDeleteFor = db.prepare('DELETE FROM queue WHERE id=? AND recipient=?')
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
const pkGetIkSign = db.prepare("SELECT key_value FROM prekeys WHERE recipient=? AND key_type='IK_SIGN' LIMIT 1")
const pkGetIkDh   = db.prepare("SELECT key_value FROM prekeys WHERE recipient=? AND key_type='IK_DH' LIMIT 1")
const pkGetSPK = db.prepare("SELECT key_value,signature,key_id FROM prekeys WHERE recipient=? AND key_type='SPK' LIMIT 1")
// OPK: атомарный выбор+удаление (one-time, без окна гонки)
const pkTakeOPK = db.prepare(
  "DELETE FROM prekeys WHERE id=(SELECT id FROM prekeys WHERE recipient=? AND key_type='OPK' LIMIT 1) RETURNING key_id,key_value")
const pkCountOPK = db.prepare("SELECT COUNT(*) AS n FROM prekeys WHERE recipient=? AND key_type='OPK'")

// === CHANNELS: blind cold-storage of signed posts (relay knows nothing) ===
db.exec(`CREATE TABLE IF NOT EXISTS channel_posts (
    channel_id TEXT NOT NULL,
    seq INTEGER NOT NULL,
    payload TEXT NOT NULL,
    created_at INTEGER NOT NULL,
    PRIMARY KEY(channel_id, seq)
)`)
db.exec('CREATE INDEX IF NOT EXISTS idx_channel_posts ON channel_posts(channel_id)')
const cpInsert = db.prepare('INSERT OR IGNORE INTO channel_posts (channel_id,seq,payload,created_at) VALUES (?,?,?,?)')
const cpSelect = db.prepare('SELECT seq,payload FROM channel_posts WHERE channel_id=? AND seq>? ORDER BY seq ASC LIMIT 100')

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
                to: socket.senderId,
                payload: row.payload,
                qid: row.id
            }))
            // RELAY-ACK: не удаляем здесь — удалим по relay_ack получателя
        }
    }
    if (rows.length > 0) console.log(`[QUEUE] выдано сообщений: ${rows.length}`)
}

const server = https.createServer({
    cert: fs.readFileSync((process.env.LCS_CERT_DIR || '/opt/libcryptsafe/certs') + '/fullchain.pem'),
    key: fs.readFileSync((process.env.LCS_CERT_DIR || '/opt/libcryptsafe/certs') + '/privkey.pem')
}, function (req, res) {
    res.writeHead(200, { "Content-Type": "text/html; charset=utf-8", "Connection": "close" })
    res.end(FACADE_HTML)
})

const wss = new WebSocket.Server({ noServer: true })
server.on("upgrade", (req, socket, head) => {
    const upath = (req.url || "").split("?")[0]
    if (upath !== SECRET_PATH) { socket.destroy(); return }
    wss.handleUpgrade(req, socket, head, function (ws) { wss.emit("connection", ws, req) })
})
const clients = new Set()
// DoS-защита: лимит соединений на IP. IP живёт только в памяти,
// в логи НЕ пишется (сохраняем Blind Delivery — сервер слепой к метаданным).
const ipConnections = new Map()
const MAX_CONNECTIONS_PER_IP = 20

// HEARTBEAT: находит полуоткрытые (half-open) сокеты. Телефон резко потерял сеть ->
// соединение висит OPEN минутами, msg 'доставляется' в пустоту. Нет pong за 15с -> terminate
// -> 'close' чистит clients -> следующие сообщения уходят в очередь.
const HEARTBEAT_MS = 15000
setInterval(() => {
    clients.forEach(ws => {
        if (ws.isAlive === false) { console.log('[HB] terminate: нет pong'); ws.terminate(); return }
        ws.isAlive = false
        ws.pingAt = Date.now()
        try { ws.ping() } catch (e) {}
    })
}, HEARTBEAT_MS)

wss.on('connection', (socket, req) => {
    const ip = req.socket.remoteAddress
    // DoS-защита: лимит на IP (IP только в памяти, не в лог)
    const cur = ipConnections.get(ip) || 0
    if (cur >= MAX_CONNECTIONS_PER_IP) {
        console.log(`[-] Connection rejected (limit)`)
        socket.terminate()
        return
    }
    clients.add(socket)
    socket.isAlive = true
    socket.on('pong', () => { socket.isAlive = true })
    ipConnections.set(ip, cur + 1)
    console.log(`[+] Connected | Total: ${clients.size}`)
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
                const ikSign = pkGetIkSign.get(target)   // для проверки подписи SPK
                const ikDh   = pkGetIkDh.get(target)     // для DH1/DH2
                const spk = pkGetSPK.get(target)
                const opk = pkTakeOPK.get(target)   // атомарно берёт+удаляет, или undefined
                socket.send(JSON.stringify({
                    type: 'prekeys_response',
                    targetId: target,
                    ik_sign: ikSign ? ikSign.key_value : null,
                    ik_dh:   ikDh   ? ikDh.key_value   : null,
                    spk: spk ? { value: spk.key_value, sig: spk.signature, keyId: spk.key_id } : null,
                    opk: opk ? { id: opk.key_id, value: opk.key_value } : null
                }))
                console.log(`[PREKEY] request ${target}: opk=${opk ? 'выдан' : 'НЕТ'}`)
                return
            }

            if (msg.type === 'msg') {
                const target = msg.to
                if (!target) { console.log('[!] msg без to — дроп'); return }
                // RELAY-ACK: сначала в очередь; удаляем только по relay_ack получателя.
                // Мёртвый (half-open) сокет больше не теряет сообщение: нет ack -> уйдёт при переподключении.
                const now = Date.now()
                const qid = Number(qInsert.run(target, msg.payload, now, now + TTL_MS).lastInsertRowid)
                let delivered = false
                clients.forEach(client => {
                    if (client.senderId === target && client.readyState === WebSocket.OPEN) {
                        client.send(JSON.stringify({ type: 'msg', to: target, payload: msg.payload, qid }))
                        delivered = true
                    }
                })
                console.log(delivered ? `[>] msg sent, ждём ack` : `[QUEUE] сообщение в очередь (офлайн)`)
                return
            }

            if (msg.type === 'relay_ack') {
                const qid = Number(msg.qid)
                if (socket.senderId && qid > 0) qDeleteFor.run(qid, socket.senderId)   // удалить может только получатель
                return
            }

            if (msg.type === 'channel_post') {
                // Owner publishes. Relay is BLIND: stores the blob, does NOT verify signature or rights.
                if (!msg.channelId || msg.seq == null || !msg.payload) return
                cpInsert.run(msg.channelId, msg.seq|0, msg.payload, Date.now())
                console.log(`[CHAN] post stored`)
                return
            }

            if (msg.type === 'channel_fetch') {
                // Reader pulls. Relay serves posts with seq>sinceSeq. Does NOT know who asked.
                if (!msg.channelId) return
                const posts = cpSelect.all(msg.channelId, msg.sinceSeq|0)
                socket.send(JSON.stringify({ type: 'channel_posts', channelId: msg.channelId, posts }))
                console.log(`[CHAN] fetch served ${posts.length} posts`)
                return
            }
        } catch (e) {
            // лог ошибки (stderr, только в journald — не пользователю, БЕЗ данных сообщения)
            const t = (typeof msg !== 'undefined' && msg && msg.type) ? msg.type : 'parse_error'
            console.error(`[MSG_ERROR] type=${t}: ${e.message}`)
        }
        console.log(`[?] неопознанный пакет ${data.length}b — игнор`)
    })
    socket.on('close', () => {
        clients.delete(socket)
        const c = (ipConnections.get(ip) || 1) - 1
        if (c <= 0) ipConnections.delete(ip); else ipConnections.set(ip, c)
        console.log(`[-] Disconnected | Total: ${clients.size}`)
    })
    socket.on('error', (err) => {
        clients.delete(socket)
        const c = (ipConnections.get(ip) || 1) - 1
        if (c <= 0) ipConnections.delete(ip); else ipConnections.set(ip, c)
        console.log(`[!] Error: ${err.message}`)
    })
})

const PORT = Number(process.env.LCS_PORT) || 443
server.listen(PORT, '0.0.0.0', () => {
    console.log(`[SERVER] Secure WSS relay + offline queue running on wss://${DOMAIN}:${PORT}`)
})
