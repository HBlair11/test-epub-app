package com.epubreader.app.epub

import android.util.Log
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipFile

/**
 * Serves EPUB-internal resources (images, CSS, fonts, XHTML) to WebViews through
 * virtual https://epub.local/<bookId>/<path> URLs.
 *
 * Patch 16 (Issue #1): font obfuscation. Some commercial EPUBs (e.g. "The Love
 * Hypothesis") ship their embedded fonts obfuscated via META-INF/encryption.xml,
 * using either the IDPF algorithm
 * (http://www.idpf.org/2008/embedding) or the Adobe algorithm
 * (http://ns.adobe.com/pdf/enc#RC). Calibre and Readium transparently
 * de-obfuscate these fonts before rendering; without the same step the WebView
 * receives garbled font bytes and silently falls back to a default system font,
 * which is why Publisher-font mode showed broken/missing glyphs even though the
 * book's CSS declared the @font-face correctly. This resolver now detects
 * obfuscated font entries, de-obfuscates them in memory (the EPUB file on disk
 * is never modified), and serves the cleartext font bytes to the WebView.
 *
 * Also fixed: binary resources (fonts/images/etc.) are now served with a null
 * encoding instead of "UTF-8", which previously set a bogus charset on binary
 * responses.
 */
class EpubResourceResolver(
    private val epubFile: File,
) {
    private var zip: ZipFile? = null
    private val mimeCache = HashMap<String, String>()

    // Patch 16 (Issue #1): lazily-parsed font obfuscation map.
    // normalizedEntryPath -> algorithm URI string.
    private var obfuscationMap: Map<String, String>? = null
    private var uniqueIdentifier: String? = null

    @Synchronized
    private fun ensureOpen(): ZipFile {
        zip?.let { return it }

        return ZipFile(epubFile).also { openedZip ->
            zip = openedZip
        }
    }

    /**
     * Reads one EPUB entry fully while the ZipFile is protected by this resolver's lock.
     *
     * Do not expose ZipFile's original InputStream to WebView: WebView may read that
     * stream after this function returns, concurrently with another WebView request.
     */
    @Synchronized
    private fun resolveBytes(entryPath: String): ByteArray? {
        return try {
            val zipFile = ensureOpen()
            // Patch 16 (Issue #1): normalize the requested path so that relative
            // font URLs resolved by the WebView against the CSS file's URL
            // (e.g. ../Fonts/foo.otf) collapse ./ and ../ segments and match the
            // real ZIP entry name.
            val normalized = EpubPaths.normalize(entryPath)
            val zipEntry = zipFile.getEntry(normalized)
                ?: zipFile.getEntry(entryPath)
                ?: return null

            val raw = zipFile.getInputStream(zipEntry).use { stream ->
                stream.readBytes()
            }

            deobfuscateIfNeeded(normalized, raw) ?: raw
        } catch (e: Exception) {
            Log.w(TAG, "resolve failed for $entryPath: ${e.message}")
            null
        }
    }

    /**
     * Patch 16 (Issue #1): if [entryPath] is a font registered in encryption.xml,
     * return a fresh de-obfuscated copy of [bytes]; otherwise return null so the
     * caller uses [bytes] verbatim.
     */
    private fun deobfuscateIfNeeded(entryPath: String, bytes: ByteArray): ByteArray? {
        val algorithm = obfuscatedAlgorithmFor(entryPath) ?: return null
        val key = obfuscationKeyFor(algorithm) ?: return null
        return try {
            deobfuscate(bytes, algorithm, key)
        } catch (e: Exception) {
            Log.w(TAG, "font deobfuscation failed for $entryPath (${e.message})")
            null
        }
    }

    private fun obfuscatedAlgorithmFor(entryPath: String): String? {
        ensureObfuscationParsed()
        val map = obfuscationMap ?: return null
        if (map.isEmpty()) return null
        // Match on the normalized entry path; also try a case-insensitive
        // match as a fallback, since some encryption.xml entries use different
        // casing than the manifest href.
        map[entryPath]?.let { return it }
        return map.entries.firstOrNull { it.key.equals(entryPath, ignoreCase = true) }?.value
    }

    private fun obfuscationKeyFor(algorithm: String): ByteArray? {
        ensureObfuscationParsed()
        val id = uniqueIdentifier ?: return null
        return when (algorithm) {
            ALGO_IDPF -> {
                // IDPF: SHA-1 of the unique identifier with all whitespace
                // stripped, giving a 20-byte key.
                val stripped = id.replace(Regex("\\s"), "")
                MessageDigest.getInstance("SHA-1").digest(stripped.toByteArray(Charsets.ISO_8859_1))
            }

            ALGO_ADOBE -> {
                // Adobe: the unique identifier is a UUID; strip a urn:uuid: or
                // uuid: prefix (case-insensitive) and hyphens, hex-decode the
                // remainder to 16 bytes. (Patch 16 Issue #1, per advisor: handle
                // both urn:uuid: and bare uuid: prefixes robustly.)
                val lower = id.lowercase()
                val withoutPrefix = lower.removePrefix("urn:uuid:").removePrefix("uuid:")
                val hex = withoutPrefix.replace("-", "").replace(" ", "")
                hexToBytes(hex)
            }

            else -> null
        }
    }

    /** XOR the first [headLen] bytes of [bytes] with [key] repeated. */
    private fun deobfuscate(bytes: ByteArray, algorithm: String, key: ByteArray): ByteArray {
        val headLen = if (algorithm == ALGO_ADOBE) ADOBE_HEAD_LEN else IDPF_HEAD_LEN
        val out = bytes.copyOf()
        val limit = minOf(headLen, out.size)
        var i = 0
        while (i < limit) {
            out[i] = (out[i].toInt() xor key[i % key.size].toInt()).toByte()
            i++
        }
        return out
    }

    @Synchronized
    private fun ensureObfuscationParsed() {
        if (obfuscationMap != null) return
        obfuscationMap = emptyMap()
        try {
            val zipFile = ensureOpen()
            val encryptionEntry = zipFile.getEntry("META-INF/encryption.xml") ?: return
            val (map, uid) = parseEncryption(zipFile.getInputStream(encryptionEntry))
            obfuscationMap = map
            uniqueIdentifier = uid ?: readUniqueIdentifier()
        } catch (e: Exception) {
            Log.w(TAG, "encryption.xml parse failed: ${e.message}")
        }
    }

    /**
     * Parses META-INF/encryption.xml and returns (entryPath -> algorithm) plus
     * the obfuscation key identifier declared inside the encryption descriptor
     * (if present). The OPF unique identifier is fetched separately by
     * [readUniqueIdentifier] when needed, because encryption.xml does not always
     * carry it.
     */
    private fun parseEncryption(input: InputStream): Pair<Map<String, String>, String?> {
        // encryption.xml (OCF spec) shape:
        // <encryption><EncryptedData><EncryptionMethod Algorithm="..."/>
        //   <CipherData><CipherReference URI="..."/></CipherData>
        // </EncryptedData></encryption>
        val map = HashMap<String, String>()
        val xpp = XmlPullParserFactoryShared.newPullParser().apply { setInput(input, "UTF-8") }
        var event = xpp.eventType
        var currentAlgorithm: String? = null
        var currentPath: String? = null
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    when (xpp.name) {
                        "EncryptedData" -> {
                            currentAlgorithm = null
                            currentPath = null
                        }

                        "EncryptionMethod" -> currentAlgorithm = xpp.getAttributeValue(null, "Algorithm")
                        "CipherReference" -> currentPath = xpp.getAttributeValue(null, "URI")
                    }
                }

                XmlPullParser.END_TAG -> {
                    if (xpp.name == "EncryptedData" && currentAlgorithm != null && currentPath != null) {
                        // Patch 16 (Issue #1): percent-decode the CipherReference URI
                        // BEFORE normalizing so paths like Fonts/My%20Font.otf match
                        // the decoded entry path the WebView requests. (Per advisor.)
                        val decoded = try {
                            Uri.decode(currentPath!!)
                        } catch (_: Exception) {
                            currentPath!!
                        }
                        val norm = EpubPaths.normalize(decoded)
                        map[norm] = currentAlgorithm!!
                    }
                }
            }
            event = xpp.next()
        }
        return map to null
    }

    /** Reads the OPF package unique-identifier to use as the obfuscation key source. */
    private fun readUniqueIdentifier(): String? {
        return try {
            val zipFile = ensureOpen()
            // container.xml -> rootfile full-path -> OPF.
            val containerEntry = zipFile.getEntry("META-INF/container.xml") ?: return null
            var opfPath: String? = null
            zipFile.getInputStream(containerEntry).use { input ->
                val xpp = XmlPullParserFactoryShared.newPullParser().apply { setInput(input, "UTF-8") }
                var event = xpp.eventType
                while (event != XmlPullParser.END_DOCUMENT) {
                    if (event == XmlPullParser.START_TAG && xpp.name == "rootfile") {
                        opfPath = xpp.getAttributeValue(null, "full-path")
                    }
                    event = xpp.next()
                }
            }
            val path = opfPath ?: return null
            val opfEntry = zipFile.getEntry(path) ?: return null
            zipFile.getInputStream(opfEntry).use { input ->
                val xpp = XmlPullParserFactoryShared.newPullParser().apply { setInput(input, "UTF-8") }
                var event = xpp.eventType
                var uniqueIdAttr: String? = null
                var currentId: String? = null
                var currentText = StringBuilder()
                while (event != XmlPullParser.END_DOCUMENT) {
                    when (event) {
                        XmlPullParser.START_TAG -> {
                            if (xpp.name == "package") {
                                uniqueIdAttr = xpp.getAttributeValue(null, "unique-identifier")
                            } else if (xpp.name == "identifier" && xpp.namespace == "http://purl.org/dc/elements/1.1/") {
                                currentId = xpp.getAttributeValue(null, "id")
                                currentText = StringBuilder()
                            }
                        }

                        XmlPullParser.TEXT -> {
                            if (currentId != null) currentText.append(xpp.text)
                        }

                        XmlPullParser.END_TAG -> {
                            if (xpp.name == "identifier" && currentId != null) {
                                if (currentId == uniqueIdAttr) {
                                    return currentText.toString().trim()
                                }
                            }
                        }
                    }
                    event = xpp.next()
                }
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "unique-identifier parse failed: ${e.message}")
            null
        }
    }

    /**
     * Used by ReaderActivity when it builds chapter HTML.
     *
     * This returns an independent in-memory stream, not a stream tied to ZipFile.
     */
    fun resolve(entryPath: String): InputStream? {
        val bytes = resolveBytes(entryPath) ?: return null
        return ByteArrayInputStream(bytes)
    }

    /**
     * Returns an intercepted resource response for virtual EPUB URLs,
     * or null to let WebView handle ordinary URLs normally.
     */
    fun intercept(url: String): WebResourceResponse? {
        val (decoded, raw) = entryPathFor(url) ?: return null
        val bytes = resolveBytes(decoded) ?: resolveBytes(raw) ?: return null

        val mime = mimeTypeFor(decoded)
        // Patch 16 (Issue #1): pass a null encoding for binary resources so the
        // WebView does not apply a UTF-8 charset to font/image bytes.
        val encoding = if (isTextMime(mime)) "UTF-8" else null
        return WebResourceResponse(
            mime,
            encoding,
            ByteArrayInputStream(bytes),
        )
    }

    fun intercept(request: WebResourceRequest): WebResourceResponse? = intercept(request.url.toString())

    /** Converts https://epub.local/<bookId>/<path> into the EPUB ZIP entry path.
     *  Returns (decoded, raw) so the caller can try the decoded path first and
     *  fall back to the raw (still-percent-encoded) path if the ZIP entry name
     *  itself literally contains "%20" — rare, but possible. */
    private fun entryPathFor(url: String): Pair<String, String>? {
        if (!url.contains(VIRTUAL_HOST)) return null

        val afterHost = url.substringAfter(VIRTUAL_HOST)
        val withoutQueryOrFragment =
            afterHost
                .substringBefore('?')
                .substringBefore('#')

        // Decode percent-escapes (e.g. %20 -> space) so font/CSS/image paths with
        // spaces match the real ZIP entry names. Decode AFTER stripping query/fragment
        // so a literal '?'/'#' inside a filename is not mishandled.
        val decoded = try {
            Uri.decode(withoutQueryOrFragment)
        } catch (_: Exception) {
            withoutQueryOrFragment
        }

        fun pathOf(s: String): String? {
            val parts = s.trim('/').split('/', limit = 2)
            return if (parts.size < 2) null else parts[1]
        }

        val decodedPath = pathOf(decoded) ?: return null
        val rawPath = pathOf(withoutQueryOrFragment) ?: decodedPath
        return decodedPath to rawPath
    }

    @Synchronized
    private fun mimeTypeFor(entry: String): String {
        mimeCache[entry]?.let { return it }

        val extension = entry.substringAfterLast('.', "").lowercase()

        val mime =
            when (extension) {
                "xhtml", "html", "htm" -> "application/xhtml+xml"
                "css" -> "text/css"
                "js" -> "application/javascript"
                "png" -> "image/png"
                "jpg", "jpeg" -> "image/jpeg"
                "gif" -> "image/gif"
                "svg" -> "image/svg+xml"
                "webp" -> "image/webp"
                "ttf" -> "font/ttf"
                "otf" -> "font/otf"
                "woff" -> "font/woff"
                "woff2" -> "font/woff2"
                "xml" -> "application/xml"
                "txt" -> "text/plain"
                else -> "application/octet-stream"
            }

        mimeCache[entry] = mime
        return mime
    }

    private fun isTextMime(mime: String): Boolean =
        mime.startsWith("text/") ||
                mime == "application/xhtml+xml" ||
                mime == "application/xml" ||
                mime == "application/javascript"

    @Synchronized
    fun close() {
        try {
            zip?.close()
        } catch (e: Exception) {
            Log.w(TAG, "close failed: ${e.message}")
        } finally {
            zip = null
            mimeCache.clear()
            obfuscationMap = null
            uniqueIdentifier = null
        }
    }

    companion object {
        const val VIRTUAL_HOST = "epub.local"
        private const val TAG = "EpubResourceResolver"

        private const val ALGO_IDPF = "http://www.idpf.org/2008/embedding"
        private const val ALGO_ADOBE = "http://ns.adobe.com/pdf/enc#RC"
        private const val IDPF_HEAD_LEN = 1040
        private const val ADOBE_HEAD_LEN = 1024

        /** Shared, namespace-aware XmlPullParser factory so we don't re-create one
         *  per resolve. */
        private object XmlPullParserFactoryShared {
            private val factory = org.xmlpull.v1.XmlPullParserFactory.newInstance().apply {
                isNamespaceAware = true
            }

            fun newPullParser(): org.xmlpull.v1.XmlPullParser = factory.newPullParser()
        }

        fun baseUrl(
            bookId: Long,
            chapterPath: String,
        ): String = "https://$VIRTUAL_HOST/$bookId/${chapterPath.trimStart('/')}"

        /** Hex-decode a UUID hex string (no urn:uuid: prefix, no hyphens) to bytes. */
        private fun hexToBytes(hex: String): ByteArray {
            val clean = hex.replace(" ", "").replace(":", "")
            val len = clean.length
            require(len % 2 == 0) { "invalid hex length" }
            val out = ByteArray(len / 2)
            var i = 0
            while (i < len) {
                val hi = Character.digit(clean[i], 16)
                val lo = Character.digit(clean[i + 1], 16)
                require(hi >= 0 && lo >= 0) { "invalid hex char" }
                out[i / 2] = ((hi shl 4) or lo).toByte()
                i += 2
            }
            return out
        }
    }
}
