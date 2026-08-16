package dev.jay.holdmyfiles.core.server

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.withCharset
import io.ktor.server.testing.testApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GuestAssetRoutesTest {
    @Test
    fun `serves the fixed guest application assets`() = testApplication {
        application { holdMyFilesModule(dependencies()) }

        val index = client.get("/") { validHost() }
        val styles = client.get("/assets/app.css") { validHost() }
        val script = client.get("/assets/app.js") { validHost() }

        assertEquals(HttpStatusCode.OK, index.status)
        assertEquals(ContentType.Text.Html.withCharset(Charsets.UTF_8), index.contentType())
        assertEquals("<main>Hold My Files</main>", index.bodyAsText())
        assertEquals(ContentType.Text.CSS.withCharset(Charsets.UTF_8), styles.contentType())
        assertEquals("main { color: navy; }", styles.bodyAsText())
        assertEquals("text/javascript; charset=UTF-8", script.contentType().toString())
        assertEquals("document.title = 'Hold My Files';", script.bodyAsText())
    }

    @Test
    fun `does not map arbitrary asset paths`() = testApplication {
        application { holdMyFilesModule(dependencies()) }

        val response = client.get("/assets/../private.txt") {
            validHost()
        }

        assertEquals(HttpStatusCode.NotFound, response.status)
        assertTrue(response.bodyAsText().contains("route_not_found"))
    }

    private fun dependencies() = ServerDependencies(
        guestAssets = GuestWebAssets(
            indexHtml = "<main>Hold My Files</main>".encodeToByteArray(),
            styleSheet = "main { color: navy; }".encodeToByteArray(),
            script = "document.title = 'Hold My Files';".encodeToByteArray(),
        ),
    )

    private fun io.ktor.client.request.HttpRequestBuilder.validHost() {
        header(HttpHeaders.Host, "localhost:80")
    }
}
