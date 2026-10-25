package com.libcryptsafe

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.libcryptsafe.db.KeyStoreManager
import org.junit.Assert.*
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runners.MethodSorters
import org.junit.runner.RunWith
import java.io.File

// №35: ID последнего собеседника не должен лежать в shared_prefs открытым текстом.
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)   // (a) первым: проверяет файл ДО того, как тесты что-то перешифруют
class LastPeerTest {
    private val prefs get() = ctx.getSharedPreferences("libcryptsafe_secure_prefs", android.content.Context.MODE_PRIVATE)
    private val probe = "TEST-0000-1111-2222"
    private fun restore(orig: String?) { if (orig != null) KeyStoreManager.saveLastPeer(ctx, orig) }
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private fun prefsXml() = File(ctx.dataDir, "shared_prefs/libcryptsafe_secure_prefs.xml").readText()

    // (a) настоящий файл настроек приложения: открытого last_peer_id нет
    @Test fun a_no_plaintext_last_peer_in_real_prefs() {
        val xml = prefsXml()
        assertTrue("контроль: читаем правильный файл", xml.contains("\"enc_passphrase\""))
        assertFalse("ID собеседника лежит открыто", xml.contains("\"last_peer_id\""))
    }

    // (b) запись + чтение: в файле только шифротекст, ID открытым текстом не встречается
    @Test fun b_roundtrip_no_plaintext() {
        val orig = KeyStoreManager.loadLastPeer(ctx)
        KeyStoreManager.saveLastPeer(ctx, probe)
        val xml = prefsXml()
        assertFalse("ID найден открытым текстом", xml.contains(probe))
        assertTrue("шифротекст на месте", xml.contains("\"last_peer_enc\""))
        assertEquals(probe, KeyStoreManager.loadLastPeer(ctx))
        restore(orig)
    }

    // (c) миграция: старая открытая запись читается, шифруется и исчезает
    @Test fun c_legacy_migrates() {
        val orig = KeyStoreManager.loadLastPeer(ctx)
        prefs.edit().putString("last_peer_id", probe).commit()
        assertEquals(probe, KeyStoreManager.loadLastPeer(ctx))
        val xml = prefsXml()
        assertFalse("старая открытая запись осталась", xml.contains("\"last_peer_id\"") || xml.contains(probe))
        restore(orig)
    }

    // (d) подделанный шифротекст -> null, без падения (GCM-тег не сходится)
    @Test fun d_tampered_returns_null() {
        val orig = KeyStoreManager.loadLastPeer(ctx)
        KeyStoreManager.saveLastPeer(ctx, probe)
        prefs.edit().putString("last_peer_enc", "AAAAAAAAAAAAAAAAAAAAAAAAAAAA").commit()
        assertNull(KeyStoreManager.loadLastPeer(ctx))
        restore(orig)
    }
}
