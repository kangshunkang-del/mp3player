package com.car.mp3player.data

/**
 * Turns noisy local filenames into better lyric-search candidates.
 *
 * Examples:
 *   "1周杰伦-稻香" -> "周杰伦-稻香"
 *   "100首华语榜单-周杰伦稻香" -> "周杰伦稻香"
 */
object SongNameParser {
    private val leadingNumber = Regex("""^\s*\d{1,4}\s*[-_.、)]?\s*""")
    private val bracketed = Regex("""[\[【(（].*?[\]】)）]""")
    private val commonNoise = Regex(
        """(?i)(高音质|无损|flac|mp3|wav|aac|精选|热歌|热门歌曲|华语榜单|华语歌曲|中文歌曲|车载音乐|汽车音乐|抖音歌曲|dj串烧|串烧|新歌速递)"""
    )
    private val separators = Regex("""\s*[-—–_]+\s*""")

    fun cleanFilename(raw: String): String {
        var value = raw.substringBeforeLast('.').trim()
        value = value.replace(bracketed, " ")
        value = value.replace(leadingNumber, "")
        value = commonNoise.replace(value, " ")
        value = value.replace(Regex("""\s+"""), " ").trim()
        return value.trim('-', '—', '–', '_', ' ')
    }

    fun candidates(rawTitle: String, rawArtist: String): List<Pair<String, String>> {
        val title = cleanFilename(rawTitle)
        val artist = cleanArtist(rawArtist)
        val result = mutableListOf<Pair<String, String>>()

        if (title.isNotBlank()) result += title to artist

        separators.split(title).filter { it.isNotBlank() }.let { parts ->
            if (parts.size >= 2) {
                val left = parts.first().trim()
                val right = parts.drop(1).joinToString(" ").trim()
                if (left.length <= 20 && right.isNotBlank()) {
                    result += right to left
                    result += left to right
                }
            }
        }

        // Common download names such as "周杰伦稻香": keep the cleaned full
        // string as a title query; lyric providers perform their own fuzzy match.
        if (artist.isBlank()) {
            result += title to ""
        }

        return result.distinct()
    }

    private fun cleanArtist(value: String): String {
        val cleaned = value.trim()
        if (cleaned.isBlank()) return ""
        if (cleaned.equals("未知歌手", true) ||
            cleaned.equals("本地音乐", true) ||
            cleaned.equals("<unknown>", true) ||
            cleaned.equals("unknown", true)
        ) return ""
        return cleanFilename(cleaned)
    }
}
