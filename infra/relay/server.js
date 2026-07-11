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
