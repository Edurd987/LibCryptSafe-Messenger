// Тест гигиены: старые связки удаляются, свежие (контроль) и смешанные — остаются.
const Database = require('/opt/libcryptsafe/node_modules/better-sqlite3')
const { housekeepPrekeys } = require('/opt/libcryptsafe/housekeeping.js')
const db = new Database('/opt/libcryptsafe/queue.db'); db.pragma('busy_timeout = 5000')
const ins = db.prepare('INSERT INTO prekeys (recipient,key_type,key_id,key_value,signature,created_at) VALUES (?,?,?,?,?,?)')
const cnt = id => db.prepare('SELECT COUNT(*) AS n FROM prekeys WHERE recipient=?').get(id).n
const DAY = 24 * 3600 * 1000, now = Date.now(), r = 1000 + Math.floor(Math.random() * 9000)
const OLD = `HKOL-0000-0000-${r}`, NEW = `HKNW-0000-0000-${r}`, MIX = `HKMX-0000-0000-${r}`
for (let i = 1; i <= 3; i++) ins.run(OLD, 'OPK', i, 'x', null, now - 40 * DAY)
for (let i = 1; i <= 3; i++) ins.run(NEW, 'OPK', i, 'x', null, now)
ins.run(MIX, 'OPK', 1, 'x', null, now - 40 * DAY); ins.run(MIX, 'OPK', 2, 'x', null, now - 40 * DAY); ins.run(MIX, 'OPK', 3, 'x', null, now)
housekeepPrekeys(db, now - 30 * DAY)
let fail = 0
const check = (n, ok) => { console.log((ok ? 'PASS ' : 'FAIL ') + n); if (!ok) fail++ }
check('housekeep: inactive ID removed', cnt(OLD) === 0)
check('housekeep: active ID kept (control)', cnt(NEW) === 3)
check('housekeep: ID with any fresh row kept whole', cnt(MIX) === 3)
db.prepare('DELETE FROM prekeys WHERE recipient IN (?,?)').run(NEW, MIX)
console.log(fail ? `FAILED: ${fail}` : 'ALL PASS'); process.exit(fail ? 1 : 0)
