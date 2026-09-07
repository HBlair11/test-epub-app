package com.epubreader.app.epub

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.util.zip.ZipFile

class EpubParser {

    private val factory: XmlPullParserFactory =
        XmlPullParserFactory.newInstance().apply {
            isNamespaceAware = true
        }

    private fun newParser(): XmlPullParser =
        factory.newPullParser()

    fun parse(file: File): EpubBook {
        ZipFile(file).use { zip ->
            val opfPath = findOpfPath(zip)
            checkNotNull(opfPath) {
                "No OPF root file found in container.xml"
            }

            val opfDir = EpubPaths.parentDir(opfPath)

            val opfEntry =
                zip.getEntry(opfPath)
                    ?: throw IllegalStateException(
                        "OPF entry not found: $opfPath"
                    )

            val manifest =
                mutableMapOf<String, ManifestItem>()

            val spineOrder =
                mutableListOf<Pair<String, Boolean>>()

            var tocId: String? = null
            var coverMetaId: String? = null

            val metadata = EpubMetadata()

            // =============================================================
            // SERIES METADATA CANDIDATES
            // =============================================================
            //
            // Priority:
            //
            //   1. Calibre
            //      calibre:series
            //      calibre:series_index
            //
            //   2. Legacy
            //      series
            //      series_index
            //
            //   3. EPUB 3
            //      belongs-to-collection
            //      collection-type = series
            //      group-position
            //
            // EPUB 3 metadata is collected first and resolved only after
            // the complete OPF has been read because its three <meta>
            // elements may appear in any order.
            // =============================================================

            var calibreSeries: String? = null
            var calibreSeriesIndex: Double? = null

            var legacySeries: String? = null
            var legacySeriesIndex: Double? = null

            val epub3Collections =
                linkedMapOf<String, String>()

            val epub3SeriesCollectionIds =
                linkedSetOf<String>()

            val epub3CollectionPositions =
                mutableMapOf<String, Double>()

            // =============================================================
            // PARSE OPF
            // =============================================================

            zip.getInputStream(opfEntry).use { input ->

                val xpp =
                    newParser().apply {
                        setInput(input, "UTF-8")
                    }

                var event = xpp.eventType

                while (event != XmlPullParser.END_DOCUMENT) {

                    when (event) {

                        XmlPullParser.START_TAG -> {

                            when (xpp.name) {

                                // =================================================
                                // METADATA
                                // =================================================

                                "meta" -> {

                                    val name =
                                        xpp.getAttributeValue(
                                            null,
                                            "name"
                                        )

                                    val content =
                                        xpp.getAttributeValue(
                                            null,
                                            "content"
                                        )

                                    val property =
                                        xpp.getAttributeValue(
                                            null,
                                            "property"
                                        )

                                    val refines =
                                        xpp.getAttributeValue(
                                            null,
                                            "refines"
                                        )

                                    val id =
                                        xpp.getAttributeValue(
                                            null,
                                            "id"
                                        )

                                    // EPUB 3 property metadata stores its value
                                    // as element text:
                                    //
                                    // <meta property="belongs-to-collection"
                                    //       id="id-2">A Vine Mess</meta>
                                    //
                                    // Legacy / Calibre metadata normally stores
                                    // the value in content=.
                                    //
                                    // collectText() safely handles both normal
                                    // and self-closing <meta> elements.
                                    val elementText =
                                        collectText(xpp)

                                    val value =
                                        elementText.ifBlank {
                                            content?.trim().orEmpty()
                                        }

                                    // =================================================
                                    // LEGACY / CALIBRE METADATA
                                    // =================================================

                                    if (
                                        !name.isNullOrBlank() &&
                                        !content.isNullOrBlank()
                                    ) {

                                        when (name.trim().lowercase()) {

                                            "cover" -> {
                                                coverMetaId =
                                                    content.trim()
                                            }

                                            // -----------------------------
                                            // CALIBRE
                                            // -----------------------------

                                            "calibre:series" -> {
                                                if (content.isNotBlank()) {
                                                    calibreSeries =
                                                        content.trim()
                                                }
                                            }

                                            "calibre:series_index" -> {
                                                calibreSeriesIndex =
                                                    content.trim()
                                                        .toDoubleOrNull()
                                            }

                                            // -----------------------------
                                            // LEGACY
                                            // -----------------------------

                                            "series" -> {
                                                if (content.isNotBlank()) {
                                                    legacySeries =
                                                        content.trim()
                                                }
                                            }

                                            "series_index" -> {
                                                legacySeriesIndex =
                                                    content.trim()
                                                        .toDoubleOrNull()
                                            }

                                            // -----------------------------
                                            // OTHER EXISTING COMPATIBILITY
                                            // -----------------------------

                                            "calibre:title_sort",
                                            "title_sort",
                                            "sortas" -> {
                                                // Kept for compatibility.
                                                // The app currently derives
                                                // sort_title from the parsed
                                                // title.
                                            }

                                            // Some hybrid/older EPUBs put
                                            // these values in name/content
                                            // rather than proper EPUB 3
                                            // property/text form.
                                            //
                                            // They are treated as LEGACY
                                            // fallback values, never as
                                            // proper EPUB 3 metadata.

                                            "belongs-to-collection" -> {
                                                if (
                                                    legacySeries == null &&
                                                    content.isNotBlank()
                                                ) {
                                                    legacySeries =
                                                        content.trim()
                                                }
                                            }

                                            "group-position" -> {
                                                if (
                                                    legacySeriesIndex == null
                                                ) {
                                                    legacySeriesIndex =
                                                        content.trim()
                                                            .toDoubleOrNull()
                                                }
                                            }
                                        }
                                    }

                                    // =================================================
                                    // EPUB 3 COLLECTION METADATA
                                    // =================================================

                                    if (!property.isNullOrBlank()) {

                                        when (property.trim().lowercase()) {

                                            // -------------------------------------------------
                                            // <meta property="belongs-to-collection"
                                            //       id="id-2">A Vine Mess</meta>
                                            // -------------------------------------------------

                                            "belongs-to-collection" -> {

                                                if (
                                                    !id.isNullOrBlank() &&
                                                    value.isNotBlank()
                                                ) {
                                                    epub3Collections[
                                                        id.trim()
                                                    ] = value
                                                }
                                            }

                                            // -------------------------------------------------
                                            // <meta refines="#id-2"
                                            //       property="collection-type">series</meta>
                                            // -------------------------------------------------

                                            "collection-type" -> {

                                                val collectionId =
                                                    refines
                                                        ?.trim()
                                                        ?.removePrefix("#")
                                                        ?.trim()

                                                if (
                                                    !collectionId.isNullOrBlank() &&
                                                    value.equals(
                                                        "series",
                                                        ignoreCase = true
                                                    )
                                                ) {
                                                    epub3SeriesCollectionIds +=
                                                        collectionId
                                                }
                                            }

                                            // -------------------------------------------------
                                            // <meta refines="#id-2"
                                            //       property="group-position">1</meta>
                                            // -------------------------------------------------

                                            "group-position" -> {

                                                val collectionId =
                                                    refines
                                                        ?.trim()
                                                        ?.removePrefix("#")
                                                        ?.trim()

                                                val position =
                                                    value.toDoubleOrNull()

                                                if (
                                                    !collectionId.isNullOrBlank() &&
                                                    position != null
                                                ) {
                                                    epub3CollectionPositions[
                                                        collectionId
                                                    ] = position
                                                }
                                            }
                                        }
                                    }
                                }

                                // =================================================
                                // STANDARD DC METADATA
                                // =================================================

                                "title" -> {
                                    if (metadata.title.isBlank()) {
                                        metadata.title =
                                            xpp.nextText().trim()
                                    }
                                }

                                "creator" -> {
                                    val text =
                                        xpp.nextText().trim()

                                    if (text.isNotBlank()) {
                                        metadata.authors.add(text)
                                    }
                                }

                                "publisher" -> {
                                    metadata.publisher =
                                        xpp.nextText()
                                            .trim()
                                            .ifBlank { null }
                                }

                                "description" -> {
                                    metadata.description =
                                        xpp.nextText()
                                            .trim()
                                            .ifBlank { null }
                                }

                                "language" -> {
                                    metadata.language =
                                        xpp.nextText()
                                            .trim()
                                            .ifBlank { null }
                                }

                                "date" -> {
                                    if (metadata.publishDate == null) {
                                        metadata.publishDate =
                                            xpp.nextText()
                                                .trim()
                                                .ifBlank { null }
                                    }
                                }

                                "identifier" -> {
                                    val text =
                                        xpp.nextText().trim()

                                    if (text.isNotBlank()) {
                                        metadata.identifiers.add(text)
                                    }
                                }

                                // =================================================
                                // MANIFEST
                                // =================================================

                                "item" -> {

                                    val id =
                                        xpp.getAttributeValue(
                                            null,
                                            "id"
                                        ) ?: ""

                                    val href =
                                        xpp.getAttributeValue(
                                            null,
                                            "href"
                                        ) ?: ""

                                    val mediaType =
                                        xpp.getAttributeValue(
                                            null,
                                            "media-type"
                                        )
                                            ?: "application/octet-stream"

                                    val props =
                                        xpp.getAttributeValue(
                                            null,
                                            "properties"
                                        )

                                    val resolved =
                                        EpubPaths.resolve(
                                            opfDir,
                                            href
                                        )

                                    manifest[id] =
                                        ManifestItem(
                                            id,
                                            resolved,
                                            mediaType,
                                            props
                                        )

                                    if (
                                        props?.contains("nav") == true
                                    ) {
                                        tocId = id
                                    }

                                    if (
                                        props?.contains("cover-image") == true
                                    ) {
                                        coverMetaId = id
                                    }
                                }

                                // =================================================
                                // SPINE
                                // =================================================

                                "itemref" -> {

                                    val idref =
                                        xpp.getAttributeValue(
                                            null,
                                            "idref"
                                        ) ?: ""

                                    val linear =
                                        xpp.getAttributeValue(
                                            null,
                                            "linear"
                                        )
                                            ?.lowercase()
                                            ?: "yes"

                                    spineOrder.add(
                                        idref to (linear != "no")
                                    )
                                }

                                "spine" -> {

                                    val tocAttr =
                                        xpp.getAttributeValue(
                                            null,
                                            "toc"
                                        )

                                    if (
                                        tocAttr != null &&
                                        tocId == null
                                    ) {
                                        tocId = tocAttr
                                    }
                                }

                                // =================================================
                                // EPUB 2 COVER
                                // =================================================

                                "reference" -> {

                                    val type =
                                        xpp.getAttributeValue(
                                            null,
                                            "type"
                                        )?.lowercase()

                                    if (
                                        type == "cover" &&
                                        coverMetaId == null
                                    ) {

                                        val href =
                                            xpp.getAttributeValue(
                                                null,
                                                "href"
                                            )

                                        if (href != null) {
                                            coverMetaId =
                                                "__href__:${
                                                    EpubPaths.resolve(
                                                        opfDir,
                                                        href
                                                    )
                                                }"
                                        }
                                    }
                                }
                            }
                        }
                    }

                    event = xpp.next()
                }
            }

            // =============================================================
            // FINALIZE SERIES METADATA
            // =============================================================

            // EPUB 3:
            //
            // belongs-to-collection id="id-2"
            // collection-type refines="#id-2" = series
            // group-position refines="#id-2" = 1
            //
            // Only the collection explicitly marked as "series" is used.
            val epub3SeriesId =
                epub3SeriesCollectionIds.firstOrNull()

            val epub3Series =
                epub3SeriesId?.let {
                    epub3Collections[it]
                }

            val epub3SeriesIndex =
                epub3SeriesId?.let {
                    epub3CollectionPositions[it]
                }

            // FINAL PRIORITY:
            //
            // Calibre > Legacy > EPUB 3
            //
            // Applied independently to series and index so that a missing
            // Calibre index can still fall back to a lower-priority index.
            metadata.series =
                calibreSeries
                    ?: legacySeries
                            ?: epub3Series

            metadata.seriesIndex =
                calibreSeriesIndex
                    ?: legacySeriesIndex
                            ?: epub3SeriesIndex

            // =============================================================
            // BUILD SPINE
            // =============================================================

            val spine =
                spineOrder.mapNotNull { (idref, linear) ->

                    val item =
                        manifest[idref]
                            ?: return@mapNotNull null

                    SpineItem(
                        idref,
                        item.href,
                        item.mediaType,
                        linear
                    )
                }

            // =============================================================
            // COVER
            // =============================================================

            val coverHref =
                determineCoverHref(
                    coverMetaId,
                    manifest,
                    opfDir,
                    zip
                )

            // =============================================================
            // TOC
            // =============================================================

            val toc =
                parseToc(
                    tocId,
                    manifest,
                    opfDir,
                    zip
                )

            return EpubBook(
                file,
                metadata,
                manifest,
                spine,
                toc,
                coverHref,
                opfDir
            )
        }
    }

    private fun findOpfPath(zip: ZipFile): String? {

        val entry =
            zip.getEntry(
                "META-INF/container.xml"
            )
                ?: return null

        zip.getInputStream(entry).use { input ->

            val xpp =
                newParser().apply {
                    setInput(input, "UTF-8")
                }

            var event = xpp.eventType

            while (event != XmlPullParser.END_DOCUMENT) {

                if (
                    event == XmlPullParser.START_TAG &&
                    xpp.name == "rootfile"
                ) {

                    val path =
                        xpp.getAttributeValue(
                            null,
                            "full-path"
                        )

                    if (path != null) {
                        return path
                    }
                }

                event = xpp.next()
            }
        }

        return null
    }

    private fun determineCoverHref(
        coverMetaId: String?,
        manifest: Map<String, ManifestItem>,
        opfDir: String,
        zip: ZipFile
    ): String? {

        if (coverMetaId != null) {

            if (
                coverMetaId.startsWith(
                    "__href__:"
                )
            ) {
                return coverMetaId.removePrefix(
                    "__href__:"
                )
            }

            manifest[coverMetaId]?.let {
                return it.href
            }
        }

        // Fallback: first image that looks like a cover.
        val imageItem =
            manifest.values.firstOrNull {
                it.mediaType.startsWith("image/") &&
                        it.href.contains(
                            "cover",
                            ignoreCase = true
                        )
            }
                ?: manifest.values.firstOrNull {
                    it.mediaType.startsWith("image/")
                }

        return imageItem?.href
    }

    private fun parseToc(
        tocId: String?,
        manifest: Map<String, ManifestItem>,
        opfDir: String,
        zip: ZipFile
    ): List<TocEntry> {

        // EPUB 3 nav document.
        if (tocId != null) {

            val navItem =
                manifest[tocId]

            if (
                navItem != null &&
                (
                        navItem.mediaType.contains("xhtml") ||
                                navItem.mediaType.contains("html")
                        )
            ) {

                val entries =
                    parseNavToc(
                        navItem.href,
                        zip
                    )

                if (entries.isNotEmpty()) {
                    return entries
                }
            }
        }

        // EPUB 2 NCX.
        val ncxItem =
            manifest.values.firstOrNull {
                it.mediaType.contains("ncx")
            }
                ?: manifest[tocId]

        if (ncxItem != null) {

            val entries =
                parseNcxToc(
                    ncxItem.href,
                    zip
                )

            if (entries.isNotEmpty()) {
                return entries
            }
        }

        return emptyList()
    }

    private fun parseNavToc(
        navHref: String,
        zip: ZipFile
    ): List<TocEntry> {

        val entry =
            zip.getEntry(navHref)
                ?: return emptyList()

        val entries =
            mutableListOf<TocEntry>()

        val baseDir =
            EpubPaths.parentDir(navHref)

        zip.getInputStream(entry).use { input ->

            val xpp =
                newParser().apply {
                    setInput(input, "UTF-8")
                }

            var event = xpp.eventType
            var level = 0
            var inTocNav = false

            while (event != XmlPullParser.END_DOCUMENT) {

                when (event) {

                    XmlPullParser.START_TAG -> {

                        when (xpp.name) {

                            "nav" -> {

                                val type =
                                    xpp.getAttributeValue(
                                        null,
                                        "type"
                                    ) ?: ""

                                if (
                                    type == "toc" ||
                                    !inTocNav
                                ) {
                                    inTocNav = true
                                }
                            }

                            "ol" -> {
                                if (inTocNav) {
                                    level++
                                }
                            }

                            "li" -> {
                                // Placeholder for nesting.
                            }

                            "a" -> {

                                if (inTocNav) {

                                    val href =
                                        xpp.getAttributeValue(
                                            null,
                                            "href"
                                        )

                                    val label =
                                        collectText(xpp)

                                    if (
                                        href != null &&
                                        label.isNotBlank()
                                    ) {

                                        entries.add(
                                            TocEntry(
                                                label.trim(),
                                                resolveWithFragment(
                                                    baseDir,
                                                    href
                                                ),
                                                level - 1
                                            )
                                        )
                                    }
                                }
                            }
                        }
                    }

                    XmlPullParser.END_TAG -> {

                        if (
                            xpp.name == "ol" &&
                            inTocNav
                        ) {
                            level =
                                (
                                        level - 1
                                        ).coerceAtLeast(0)
                        }

                        if (
                            xpp.name == "nav"
                        ) {
                            inTocNav = false
                        }
                    }
                }

                event = xpp.next()
            }
        }

        return entries
    }

    private fun parseNcxToc(
        ncxHref: String,
        zip: ZipFile
    ): List<TocEntry> {

        val entry =
            zip.getEntry(ncxHref)
                ?: return emptyList()

        val entries =
            mutableListOf<TocEntry>()

        val baseDir =
            EpubPaths.parentDir(ncxHref)

        zip.getInputStream(entry).use { input ->

            val xpp =
                newParser().apply {
                    setInput(input, "UTF-8")
                }

            var event = xpp.eventType
            var level = 0
            var label = ""
            var href: String? = null

            while (event != XmlPullParser.END_DOCUMENT) {

                when (event) {

                    XmlPullParser.START_TAG -> {

                        when (xpp.name) {

                            "navPoint" -> {
                                level++
                            }

                            "text" -> {
                                label =
                                    collectText(xpp)
                            }

                            "content" -> {
                                href =
                                    xpp.getAttributeValue(
                                        null,
                                        "src"
                                    )
                            }
                        }
                    }

                    XmlPullParser.END_TAG -> {

                        if (
                            xpp.name == "navPoint"
                        ) {

                            if (
                                label.isNotBlank() &&
                                href != null
                            ) {

                                entries.add(
                                    TocEntry(
                                        label.trim(),
                                        resolveWithFragment(
                                            baseDir,
                                            href
                                        ),
                                        level - 1
                                    )
                                )
                            }

                            label = ""
                            href = null

                            level =
                                (
                                        level - 1
                                        ).coerceAtLeast(0)
                        }
                    }
                }

                event = xpp.next()
            }
        }

        return entries
    }

    private fun resolveWithFragment(
        baseDir: String,
        href: String
    ): String {

        val frag =
            if ('#' in href) {
                href.substringAfter('#')
            } else {
                null
            }

        val path =
            EpubPaths.resolve(
                baseDir,
                href
            )

        return if (frag != null) {
            "$path#$frag"
        } else {
            path
        }
    }

    /**
     * Collect text content of the current XML element.
     *
     * Called while positioned on START_TAG. When it returns, the parser is
     * positioned on that element's END_TAG.
     */
    private fun collectText(
        xpp: XmlPullParser
    ): String {

        val sb =
            StringBuilder()

        val depth =
            xpp.depth

        var event =
            xpp.next()

        while (
            !(
                    event == XmlPullParser.END_TAG &&
                            xpp.depth == depth
                    )
        ) {

            if (
                event == XmlPullParser.TEXT
            ) {
                sb.append(xpp.text)
            }

            if (
                event == XmlPullParser.END_DOCUMENT
            ) {
                break
            }

            event = xpp.next()
        }

        return sb.toString().trim()
    }
}
