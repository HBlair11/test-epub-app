package com.epubreader.app.epub

import java.net.URI
import java.util.Locale

/** Path normalization helpers for resolving EPUB-internal hrefs to zip entry names. */
object EpubPaths {

    /** Resolve a (possibly relative) href against a base directory, both as zip-entry paths. */
    fun resolve(baseDir: String, href: String): String {
        if (href.startsWith("data:")) return href
        // Strip fragment.
        val frag = href.indexOf('#')
        val path = if (frag >= 0) href.substring(0, frag) else href
        // Absolute URLs that aren't our virtual scheme are left as-is (external).
        if (path.startsWith("http://") || path.startsWith("https://") || path.startsWith("mailto:")) {
            return href
        }
        val base = baseDir.trimEnd('/')
        val resolved = if (path.startsWith("/")) {
            path.removePrefix("/")
        } else if (base.isEmpty()) {
            path
        } else {
            try {
                URI(base + "/").resolve(path).path
            } catch (_: Throwable) {
                if (base.isEmpty()) path else "$base/$path"
            }
        }
        return normalize(resolved)
    }

    fun normalize(path: String): String {
        // Collapse ./ and ../ segments.
        val parts = path.split("/").toMutableList()
        val out = ArrayDeque<String>()
        for (p in parts) {
            when {
                p.isEmpty() || p == "." -> { /* skip */
                }

                p == ".." -> {
                    if (out.isNotEmpty()) out.removeLast()
                }

                else -> out.addLast(p)
            }
        }
        return out.joinToString("/")
    }

    fun parentDir(path: String): String {
        val n = normalize(path)
        val idx = n.lastIndexOf('/')
        return if (idx < 0) "" else n.substring(0, idx)
    }
}
