package dev.jay.holdmyfiles.sharing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShareStateCodecTest {
    @Test
    fun `round trips labels and uris without delimiter ambiguity`() {
        val state = StoredShareState(
            version = 7,
            shares = listOf(
                StoredShare(
                    id = "share_one",
                    label = "Trips | 2026\nPhotos",
                    enabled = true,
                    uri = "content://provider/tree/primary%3APictures%2FTrips?x=a|b",
                ),
            ),
        )

        assertEquals(state, ShareStateCodec.decode(ShareStateCodec.encode(state)))
    }

    @Test
    fun `malformed persisted state is rejected`() {
        val malformedStates = listOf(
            "",
            "HMF1\n-1\n0",
            "HMF1\n1\n65",
            "HMF1\n1\n1\nnot.a.valid.record.extra",
        )

        malformedStates.forEach { state ->
            assertNull(ShareStateCodec.decode(state))
        }
    }
}
