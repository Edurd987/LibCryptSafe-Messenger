// Протокольные тесты relay. Запуск на VPS: node /opt/libcryptsafe/test/relay_protocol_test.js
const crypto = require('crypto')
const WebSocket = require('/opt/libcryptsafe/node_modules/ws')
const Database = require('/opt/libcryptsafe/node_modules/better-sqlite3')
const URL = 'wss://cryptsafe-relay.duckdns.org/api/v1/sync'
const db = new Database('/opt/libcryptsafe/queue.db', { readonly: true })
const qCount = to => db.prepare('SELECT COUNT(*) AS n FROM queue WHERE recipient=?').get(to).n
const pkCount = to => db.prepare('SELECT COUNT(*) AS n FROM prekeys WHERE recipient=?').get(to).n
const sleep = ms => new Promise(r => setTimeout(r, ms))
function newIdentity() {
    const { publicKey, privateKey } = crypto.generateKeyPairSync('ec', { namedCurve: 'P-256' })
    const der = publicKey.export({ type: 'spki', format: 'der' })
    const h = crypto.createHash('sha256').update(der).digest().subarray(0, 8).toString('hex').toUpperCase()
    return { id: h.match(/.{4}/g).join('-'), der, privateKey }
}
// identity=null -> анонимно; claim — какой ID заявить; badNonce — подписать чужой вызов
function open(identity, { claim, badNonce } = {}) {
    return new Promise((res, rej) => {
        const ws = new WebSocket(URL), inbox = []
        let done = false; const finish = () => { if (!done) { done = true; res({ ws, inbox }) } }
        ws.on('message', d => {
            let m; try { m = JSON.parse(d) } catch (e) { return }
            inbox.push(m)
            if (m.type === 'auth_challenge' && identity) {
                const id = claim || identity.id
                const nonce = badNonce ? 'f'.repeat(64) : m.nonce
                const sig = crypto.sign('sha256', Buffer.from(`LCS-AUTH-v1|${nonce}|${id}`), identity.privateKey)
                ws.send(JSON.stringify({ type: 'auth', senderId: id, ik_sign: identity.der.toString('base64'), sig: sig.toString('base64') }))
            }
            if (m.type === 'auth_ok' || m.type === 'auth_fail') setTimeout(finish, 300)
        })
        ws.on('open', () => { if (!identity) setTimeout(finish, 500) })
        ws.on('error', rej)
    })
}
let fail = 0
const check = (name, ok) => { console.log((ok ? 'PASS ' : 'FAIL ') + name); if (!ok) fail++ }
const authed = c => c.inbox.some(m => m.type === 'auth_ok')

;(async () => {
    const owner = newIdentity(), attacker = newIdentity(), T = owner.id
    const a = await open(null)

    const cid = 'testcid' + Date.now()   // ЦЕЛОСТНОСТЬ: дубли по cid
    a.ws.send(JSON.stringify({ type: 'msg', to: T, payload: 'AAAA', cid }))
    a.ws.send(JSON.stringify({ type: 'msg', to: T, payload: 'AAAA', cid }))
    await sleep(1000)
    check('dedupe: 2 relay_rcvd', a.inbox.filter(m => m.type === 'relay_rcvd' && m.cid === cid).length === 2)
    check('dedupe: 1 row in queue', qCount(T) === 1)

    const mark = a.inbox.length           // СОВМЕСТИМОСТЬ: без cid
    a.ws.send(JSON.stringify({ type: 'msg', to: T, payload: 'BBBB' }))
    await sleep(1000)
    check('compat: no relay_rcvd without cid', a.inbox.slice(mark).every(m => m.type !== 'relay_rcvd'))
    check('compat: queued', qCount(T) === 2)

    // АТАКА 1: самозванец заявляет ID владельца со своим ключом
    const imp = await open(attacker, { claim: T }); await sleep(800)
    check('auth: impostor rejected', !authed(imp))
    check('auth: impostor got no queue', !imp.inbox.some(m => m.type === 'msg'))
    check('auth: queue intact after impostor', qCount(T) === 2)

    // АТАКА 2: подпись не того вызова (повтор/подделка)
    const replay = await open(owner, { badNonce: true }); await sleep(500)
    check('auth: wrong-nonce signature rejected', !authed(replay))

    // АТАКА 3: вошёл честно под своим ID, но шлёт relay_ack и ключи под чужим
    const atk = await open(attacker); await sleep(300)
    check('auth: attacker authed as self', authed(atk))
    const row = db.prepare('SELECT id FROM queue WHERE recipient=? LIMIT 1').get(T)
    atk.ws.send(JSON.stringify({ type: 'relay_ack', qid: row.id }))
    atk.ws.send(JSON.stringify({ type: 'prekeys_upload', senderId: T, keys: [{ type: 'SPK', id: 1, value: 'X' }] }))
    await sleep(800)
    check('security: foreign relay_ack ignored', qCount(T) === 2)
    check('security: upload under foreign ID ignored', pkCount(T) === 0)

    // КОНТРОЛЬ: настоящий владелец входит по подписи, забирает, подтверждает
    const own = await open(owner); await sleep(1200)
    check('control: owner authed', authed(own))
    const got = own.inbox.filter(m => m.type === 'msg' && m.to === T)
    check('control: owner received 2', got.length === 2)
    got.forEach(m => own.ws.send(JSON.stringify({ type: 'relay_ack', qid: m.qid })))
    await sleep(800)
    check('control: owner ack deletes', qCount(T) === 0)

    ;[a, imp, replay, atk, own].forEach(c => c.ws.close())
    console.log(fail ? `FAILED: ${fail}` : 'ALL PASS')
    process.exit(fail ? 1 : 0)
})().catch(e => { console.error('ERROR', e.message); process.exit(2) })
