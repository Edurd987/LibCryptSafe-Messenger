package com.libcryptsafe

import com.libcryptsafe.util.InitialContent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Первое (X3DH) сообщение: новый формат JSON v1 -> общий конвейер с ACK; старый голый текст -> как раньше. */
class InitialContentTest {
    @Test fun newFormatGoesToChatPipeline() =
        assertTrue(InitialContent.isChatV1("""{"v":1,"n":"078bcbaa-1","t":"привет","ts":1763760000000}"""))
    @Test fun leadingSpacesOk() = assertTrue(InitialContent.isChatV1("""  {"v":1,"n":"x","t":"hi"}"""))
    @Test fun legacyPlainText() = assertFalse(InitialContent.isChatV1("привет"))
    @Test fun textThatStartsWithBrace() = assertFalse(InitialContent.isChatV1("{ это просто скобка"))
    @Test fun jsonWithoutNonce() = assertFalse(InitialContent.isChatV1("""{"v":1,"t":"hi"}"""))
    @Test fun otherVersion() = assertFalse(InitialContent.isChatV1("""{"v":2,"n":"x","t":"hi"}"""))
}
