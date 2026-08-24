package dev.jay.holdmyfiles.server

import android.content.res.AssetManager
import dev.jay.holdmyfiles.core.server.GuestWebAssets
import java.io.ByteArrayOutputStream

class AndroidGuestAssetLoader(
    private val assets: AssetManager,
) {
    fun load(): GuestWebAssets = GuestWebAssets(
        indexHtml = readAsset(INDEX_PATH, MAX_INDEX_BYTES),
        styleSheet = readAsset(STYLE_PATH, MAX_STYLE_BYTES),
        script = readAsset(SCRIPT_PATH, MAX_SCRIPT_BYTES),
    )

    private fun readAsset(path: String, maximumBytes: Int): ByteArray =
        assets.open(path, AssetManager.ACCESS_STREAMING).use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(BUFFER_BYTES)
            var totalBytes = 0
            while (true) {
                val count = input.read(buffer)
                if (count == -1) {
                    break
                }
                check(count > 0) { "Bundled guest asset could not be read" }
                totalBytes = Math.addExact(totalBytes, count)
                check(totalBytes <= maximumBytes) { "Bundled guest asset is too large" }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }

    private companion object {
        const val INDEX_PATH = "web/index.html"
        const val STYLE_PATH = "web/assets/app.css"
        const val SCRIPT_PATH = "web/assets/app.js"
        const val MAX_INDEX_BYTES = 256 * 1_024
        const val MAX_STYLE_BYTES = 512 * 1_024
        const val MAX_SCRIPT_BYTES = 2 * 1_024 * 1_024
        const val BUFFER_BYTES = 8 * 1_024
    }
}
