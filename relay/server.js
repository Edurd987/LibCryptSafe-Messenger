const WebSocket = require('ws')
const wss = new WebSocket.Server({ port: 8080 })
const clients = new Set()

wss.on('connection', (socket, req) => {
    clients.add(socket)
    const ip = req.socket.remoteAddress
    console.log(`[+] Connected: ${ip} | Total: ${clients.size}`)

    // Отправляем новому клиенту публичные ключи всех остальных
    clients.forEach(other => {
        if (other !== socket && other.readyState === WebSocket.OPEN && other.pubKey) {
            socket.send(JSON.stringify({
                type: 'pubkey',
                key: other.pubKey
            }))
            console.log(`[KEY] Sent existing pubkey to new client`)
        }
    })

    socket.on('message', (data) => {
        try {
            const msg = JSON.parse(data.toString())

            if (msg.type === 'pubkey') {
                // Сохраняем публичный ключ и рассылаем всем
                socket.pubKey = msg.key
                console.log(`[KEY] Received pubkey: ${msg.key.slice(0,16)}...`)
                clients.forEach(client => {
                    if (client !== socket && client.readyState === WebSocket.OPEN) {
                        client.send(JSON.stringify({
                            type: 'pubkey',
                            key: msg.key
                        }))
                    }
                })
                return
            }
        } catch (e) {
            // Не JSON — обычное зашифрованное сообщение
        }

        // Пересылаем бинарные данные всем кроме отправителя
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

    socket.on('error', (err) => {
        console.log(`[!] Error: ${err.message}`)
    })
})

console.log('[SERVER] WebSocket relay running on ws://0.0.0.0:8080')
