package com.epubreader.app.epub

/** A single manifest item from the OPF. */
data class ManifestItem(
    val id: String,
    val href: String,        // resolved, zip-entry path
    val mediaType: String,
    val properties: String?
)

/** A spine entry: an ordered reading item. */
data class SpineItem(
    val idref: String,
    val href: String,        // resolved, zip-entry path
    val mediaType: String,
    val linear: Boolean = true
)

data class TocEntry(
    val label: String,
    val href: String,        // may contain a fragment (#id)
    val level: Int = 0
)

data class EpubMetadata(
    var title: String = "",
    val authors: MutableList<String> = mutableListOf(),
    var language: String? = null,
    var publisher: String? = null,
    var description: String? = null,
    val identifiers: MutableList<String> = mutableListOf(),
    val subjects: MutableList<String> = mutableListOf(),
    var series: String? = null,
    var seriesIndex: Double? = null,
    var publishDate: String? = null
) {
    val authorString: String get() = authors.joinToString(", ").ifBlank { "Unknown Author" }
}

data class EpubBook(
    val file: java.io.File,
    val metadata: EpubMetadata,
    val manifest: Map<String, ManifestItem>,
    val spine: List<SpineItem>,
    val toc: List<TocEntry>,
    val coverHref: String?,
    val opfDir: String
) {
    /** Resolve an href that may be relative to a chapter into an absolute zip-entry path. */
    fun resolveHref(fromHref: String, baseDir: String = opfDir): String {
        return EpubPaths.resolve(baseDir, fromHref)
    }

    fun spineIndexForHref(href: String): Int {
        val target = normalize(href)
        return spine.indexOfFirst { normalize(it.href) == target }.let { if (it < 0) -1 else it }
    }

    private fun normalize(href: String): String {
        var h = href
        val frag = h.indexOf('#')
        if (frag >= 0) h = h.substring(0, frag)
        return h.trimEnd('/')
    }
}
