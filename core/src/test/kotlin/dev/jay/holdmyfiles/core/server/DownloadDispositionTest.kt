package dev.jay.holdmyfiles.core.server

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadDispositionTest {
    @Test
    fun `creates ascii and utf8 attachment names`() {
        assertEquals(
            "attachment; filename=\"report.pdf\"; filename*=UTF-8''report.pdf",
            attachmentContentDisposition("report.pdf"),
        )
        assertEquals(
            "attachment; filename=\"resume _.pdf\"; " +
                "filename*=UTF-8''r%C3%A9sum%C3%A9%20%F0%9F%98%80.pdf",
            attachmentContentDisposition("résumé 😀.pdf"),
        )
    }

    @Test
    fun `removes header and path control characters`() {
        val header = attachmentContentDisposition(
            "../secret\r\nX-Evil: yes/\\\"name\u202Etxt.exe",
        )

        assertFalse(header.contains('\r'))
        assertFalse(header.contains('\n'))
        assertFalse(header.contains("\\"))
        assertFalse(header.contains("\u202E"))
        assertFalse(header.contains("filename=\"../"))
    }

    @Test
    fun `falls back for unsafe empty names`() {
        listOf("", "   ", ".", "..", "\r\n").forEach { name ->
            assertTrue(attachmentContentDisposition(name).contains("filename=\"download\""))
        }
    }

    @Test
    fun `bounds long names while preserving a short extension`() {
        val header = attachmentContentDisposition("a".repeat(2_000) + ".pdf")

        assertTrue(header.length < 700)
        assertTrue(header.contains(".pdf"))
    }
}
