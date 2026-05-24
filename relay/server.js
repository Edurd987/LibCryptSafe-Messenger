const WebSocket = require('ws')
const wss = new WebSocket.Server({ port: 8080 })
const clients = new Set()

wss.on('connection', (socket, req) => {
    clients.add(socket)
    const ip = req.socket.remoteAddress
    console.log(`[+] Connected: ${ip} | Total: ${clients.size}`)

    socket.on('message', (data) => {
        console.log(`[>] Message: ${data.length} bytes`)
        // Пересылаем всем кроме отправителя
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

    socket.on('error', (err) => {
        console.log(`[!] Error: ${err.message}`)
    })
})

console.log('[SERVER] WebSocket relay running on ws://0.0.0.0:8080')
