// Протокольные тесты relay: дубли по cid, совместимость без cid, чужой relay_ack.
// Запуск на VPS: node /opt/libcryptsafe/test/relay_protocol_test.js
const WebSocket = require('/opt/libcryptsafe/node_modules/ws')
const Database = require('/opt/libcryptsafe/node_modules/better-sqlite3')
const URL = 'wss://cryptsafe-relay.duckdns.org/api/v1/sync'
const db = new Database('/opt/libcryptsafe/queue.db', { readonly: true })
const count = to => db.prepare('SELECT COUNT(*) AS n FROM queue WHERE recipient=?').get(to).n
const sleep = ms => new Promise(r => setTimeout(r, ms))
function open(id) {
    return new Promise((res, rej) => {
        const ws = new WebSocket(URL), inbox = []
        ws.on('message', d => { try { inbox.push(JSON.parse(d)) } catch (e) {} })
        ws.on('open', () => { if (id) ws.send(JSON.stringify({ type: 'pubkey', key: 'TEST', senderId: id })); res({ ws, inbox }) })
        ws.on('error', rej)
    })
}
let fail = 0
const check = (name, ok) => { console.log((ok ? 'PASS ' : 'FAIL ') + name); if (!ok) fail++ }

;(async () => {
    const T = 'TEST-0000-0000-' + (1000 + Math.floor(Math.random() * 9000))   // офлайн-получатель
    const a = await open(null); await sleep(500)

    // ЦЕЛОСТНОСТЬ: повтор с тем же cid -> 2 подтверждения, 1 запись
    const cid = 'testcid' + Date.now()
    a.ws.send(JSON.stringify({ type: 'msg', to: T, payload: 'AAAA', cid }))
    a.ws.send(JSON.stringify({ type: 'msg', to: T, payload: 'AAAA', cid }))
    await sleep(1000)
    check('dedupe: 2 relay_rcvd', a.inbox.filter(m => m.type === 'relay_rcvd' && m.cid === cid).length === 2)
    check('dedupe: 1 row in queue', count(T) === 1)

    // СОВМЕСТИМОСТЬ: без cid -> без подтверждения, но в очереди
    const mark = a.inbox.length
    a.ws.send(JSON.stringify({ type: 'msg', to: T, payload: 'BBBB' }))
    await sleep(1000)
    check('compat: no relay_rcvd without cid', a.inbox.slice(mark).every(m => m.type !== 'relay_rcvd'))
    check('compat: queued', count(T) === 2)

    // БЕЗОПАСНОСТЬ: чужой relay_ack не удаляет
    const row = db.prepare('SELECT id FROM queue WHERE recipient=? LIMIT 1').get(T)
    const atk = await open('ATTK-0000-0000-0001'); await sleep(500)
    atk.ws.send(JSON.stringify({ type: 'relay_ack', qid: row.id }))
    await sleep(800)
    check('security: foreign relay_ack ignored', count(T) === 2)

    // КОНТРОЛЬ: владелец забирает и подтверждает -> удалено
    const own = await open(T); await sleep(1500)
    const got = own.inbox.filter(m => m.type === 'msg' && m.to === T)
    check('control: owner received 2', got.length === 2)
    got.forEach(m => own.ws.send(JSON.stringify({ type: 'relay_ack', qid: m.qid })))
    await sleep(800)
    check('control: owner ack deletes', count(T) === 0)

    ;[a, atk, own].forEach(c => c.ws.close())
    console.log(fail ? `FAILED: ${fail}` : 'ALL PASS')
    process.exit(fail ? 1 : 0)
})().catch(e => { console.error('ERROR', e.message); process.exit(2) })
