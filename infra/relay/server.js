const fs = require('fs')
const https = require('https')
const WebSocket = require('ws')

const DOMAIN = 'cryptsafe-relay.duckdns.org'
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
            socket.send(JSON.stringify({ type: 'pubkey', key: other.pubKey }))
            console.log(`[KEY] Sent existing pubkey to new client`)
        }
    })
    socket.on('message', (data) => {
        try {
            const msg = JSON.parse(data.toString())
            if (msg.type === 'pubkey') {
                socket.pubKey = msg.key
                console.log(`[KEY] Received pubkey: ${msg.key.slice(0,16)}...`)
                clients.forEach(client => {
                    if (client !== socket && client.readyState === WebSocket.OPEN) {
                        client.send(JSON.stringify({ type: 'pubkey', key: msg.key }))
                    }
                })
                return
            }
        } catch (e) {}
        console.log(`[>] Message: ${data.length} bytes`)
        clients.forEach(client => {
            if (client !== socket && client.readyState === WebSocket.OPEN) {
                client.send(data)
            }
        })
    })
    socket.on('close', () => {
        clients.delete(socket)
        console.log(`[-] Disconnected | Total: ${clients.size}`)
    })
    socket.on('error', (err) => console.log(`[!] Error: ${err.message}`))
})

const PORT = 8080
server.listen(PORT, '0.0.0.0', () => {
    console.log(`[SERVER] Secure WSS relay running on wss://${DOMAIN}:${PORT}`)
})
