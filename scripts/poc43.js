const WebSocket = require('/opt/libcryptsafe/node_modules/ws')
const [to, kind] = process.argv.slice(2)
const inner = kind === 'init' ? { type: 'INITIAL_HANDSHAKE' } : { type: 'CHAT_ENCRYPTED', cipher: '!!!' }
const ws = new WebSocket('wss://cryptsafe-relay.duckdns.org/api/v1/sync')
ws.on('open', () => {
  ws.send(JSON.stringify({ type: 'msg', to, payload: Buffer.from(JSON.stringify(inner)).toString('base64') }))
  setTimeout(() => { console.log('SENT', kind, '->', to); ws.close() }, 1000)
})
ws.on('error', e => { console.log('ERR', e.message); process.exit(1) })
