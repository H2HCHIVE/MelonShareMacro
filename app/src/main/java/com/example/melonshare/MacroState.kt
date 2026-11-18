package com.example.melonshare

enum class Cat(
    val label: String,
    val listUrl: String,
    val detailUrl: String,
    val patterns: List<Regex>,
    val desktop: Boolean = true,
    val pathMarker: String = ""
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
        ),
        desktop = true,
        pathMarker = "/artist/song.htm"
    ),
    ALBUM(
        "앨범",
        "https://www.melon.com/artist/album.htm?artistId=4096106",
        "https://www.melon.com/album/detail.htm?albumId=",
        listOf(
            Regex("albumId=(\\d{4,})"),
            Regex("goAlbumDetail\\w*\\(\\s*['\"]?(\\d{4,})")
        ),
        desktop = true,
        pathMarker = "/artist/album.htm"
    ),
    MV(
        "영상",
        "https://m2.melon.com/artist/video.htm?artistId=4096106",
        "https://www.melon.com/video/detail2.htm?mvId=",
        listOf(
            Regex("mvId=(\\d{4,})"),
            Regex("goMvDetail\\w*\\(\\s*['\"]?(\\d{4,})"),
            Regex("(?:play|go)(?:Mv|Video)\\w*\\(\\s*['\"]?(\\d{4,})"),
            Regex("[\"']?mvId[\"']?\\s*[:=]\\s*[\"']?(\\d{4,})"),
            Regex("data-mv-?id=[\"'](\\d{4,})[\"']", RegexOption.IGNORE_CASE)
        ),
        desktop = false,
        pathMarker = "/artist/video.htm"
    ),
    PHOTO(
        "포토",
        "https://www.melon.com/artist/photo.htm?artistId=4096106",
        "https://www.melon.com/artist/photo_detail.htm?artistId=4096106&orderBy=NEW&photoId=",
        listOf(
            // goPhotoBtmDetail('아티스트ID','사진ID','NEW') → 두 번째 인자가 photoId
            Regex("goPhotoBtmDetail\\w*\\(\\s*['\"]?\\d+['\"]?\\s*,\\s*['\"]?(\\d{4,})"),
            Regex("goPhotoDetail\\w*\\(\\s*['\"]?\\d+['\"]?\\s*,\\s*['\"]?(\\d{4,})"),
            Regex("photoId=(\\d{4,})"),
            Regex("[\"']?photoId[\"']?\\s*[:=]\\s*[\"']?(\\d{4,})"),
            Regex("data-photo-?(?:id|no)=[\"'](\\d{4,})[\"']", RegexOption.IGNORE_CASE)
        ),
        desktop = true,
        pathMarker = "/artist/photo.htm"
    )
}

object MacroState {
    const val OPEN_CHAT_URL = "https://open.kakao.com/o/g5yc3uXi"

    const val UA_DESKTOP =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    const val UA_MOBILE =
        "Mozilla/5.0 (Linux; Android 13; SM-S918N) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

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