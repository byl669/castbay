package com.weenas.castbay.dlna

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DlnaMediaTest {
    @Test
    fun readsASongFromMusicAppMetadata() {
        val didl = """<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/">
            <item id="1" parentID="0" restricted="1">
              <dc:title>晴天</dc:title>
              <upnp:artist role="Performer">周杰伦</upnp:artist>
              <upnp:album>叶惠美</upnp:album>
              <upnp:albumArtURI>http://p1.music.126.net/cover.jpg?param=500y500&amp;x=1</upnp:albumArtURI>
              <upnp:class>object.item.audioItem.musicTrack</upnp:class>
              <res protocolInfo="http-get:*:audio/mpeg:*" duration="00:04:29">http://m.music.126.net/a.mp3</res>
            </item></DIDL-Lite>"""
        val media = DlnaMedia.parse(didl, "http://m.music.126.net/a.mp3")
        assertEquals("晴天", media.title)
        assertEquals("周杰伦", media.artist)
        assertEquals("叶惠美", media.album)
        assertEquals("http://p1.music.126.net/cover.jpg?param=500y500&x=1", media.albumArtUrl)
        assertTrue(media.isAudio)
    }

    @Test
    fun tellsVideoFromAudio() {
        val video = """<item><dc:title>Movie</dc:title><upnp:class>object.item.videoItem</upnp:class></item>"""
        assertFalse(DlnaMedia.parse(video, "http://cdn/v.mp4").isAudio)
        // No class: the resource's MIME type decides.
        assertTrue(DlnaMedia.parse("""<res protocolInfo="http-get:*:audio/flac:*">x</res>""", "http://cdn/x").isAudio)
        assertFalse(DlnaMedia.parse("""<res protocolInfo="http-get:*:video/mp4:*">x</res>""", "http://cdn/a.mp3").isAudio)
    }

    @Test
    fun fallsBackToTheUrlWithoutMetadata() {
        assertTrue(DlnaMedia.parse(null, "http://cdn/song.FLAC?token=1").isAudio)
        assertFalse(DlnaMedia.parse("", "http://cdn/42166259485-1-192.mp4").isAudio)
        val bare = DlnaMedia.parse(null, "http://cdn/song.mp3")
        assertNull(bare.title)
        assertNull(bare.albumArtUrl)
    }

    @Test
    fun usesTheCreatorWhenThereIsNoArtist() {
        assertEquals("Someone", DlnaMedia.parse("<dc:title>T</dc:title><dc:creator>Someone</dc:creator>", "u").artist)
    }

    @Test
    fun decodesCharacterReferences() {
        // NetEase Cloud Music: the title as character references, escaped once more.
        val didl = "<DIDL-Lite><item><dc:title>&amp;#21508;&amp;#33258;&amp;#23433;&amp;#22909;</dc:title>" +
            "<upnp:artist>&#x5218;&#x82E5;&#x82F1;</upnp:artist><upnp:class>object.item.audioItem</upnp:class></item></DIDL-Lite>"
        val media = DlnaMedia.parse(didl, "http://m701.music.126.net/a.m4a")
        assertEquals("各自安好", media.title)
        assertEquals("刘若英", media.artist)
        assertEquals("Tom & Jerry", DlnaMedia.unescape("Tom &amp; Jerry"))
        assertEquals("&#xZZ; stays", DlnaMedia.unescape("&#xZZ; stays"))
    }

    @Test
    fun tellsPicturesApart() {
        assertTrue(DlnaMedia.parse("""<upnp:class>object.item.imageItem.photo</upnp:class>""", "http://pc/1").isImage)
        assertTrue(DlnaMedia.parse("""<res protocolInfo="http-get:*:image/jpeg:*">x</res>""", "http://pc/1").isImage)
        assertTrue(DlnaMedia.parse(null, "http://pc/IMG_0001.JPG").isImage)
        assertFalse(DlnaMedia.parse(null, "http://cdn/v.mp4").isImage)
        // The MIME type wins over a misleading extension.
        assertFalse(DlnaMedia.parse("""<res protocolInfo="http-get:*:video/mp4:*">x</res>""", "http://cdn/cover.jpg").isImage)
    }

    @Test
    fun keepsTheSendersMimeType() {
        assertEquals("application/x-mpegURL", DlnaMedia.parse("""<res protocolInfo="http-get:*:application/x-mpegURL:*">x</res>""", "http://cdn/play?id=1").mimeType)
        assertNull(DlnaMedia.parse(null, "http://cdn/v.mp4").mimeType)
    }
}
