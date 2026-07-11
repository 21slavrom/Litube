package com.hhst.youtubelite.extractor

/** Parses bare 11-char ids and common YouTube watch / shorts / youtu.be URLs. */
object VideoId {
    private val fromUrl = Regex(
        """(?:v=|=v/|/v/|/u/\w/|embed/|watch\?v=|shorts/|youtu\.be/)([a-zA-Z0-9_-]{11})""",
    )
    private val bare = Regex("""^[a-zA-Z0-9_-]{11}$""")

    /** Returns the 11-char id, or null if [input] cannot be parsed. */
    fun parse(input: String?): String? {
        if (input.isNullOrBlank()) return null
        val trimmed = input.trim()
        if (bare.matches(trimmed)) return trimmed
        return fromUrl.find(trimmed)?.groupValues?.getOrNull(1)
    }

    fun watchUrl(videoId: String): String = "https://www.youtube.com/watch?v=$videoId"
}
