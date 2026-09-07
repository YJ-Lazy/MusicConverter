package com.musicconverter.miui.player

data class Track(
    val id: String,
    val title: String,
    val artist: String,
    val album: String = "",
    val artwork: String? = null,
    val streamUrl: String,
    val source: String = "local"
)
