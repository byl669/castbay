package com.weenas.castbay.dlna

/**
 * What a DLNA sender is casting, from its DIDL-Lite metadata: music apps (NetEase Cloud Music,
 * QQ Music, ...) send the song's title, artist, album and cover, which the music screen shows.
 */
data class DlnaMedia(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    /** An http(s) URL of the cover image. */
    val albumArtUrl: String? = null,
    /** Sound only (a song), shown on the music screen rather than as video. */
    val isAudio: Boolean = false,
    /** A picture (a photo), which CastBay doesn't show. */
    val isImage: Boolean = false,
    /** The resource's MIME type from its protocolInfo, if the sender gave one. */
    val mimeType: String? = null,
    /** The casting app, from [DlnaSender.fromUserAgent]; empty when unknown. */
    val sender: String = ""
) {
    companion object {
        private val AUDIO_EXTENSIONS = setOf("mp3", "m4a", "aac", "flac", "wav", "ogg", "opus", "wma", "ape")
        private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "bmp")

        /**
         * Parses DIDL-Lite loosely (senders' metadata is often not well-formed) and tells audio,
         * video and pictures apart by the item class, the resource's MIME type, or the URL's
         * extension.
         */
        fun parse(metadata: String?, url: String): DlnaMedia {
            val didl = metadata.orEmpty()
            val itemClass = tag(didl, "upnp:class").orEmpty()
            val mime = Regex("""protocolInfo="[^:"]*:[^:"]*:([^:"]+)""").find(didl)?.groupValues?.get(1).orEmpty()
            val extension = url.substringBefore('?').substringAfterLast('/').substringAfterLast('.', "").lowercase()
            val isImage = when {
                "imageItem" in itemClass || mime.startsWith("image/") -> true
                "audioItem" in itemClass || "videoItem" in itemClass || mime.isNotEmpty() -> false
                else -> extension in IMAGE_EXTENSIONS
            }
            val isAudio = when {
                "audioItem" in itemClass -> true
                "videoItem" in itemClass || mime.startsWith("video/") -> false
                mime.startsWith("audio/") -> true
                else -> extension in AUDIO_EXTENSIONS
            }
            return DlnaMedia(
                title = tag(didl, "dc:title"),
                artist = tag(didl, "upnp:artist") ?: tag(didl, "dc:creator"),
                album = tag(didl, "upnp:album"),
                albumArtUrl = tag(didl, "upnp:albumArtURI")?.takeIf { it.startsWith("http") },
                isImage = isImage,
                mimeType = mime.ifEmpty { null },
                isAudio = isAudio
            )
        }

        /** The text of the first <[name]> element, entities decoded, or null if absent or blank. */
        private fun tag(didl: String, name: String): String? {
            val match = Regex("<$name(?:\\s[^>]*)?>(.*?)</$name>", RegexOption.DOT_MATCHES_ALL).find(didl) ?: return null
            return unescape(match.groupValues[1]).trim().takeIf { it.isNotEmpty() }
        }

        private val ENTITY = Regex("&(#[0-9]+|#[xX][0-9a-fA-F]+|lt|gt|quot|apos|amp);")

        /**
         * Decodes XML entities, numeric ones too: NetEase Cloud Music sends its titles as
         * character references ("&#21508;&#33258;..."), escaped once more than the rest of the
         * DIDL, so what is still an entity after one pass is decoded again.
         */
        internal fun unescape(text: String): String {
            var decoded = text
            repeat(2) {
                if ('&' !in decoded) return decoded
                decoded = ENTITY.replace(decoded) { entity ->
                    when (val name = entity.groupValues[1]) {
                        "lt" -> "<"
                        "gt" -> ">"
                        "quot" -> "\""
                        "apos" -> "'"
                        "amp" -> "&"
                        else -> {
                            val code = if (name[1] == 'x' || name[1] == 'X') name.substring(2).toIntOrNull(16)
                            else name.substring(1).toIntOrNull()
                            code?.takeIf { Character.isValidCodePoint(it) }?.let { String(Character.toChars(it)) } ?: entity.value
                        }
                    }
                }
            }
            return decoded
        }
    }
}
