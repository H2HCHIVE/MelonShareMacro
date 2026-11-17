package com.example.melonshare

enum class Cat(
    val label: String,
    val listUrl: String,
    val detailUrl: String,
    val patterns: List<Regex>
) {
    SONG(
        "곡",
        "https://www.melon.com/artist/song.htm?artistId=4096106",
        "https://www.melon.com/song/detail.htm?songId=",
        listOf(
            Regex("songId=(\\d{4,})"),
            Regex("goSongDetail\\w*\\(\\s*['\"]?(\\d{4,})"),
            Regex("playSong\\w*\\(\\s*['\"]?\\d+['\"]?\\s*,\\s*['\"]?(\\d{4,})"),
            Regex("name=[\"']input_check[\"'][^>]*value=[\"'](\\d{4,})[\"']"),
            Regex("value=[\"'](\\d{4,})[\"'][^>]*name=[\"']input_check[\"']")
        )
    ),
    ALBUM(
        "앨범",
        "https://www.melon.com/artist/album.htm?artistId=4096106",
        "https://www.melon.com/album/detail.htm?albumId=",
        listOf(
            Regex("albumId=(\\d{4,})"),
            Regex("goAlbumDetail\\w*\\(\\s*['\"]?(\\d{4,})")
        )
    ),
    MV(
        "영상",
        "https://www.melon.com/artist/video.htm?artistId=4096106",
        "https://www.melon.com/mv/detail.htm?mvId=",
        listOf(
            Regex("mvId=(\\d{4,})"),
            Regex("goMvDetail\\w*\\(\\s*['\"]?(\\d{4,})")
        )
    ),
    PHOTO(
        "포토",
        "https://www.melon.com/artist/photo.htm?artistId=4096106",
        "https://www.melon.com/photo/detail.htm?photoId=",
        listOf(
            Regex("photoId=(\\d{4,})"),
            Regex("goPhotoDetail\\w*\\(\\s*['\"]?(\\d{4,})")
        )
    )
}

object MacroState {
    const val OPEN_CHAT_URL = "https://open.kakao.com/o/g5yc3uXi"

    val collected = mutableMapOf<Cat, LinkedHashSet<String>>()
    @Volatile var queue: List<String> = emptyList()
    @Volatile var running = false
    @Volatile var roomName = ""
    @Volatile var index = 0

    fun buildQueue(): List<String> =
        Cat.values().flatMap { c ->
            (collected[c] ?: emptySet<String>()).map { c.detailUrl + it }
        }
}
