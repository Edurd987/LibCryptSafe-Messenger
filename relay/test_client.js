const WebSocket = require('ws')
const ws = new WebSocket('ws://127.0.0.1:8080')

ws.on('open', () => {
    console.log('[CLIENT] Connected to relay')
})

ws.on('message', (data) => {
    console.log(`[CLIENT] Received: ${data.length} bytes`)
    console.log(`[CLIENT] Raw hex: ${Buffer.from(data).toString('hex').slice(0,32)}...`)
})
