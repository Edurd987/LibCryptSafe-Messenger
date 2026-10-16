// Гигиена relay: удалить связки ключей ID, не входивших дольше IDLE_MS.
// Свежесть = MAX(created_at) по ID: клиент публикует снимок связки при КАЖДОМ входе,
// поэтому created_at обновляется сам — отдельная колонка last_active не нужна.
// Связка удаляется целиком, только если ВСЕ её строки старые (ID неактивен).
const IDLE_MS = 30 * 24 * 3600 * 1000
function housekeepPrekeys(db, cutoff) {
    return db.prepare(`DELETE FROM prekeys WHERE recipient IN (
        SELECT recipient FROM prekeys GROUP BY recipient HAVING MAX(created_at) < ?)`).run(cutoff).changes
}
module.exports = { IDLE_MS, housekeepPrekeys }
