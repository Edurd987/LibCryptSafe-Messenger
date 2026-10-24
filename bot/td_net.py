"""
Сеть TD-Gammon на чистом numpy, FLOAT64, без PyTorch: 196 -> 80 (сигмоида) -> 5 (сигмоида).
Прямой проход = две матрицы, переносится в Kotlin один в один (Double).
Детерминизм: CPU, фиксированный seed, фиксированный порядок операций.
"""
import hashlib
import numpy as np
from encode import N_IN

N_HID, N_OUT = 80, 5
MAGIC = b"NRD1"


def sig(z):
    return 1.0 / (1.0 + np.exp(-z))


class Net:
    def __init__(self, seed=0, hidden=N_HID):
        r = np.random.default_rng(seed)
        self.W1 = r.uniform(-0.1, 0.1, (hidden, N_IN))
        self.b1 = np.zeros(hidden)
        self.W2 = r.uniform(-0.1, 0.1, (N_OUT, hidden))
        self.b2 = np.zeros(N_OUT)

    def params(self):
        return [self.W1, self.b1, self.W2, self.b2]

    def forward(self, X):
        """X: (n, 196) или (196,) -> (n, 5) или (5,)."""
        h = sig(X @ self.W1.T + self.b1)
        return sig(h @ self.W2.T + self.b2)

    def grads(self, x):
        """Значение (5,) и градиенты КАЖДОГО выхода по всем весам: [(5,…) на параметр]."""
        h = sig(self.W1 @ x + self.b1)
        o = sig(self.W2 @ h + self.b2)
        do = o * (1 - o)                                   # (5,)
        dh = h * (1 - h)                                   # (80,)
        gW2 = np.zeros((N_OUT,) + self.W2.shape)
        gW2[np.arange(N_OUT), np.arange(N_OUT)] = do[:, None] * h
        gb2 = np.diag(do)
        gpre = do[:, None] * self.W2 * dh                  # (5, 80)
        gW1 = gpre[:, :, None] * x[None, None, :]          # (5, 80, 196)
        return o, [gW1, gpre, gW2, gb2]

    def digest(self):
        """Отпечаток весов (sha256 байтов) — для проверки детерминизма."""
        m = hashlib.sha256()
        for p in self.params():
            m.update(np.ascontiguousarray(p, dtype="<f8").tobytes())
        return m.hexdigest()[:16]

    def save(self, path):
        """Простой бинарник для Kotlin: MAGIC, hidden(int32), затем W1,b1,W2,b2 как little-endian float64."""
        with open(path, "wb") as f:
            f.write(MAGIC + np.int32(self.W1.shape[0]).tobytes())
            for p in self.params():
                f.write(np.ascontiguousarray(p, dtype="<f8").tobytes())

    @classmethod
    def load(cls, path):
        raw = open(path, "rb").read()
        assert raw[:4] == MAGIC, "не файл весов нард"
        hid = int(np.frombuffer(raw[4:8], "<i4")[0])
        n = cls(hidden=hid)
        off = 8
        for p in n.params():
            k = p.size
            p[...] = np.frombuffer(raw[off:off + 8 * k], "<f8").reshape(p.shape)
            off += 8 * k
        assert off == len(raw), "лишние/недостающие байты в файле весов"
        return n
