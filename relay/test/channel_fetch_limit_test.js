// Хвост №57: channel_fetch без входа — лимит частоты (на соединение и на IP) и лимит размера ответа.
// Раньше: сколько угодно запросов; один ответ до 100 постов * ~256 КБ = ~25 МБ (усиление: маленький запрос -> огромный ответ).
// Запуск локально: LCS_TEST_URL=... LCS_TEST_DB=... LCS_TEST_INSECURE=1 NODE_PATH=... node channel_fetch_limit_test.js
const crypto = require('crypto')
const NM = process.env.NODE_PATH ? '' : '/opt/libcryptsafe/node_modules/'
const WebSocket = require(NM + 'ws')
const URL = process.env.LCS_TEST_URL || 'wss://cryptsafe-relay.duckdns.org/api/v1/sync'
const WS_OPTS = process.env.LCS_TEST_INSECURE === '1' ? { rejectUnauthorized: false } : {}
const sleep = ms => new Promise(r => setTimeout(r, ms))
const PER_SOCKET = 30, PER_IP = 120, MAX_BYTES = 512 * 1024   // ожидаемые лимиты релея

function newChannel() {
    const { publicKey, privateKey } = crypto.generateKeyPairSync('ec', { namedCurve: 'P-256' })
    return { id: publicKey.export({ type: 'spki', format: 'der' }).toString('base64'), privateKey }
}
function postFrame(ch, seq, content) {
    const ts = Date.now(), head = Buffer.alloc(16)
    head.writeBigInt64BE(BigInt(seq), 0); head.writeBigInt64BE(BigInt(ts), 8)
    const sig = crypto.sign('sha256', Buffer.concat([head, Buffer.from(content, 'utf8')]), ch.privateKey).toString('base64')
    return JSON.stringify({ type: 'channel_post', channelId: ch.id, seq, payload: JSON.stringify({ ts, content, sig }) })
}
function open() {
    return new Promise((res, rej) => {
        const ws = new WebSocket(URL, WS_OPTS), inbox = [], sizes = []
        ws.on('message', d => { sizes.push(d.length); try { inbox.push(JSON.parse(d)) } catch (e) {} })
        ws.on('open', () => setTimeout(() => res({ ws, inbox, sizes }), 300))
        ws.on('error', rej)
    })
}
const answers = (c, chId) => c.inbox.filter(m => m.type === 'channel_posts' && m.channelId === chId).length
const fetchReq = (chId, since = 0) => JSON.stringify({ type: 'channel_fetch', channelId: chId, sinceSeq: since })
let fail = 0
const check = (name, ok) => { console.log((ok ? 'PASS ' : 'FAIL ') + name); if (!ok) fail++ }

;(async () => {
    const ch = newChannel()
    const own = await open()
    own.ws.send(postFrame(ch, 1, 'маленький пост'))
    await sleep(400)

    // КОНТРОЛЬ: обычный клиент (несколько запросов) — отвечены все
    const a = await open()
    for (let i = 0; i < 5; i++) a.ws.send(fetchReq(ch.id))
    await sleep(600)
    check('control: 5 requests -> 5 answers', answers(a, ch.id) === 5)

    // АТАКА 1: 100 запросов подряд с одного соединения
    const b = await open()
    for (let i = 0; i < 100; i++) b.ws.send(fetchReq(ch.id))
    await sleep(1200)
    const nb = answers(b, ch.id)
    check(`flood: one socket answered ${nb} <= ${PER_SOCKET}`, nb <= PER_SOCKET && nb > 0)

    // Пополнение: через 3 с запас вырос хотя бы на 1
    await sleep(3000)
    const before = answers(b, ch.id)
    b.ws.send(fetchReq(ch.id))
    await sleep(500)
    check('refill: after 3s one more request answered', answers(b, ch.id) === before + 1)

    // АТАКА 2: 5 соединений с одного IP по 40 запросов (лимит соединения 30 -> до 150, лимит IP 120)
    const many = []
    for (let k = 0; k < 5; k++) many.push(await open())
    for (const c of many) for (let i = 0; i < 40; i++) c.ws.send(fetchReq(ch.id))
    await sleep(1500)
    const total = many.reduce((s, c) => s + answers(c, ch.id), 0)
    check(`flood: one IP answered ${total} <= ${PER_IP}`, total <= PER_IP)
    for (const c of many) c.ws.close()

    // АТАКА 3: усиление — 10 постов по ~200 КБ, один запрос
    const big = newChannel(), chunk = 'Ж'.repeat(100 * 1024)            // ~200 КБ в UTF-8
    for (let s = 1; s <= 10; s++) { own.ws.send(postFrame(big, s, chunk)); await sleep(60) }
    await sleep(800)
    const d = await open()
    d.ws.send(fetchReq(big.id))
    await sleep(1200)
    const r1 = d.inbox.find(m => m.type === 'channel_posts' && m.channelId === big.id)
    const bytes1 = d.sizes.length ? Math.max(...d.sizes) : 0
    check(`size: first answer ${Math.round(bytes1 / 1024)} KB <= ~${MAX_BYTES / 1024 + 256} KB`, !!r1 && bytes1 <= MAX_BYTES + 256 * 1024)
    check('size: at least one post returned', !!r1 && r1.posts.length >= 1)
    // Остаток докачивается по sinceSeq, как в клиенте
    let got = r1 ? r1.posts.length : 0, last = r1 && r1.posts.length ? r1.posts[r1.posts.length - 1].seq : 0
    for (let k = 0; k < 10 && got < 10; k++) {
        const mark = d.inbox.length
        d.ws.send(fetchReq(big.id, last)); await sleep(700)
        const r = d.inbox.slice(mark).find(m => m.type === 'channel_posts' && m.channelId === big.id)
        if (!r || !r.posts.length) break
        got += r.posts.length; last = r.posts[r.posts.length - 1].seq
    }
    check(`size: all 10 posts reachable via sinceSeq (got ${got})`, got === 10)
    for (const c of [own, a, b, d]) c.ws.close()
    console.log(fail ? `\nFAIL: ${fail}` : '\nВСЕ PASS')
    process.exit(fail ? 1 : 0)
})().catch(e => { console.error('ERROR', e.message); process.exit(2) })
