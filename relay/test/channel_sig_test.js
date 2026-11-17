// №57: релей сохраняет пост канала ТОЛЬКО с верной подписью владельца канала.
// channelId = Base64(SPKI DER P-256 владельца, 91 байт); подпись ECDSA P-256 + SHA-256 (DER)
// над [seq 8Б BE][ts 8Б BE][content UTF-8] — тот же формат, что ChannelSerializer.kt и channel_sign (C++).
// Раньше: любой (даже без входа) занимал номер seq мусором -> настоящий пост владельца отбрасывался (INSERT OR IGNORE).
// Запуск локально: LCS_TEST_URL=... LCS_TEST_DB=... LCS_TEST_INSECURE=1 NODE_PATH=... node channel_sig_test.js
const crypto = require('crypto')
const NM = process.env.NODE_PATH ? '' : '/opt/libcryptsafe/node_modules/'
const WebSocket = require(NM + 'ws')
const Database = require(NM + 'better-sqlite3')
const URL = process.env.LCS_TEST_URL || 'wss://cryptsafe-relay.duckdns.org/api/v1/sync'
const db = new Database(process.env.LCS_TEST_DB || '/opt/libcryptsafe/queue.db', { readonly: true })
const WS_OPTS = process.env.LCS_TEST_INSECURE === '1' ? { rejectUnauthorized: false } : {}
const sleep = ms => new Promise(r => setTimeout(r, ms))
const rows = ch => db.prepare('SELECT seq, payload FROM channel_posts WHERE channel_id=? ORDER BY seq').all(ch)

function newChannel() {
    const { publicKey, privateKey } = crypto.generateKeyPairSync('ec', { namedCurve: 'P-256' })
    const der = publicKey.export({ type: 'spki', format: 'der' })
    return { id: der.toString('base64'), der, privateKey }
}
function signed(priv, seq, ts, content) {
    const head = Buffer.alloc(16); head.writeBigInt64BE(BigInt(seq), 0); head.writeBigInt64BE(BigInt(ts), 8)
    return crypto.sign('sha256', Buffer.concat([head, Buffer.from(content, 'utf8')]), priv).toString('base64')
}
const post = (channelId, seq, payloadObj) => JSON.stringify({ type: 'channel_post', channelId, seq, payload: JSON.stringify(payloadObj) })

function open() {
    return new Promise((res, rej) => {
        const ws = new WebSocket(URL, WS_OPTS), inbox = []
        ws.on('message', d => { try { inbox.push(JSON.parse(d)) } catch (e) {} })
        ws.on('open', () => setTimeout(() => res({ ws, inbox }), 300))
        ws.on('error', rej)
    })
}
let fail = 0
const check = (name, ok) => { console.log((ok ? 'PASS ' : 'FAIL ') + name); if (!ok) fail++ }

;(async () => {
    const owner = newChannel(), eve = newChannel(), C = owner.id, ts = Date.now()
    check('setup: channelId = 91-byte SPKI', owner.der.length === 91)
    const c = await open()          // каналы доступны без входа (по замыслу), поэтому атакуем анонимно

    // АТАКА 1: чужая ВЕРНАЯ подпись (ключ Евы) на номер 1 канала владельца
    c.ws.send(post(C, 1, { ts, content: 'спам', sig: signed(eve.privateKey, 1, ts, 'спам') }))
    // АТАКА 2: битая подпись, номер 2
    c.ws.send(post(C, 2, { ts, content: 'мусор', sig: 'AAAA' }))
    // АТАКА 3: без подписи / payload не JSON, номера 3 и 4
    c.ws.send(post(C, 3, { ts, content: 'без подписи' }))
    c.ws.send(JSON.stringify({ type: 'channel_post', channelId: C, seq: 4, payload: 'не json' }))
    await sleep(800)
    check('attack: foreign/bad/missing signatures not stored', rows(C).length === 0)

    // ВЛАДЕЛЕЦ публикует 1 и 2 — номера НЕ должны быть заняты атакой
    c.ws.send(post(C, 1, { ts, content: 'пост 1', sig: signed(owner.privateKey, 1, ts, 'пост 1') }))
    c.ws.send(post(C, 2, { ts, content: 'пост 2', sig: signed(owner.privateKey, 2, ts, 'пост 2') }))
    await sleep(800)
    const got = rows(C)
    check('owner: seq 1 and 2 stored (not squatted)', got.length === 2 && got.every((r, i) => JSON.parse(r.payload).content === `пост ${i + 1}`))

    // АТАКА 4: пост владельца переслан под ДРУГИМ номером (подпись покрывает seq)
    c.ws.send(post(C, 7, { ts, content: 'пост 1', sig: signed(owner.privateKey, 1, ts, 'пост 1') }))
    // АТАКА 5: подпись владельца, но подменён текст
    c.ws.send(post(C, 8, { ts, content: 'подмена', sig: signed(owner.privateKey, 8, ts, 'пост 8') }))
    await sleep(600)
    check('attack: replayed/altered owner post not stored', rows(C).length === 2)

    // АТАКА 6: channelId — не ключ P-256 (RSA, мусор), подпись «своя»
    const rsa = crypto.generateKeyPairSync('rsa', { modulusLength: 2048 })
    const rsaId = rsa.publicKey.export({ type: 'spki', format: 'der' }).toString('base64')
    c.ws.send(post(rsaId, 1, { ts, content: 'x', sig: signed(rsa.privateKey, 1, ts, 'x') }))
    c.ws.send(post('bm90LWEta2V5', 1, { ts, content: 'x', sig: 'AAAA' }))
    // АТАКА 7: тот же ключ владельца, но НЕканоничная запись channelId (без '=')
    const alias = C.replace(/=+$/, '')
    c.ws.send(post(alias, 9, { ts, content: 'alias', sig: signed(owner.privateKey, 9, ts, 'alias') }))
    // АТАКА 8: номер вне допустимого (0, отрицательный, дробный, гигантский)
    for (const s of [0, -1, 1.5, 2 ** 40]) c.ws.send(post(C, s, { ts, content: 'n', sig: signed(owner.privateKey, Math.trunc(s) || 0, ts, 'n') }))
    await sleep(800)
    check('attack: RSA/garbage channelId not stored', rows(rsaId).length === 0 && rows('bm90LWEta2V5').length === 0)
    check('attack: non-canonical channelId not stored', alias === C || rows(alias).length === 0)
    check('attack: bad seq values not stored', rows(C).length === 2)

    // КОНТРОЛЬ: чтение без входа работает и отдаёт ровно посты владельца
    const mark = c.inbox.length
    c.ws.send(JSON.stringify({ type: 'channel_fetch', channelId: C, sinceSeq: 0 }))
    await sleep(600)
    const resp = c.inbox.slice(mark).find(m => m.type === 'channel_posts' && m.channelId === C)
    check('control: fetch returns the 2 owner posts', !!resp && resp.posts.length === 2)
    c.ws.close()
    console.log(fail ? `\nFAIL: ${fail}` : '\nВСЕ PASS')
    process.exit(fail ? 1 : 0)
})().catch(e => { console.error('ERROR', e.message); process.exit(2) })
