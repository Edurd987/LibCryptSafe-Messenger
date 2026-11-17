// №55: до входа по подписи релей НЕ должен обрабатывать ничего, кроме auth.
// prekeys_request без входа опустошал чужие OPK и выдавал момент подключения (№54);
// msg без входа ставил сообщения в очередь любому ID (раздувание очереди, база для №43).
// Запуск локально:  LCS_TEST_URL=wss://127.0.0.1:18443/api/v1/sync LCS_TEST_DB=<queue.db> LCS_TEST_INSECURE=1 node preauth_test.js
// Запуск на VPS:    node /opt/libcryptsafe/test/preauth_test.js   (боевой URL и база по умолчанию)
const crypto = require('crypto')
const NM = process.env.NODE_PATH ? '' : '/opt/libcryptsafe/node_modules/'
const WebSocket = require(NM + 'ws')
const Database = require(NM + 'better-sqlite3')
const URL = process.env.LCS_TEST_URL || 'wss://cryptsafe-relay.duckdns.org/api/v1/sync'
const db = new Database(process.env.LCS_TEST_DB || '/opt/libcryptsafe/queue.db', { readonly: true })
const WS_OPTS = process.env.LCS_TEST_INSECURE === '1' ? { rejectUnauthorized: false } : {}
const qCount = to => db.prepare('SELECT COUNT(*) AS n FROM queue WHERE recipient=?').get(to).n
const opkCount = to => db.prepare("SELECT COUNT(*) AS n FROM prekeys WHERE recipient=? AND key_type='OPK'").get(to).n
const sleep = ms => new Promise(r => setTimeout(r, ms))

function newIdentity() {
    const { publicKey, privateKey } = crypto.generateKeyPairSync('ec', { namedCurve: 'P-256' })
    const der = publicKey.export({ type: 'spki', format: 'der' })
    const h = crypto.createHash('sha256').update(der).digest().subarray(0, 8).toString('hex').toUpperCase()
    return { id: h.match(/.{4}/g).join('-'), der, privateKey }
}
// identity=null -> НЕ входим вообще; badSig -> входим с неверной подписью
function open(identity, { badSig } = {}) {
    return new Promise((res, rej) => {
        const ws = new WebSocket(URL, WS_OPTS), inbox = []
        let done = false; const finish = () => { if (!done) { done = true; res({ ws, inbox }) } }
        ws.on('message', d => {
            let m; try { m = JSON.parse(d) } catch (e) { return }
            inbox.push(m)
            if (m.type === 'auth_challenge' && identity) {
                const sig = crypto.sign('sha256', Buffer.from(`LCS-AUTH-v1|${m.nonce}|${identity.id}`), identity.privateKey)
                if (badSig) sig[sig.length - 1] ^= 1
                ws.send(JSON.stringify({ type: 'auth', senderId: identity.id, ik_sign: identity.der.toString('base64'), sig: sig.toString('base64') }))
            }
            if (m.type === 'auth_ok' || m.type === 'auth_fail') setTimeout(finish, 200)
        })
        ws.on('open', () => { if (!identity) setTimeout(finish, 400) })
        ws.on('error', rej)
    })
}
let fail = 0
const check = (name, ok) => { console.log((ok ? 'PASS ' : 'FAIL ') + name); if (!ok) fail++ }
const authed = c => c.inbox.some(m => m.type === 'auth_ok')
const after = (c, mark, type) => c.inbox.slice(mark).filter(m => m.type === type)

;(async () => {
    const owner = newIdentity(), stranger = newIdentity(), T = owner.id
    // Владелец публикует связку: 3 OPK
    const own = await open(owner)
    check('setup: owner authed', authed(own))
    own.ws.send(JSON.stringify({ type: 'prekeys_upload', senderId: T, keys: [
        { type: 'IK_SIGN', id: 0, value: 'ikS' }, { type: 'IK_DH', id: 0, value: 'ikD' },
        { type: 'SPK', id: 1, value: 'spk', sig: 'sig' },
        { type: 'OPK', id: 1, value: 'o1' }, { type: 'OPK', id: 2, value: 'o2' }, { type: 'OPK', id: 3, value: 'o3' }] }))
    await sleep(500)
    check('setup: 3 OPK on relay', opkCount(T) === 3)
    own.ws.close()

    // АТАКА 1: без входа запрашивает связку -> ни ответа, ни расхода OPK
    const anon = await open(null)
    let mark = anon.inbox.length
    for (let i = 0; i < 3; i++) anon.ws.send(JSON.stringify({ type: 'prekeys_request', targetId: T }))
    await sleep(800)
    check('preauth: prekeys_request -> no prekeys_response', after(anon, mark, 'prekeys_response').length === 0)
    check('preauth: OPK not drained', opkCount(T) === 3)

    // АТАКА 2: без входа шлёт сообщение -> не в очереди, без relay_rcvd
    const q0 = qCount(T); mark = anon.inbox.length
    anon.ws.send(JSON.stringify({ type: 'msg', to: T, payload: 'AAAA', cid: 'pre' + Date.now() }))
    anon.ws.send(JSON.stringify({ type: 'msg', to: T, payload: 'BBBB' }))
    await sleep(800)
    check('preauth: msg not queued', qCount(T) === q0)
    check('preauth: msg -> no relay_rcvd', after(anon, mark, 'relay_rcvd').length === 0)
    anon.ws.close()

    // АТАКА 3: вход провален (неверная подпись) -> всё так же отброшено
    const bad = await open(stranger, { badSig: true })
    check('badauth: auth rejected', !authed(bad))
    if (bad.ws.readyState === WebSocket.OPEN) {
        mark = bad.inbox.length
        bad.ws.send(JSON.stringify({ type: 'prekeys_request', targetId: T }))
        bad.ws.send(JSON.stringify({ type: 'msg', to: T, payload: 'CCCC', cid: 'bad' + Date.now() }))
        await sleep(800)
        check('badauth: no prekeys_response', after(bad, mark, 'prekeys_response').length === 0)
        check('badauth: msg not queued', qCount(T) === q0)
        bad.ws.close()
    } else check('badauth: socket closed by relay', true)

    // КОНТРОЛЬ: вошедший незнакомец (своя личность) — работает как раньше
    const ok = await open(stranger)
    check('control: stranger authed', authed(ok))
    mark = ok.inbox.length
    ok.ws.send(JSON.stringify({ type: 'prekeys_request', targetId: T }))
    const cid = 'ok' + Date.now()
    ok.ws.send(JSON.stringify({ type: 'msg', to: T, payload: 'DDDD', cid }))
    await sleep(800)
    const resp = after(ok, mark, 'prekeys_response')
    check('control: prekeys_response with OPK', resp.length === 1 && resp[0].opk !== null)
    check('control: one OPK spent', opkCount(T) === 2)
    check('control: msg queued', qCount(T) === q0 + 1)
    check('control: relay_rcvd', after(ok, mark, 'relay_rcvd').some(m => m.cid === cid))
    ok.ws.close()

    console.log(fail ? `\nFAIL: ${fail}` : '\nВСЕ PASS')
    process.exit(fail ? 1 : 0)
})().catch(e => { console.error('ERROR', e.message); process.exit(2) })
