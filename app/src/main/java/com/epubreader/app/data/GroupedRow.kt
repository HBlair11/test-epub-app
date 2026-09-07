package com.epubreader.app.data

/** A row used for the Authors / Series tab: a name + how many books belong to it. */
data class GroupedRow(
    val name: String,
    val count: Int
)
