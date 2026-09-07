package com.epubreader.app.epub

object MetadataRefreshReportStore {

    @Volatile
    var latest: EpubImporter.MetadataRefreshResult? = null
}
