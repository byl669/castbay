package com.weenas.castbay.dlna

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class DlnaRendererTest {
    private class FakeTarget : DlnaRenderer.Target {
        val calls = mutableListOf<String>()
        var status = DlnaRenderer.Status(DlnaState.STOPPED)
        var refuse: String? = null
        override fun authorize(address: String, sender: String, opening: Boolean) {
            calls += "authorize $address $sender $opening"
            refuse?.let { throw Soap.Fault(701, it) }
        }
        override fun open(url: String, media: DlnaMedia) { calls += "open $url ${media.title}" }
        override fun play() { calls += "play" }
        override fun pause() { calls += "pause" }
        override fun stop() { calls += "stop" }
        override fun seek(positionSec: Double) { calls += "seek $positionSec" }
        override fun setVolume(percent: Int) { calls += "volume $percent" }
        override fun setMuted(muted: Boolean) { calls += "mute $muted" }
        override fun status() = status
    }

    private val target = FakeTarget()
    private val renderer = DlnaRenderer(target)

    private fun soap(action: String, args: String = "") = """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body>
<u:$action xmlns:u="urn:schemas-upnp-org:service:AVTransport:1"><InstanceID>0</InstanceID>$args</u:$action>
</s:Body></s:Envelope>"""

    private fun call(action: String, args: String = "", service: UpnpDescriptions.Service = UpnpDescriptions.AV_TRANSPORT) =
        renderer.handle(service, Soap.parse(soap(action, args), null)!!).toMap()

    @Test
    fun parsesActionsWithEscapedMetadata() {
        val action = Soap.parse(
            soap("SetAVTransportURI", "<CurrentURI>http://cdn/v.mp4?a=1&amp;b=2</CurrentURI>" +
                "<CurrentURIMetaData>&lt;DIDL-Lite&gt;&lt;item&gt;&lt;dc:title&gt;晴天 &amp;amp; MV&lt;/dc:title&gt;&lt;/item&gt;&lt;/DIDL-Lite&gt;</CurrentURIMetaData>"),
            "\"urn:schemas-upnp-org:service:AVTransport:1#SetAVTransportURI\""
        )!!
        assertEquals("SetAVTransportURI", action.name)
        assertEquals("http://cdn/v.mp4?a=1&b=2", action.args["CurrentURI"])
        assertEquals("晴天 & MV", DlnaState.title(action.args["CurrentURIMetaData"]))
    }

    @Test
    fun fallsBackToTheSoapActionHeader() {
        assertEquals("Play", Soap.parse("not xml", "\"urn:schemas-upnp-org:service:AVTransport:1#Play\"")?.name)
        assertNull(Soap.parse("not xml", null))
    }

    @Test
    fun drivesTheTargetAndReportsItsState() {
        assertEquals(DlnaState.NO_MEDIA, call("GetTransportInfo")["CurrentTransportState"])
        call("SetAVTransportURI", "<CurrentURI>http://cdn/v.mp4</CurrentURI><CurrentURIMetaData></CurrentURIMetaData>")
        call("Play", "<Speed>1</Speed>")
        call("Seek", "<Unit>REL_TIME</Unit><Target>00:01:30</Target>")
        call("Pause")
        call("SetVolume", "<Channel>Master</Channel><DesiredVolume>35</DesiredVolume>", UpnpDescriptions.RENDERING_CONTROL)
        assertEquals(listOf("open http://cdn/v.mp4 null", "play", "seek 90.0", "pause", "volume 35"), target.calls.filterNot { it.startsWith("authorize") })

        target.status = DlnaRenderer.Status(DlnaState.PAUSED, positionSec = 3725.4, durationSec = 5400.0)
        assertEquals(DlnaState.PAUSED, call("GetTransportInfo")["CurrentTransportState"])
        val position = call("GetPositionInfo")
        assertEquals("1:02:05", position["RelTime"])
        assertEquals("1:30:00", position["TrackDuration"])
        assertEquals("http://cdn/v.mp4", position["TrackURI"])
    }

    @Test
    fun eventsTheTransportStateAndVolume() {
        assertEquals(DlnaState.NO_MEDIA, renderer.eventValues(UpnpDescriptions.AV_TRANSPORT).toMap()["TransportState"])
        call("SetAVTransportURI", "<CurrentURI>http://cdn/v.mp4</CurrentURI>")
        target.status = DlnaRenderer.Status(DlnaState.PLAYING, durationSec = 60.0, volume = 30, muted = true)
        val transport = renderer.eventValues(UpnpDescriptions.AV_TRANSPORT).toMap()
        assertEquals(DlnaState.PLAYING, transport["TransportState"])
        assertEquals("Pause,Stop,Seek", transport["CurrentTransportActions"])
        assertEquals("0:01:00", transport["CurrentTrackDuration"])
        assertEquals(mapOf("Volume" to "30", "Mute" to "1"), renderer.eventValues(UpnpDescriptions.RENDERING_CONTROL).toMap())
        assertTrue(renderer.eventValues(UpnpDescriptions.CONNECTION_MANAGER).isEmpty())
    }

    @Test
    fun keepsTheCurrentMediaWhenTheTargetRefusesANewOne() {
        call("SetAVTransportURI", "<CurrentURI>http://cdn/first.mp4</CurrentURI>")
        val refusing = object : DlnaRenderer.Target by target {
            override fun open(url: String, media: DlnaMedia) = throw Soap.Fault(701, "busy")
        }
        val guarded = DlnaRenderer(refusing)
        val action = Soap.parse(soap("SetAVTransportURI", "<CurrentURI>http://cdn/second.mp4</CurrentURI>"), null)!!
        try {
            guarded.handle(UpnpDescriptions.AV_TRANSPORT, action)
            fail("should be refused")
        } catch (fault: Soap.Fault) {
            assertEquals(701, fault.code)
        }
        assertEquals(DlnaState.NO_MEDIA, guarded.eventValues(UpnpDescriptions.AV_TRANSPORT).toMap()["TransportState"])
    }

    @Test
    fun refusesWhatItCantDo() {
        for ((action, code) in listOf("Play" to 701, "Next" to 701, "Record" to 401)) {
            try {
                call(action)
                fail("$action should fail")
            } catch (fault: Soap.Fault) {
                assertEquals(code, fault.code)
            }
        }
        val response = Soap.fault(Soap.Fault(701, "Transition not available"))
        assertTrue("<errorCode>701</errorCode>" in response)
    }

    @Test
    fun parsesAndFormatsTimes() {
        assertEquals(90.5, DlnaState.parseTime("0:01:30.500")!!, 0.001)
        assertEquals(42.0, DlnaState.parseTime("42")!!, 0.001)
        assertNull(DlnaState.parseTime("NOT_IMPLEMENTED"))
        assertEquals("0:00:00", DlnaState.formatTime(-3.0))
    }

    @Test
    fun theTargetAuthorizesWhatChangesPlaybackNotQueries() {
        val uri = "<CurrentURI>http://cdn/v.mp4</CurrentURI><CurrentURIMetaData></CurrentURIMetaData>"
        renderer.handle(UpnpDescriptions.AV_TRANSPORT, Soap.parse(soap("SetAVTransportURI", uri), null)!!, "Bilibili", "10.0.0.7")
        renderer.handle(UpnpDescriptions.AV_TRANSPORT, Soap.parse(soap("Pause"), null)!!, "Bilibili", "10.0.0.7")
        renderer.handle(UpnpDescriptions.AV_TRANSPORT, Soap.parse(soap("GetPositionInfo"), null)!!, "Bilibili", "10.0.0.8")
        assertEquals(
            listOf("authorize 10.0.0.7 Bilibili true", "open http://cdn/v.mp4 null", "authorize 10.0.0.7 Bilibili false", "pause"),
            target.calls
        )
    }

    @Test
    fun aRefusedDeviceCastsNothing() {
        target.refuse = "Another device is casting to this TV"
        try {
            call("SetAVTransportURI", "<CurrentURI>http://cdn/v.mp4</CurrentURI>")
            fail("expected a fault")
        } catch (fault: Soap.Fault) {
            assertEquals(701, fault.code)
        }
        assertTrue(target.calls.none { it.startsWith("open") })
        // Its URI isn't taken either: Play has nothing to start.
        target.refuse = null
        try {
            call("Play", "<Speed>1</Speed>")
            fail("expected a fault")
        } catch (fault: Soap.Fault) {
            assertEquals(701, fault.code)
        }
    }

    @Test
    fun picturesAreRefusedNotPlayed() {
        try {
            call("SetAVTransportURI", "<CurrentURI>http://pc/IMG_0001.jpg</CurrentURI>")
            fail("expected a fault")
        } catch (fault: Soap.Fault) {
            assertEquals(714, fault.code)
        }
        assertTrue(target.calls.none { it.startsWith("open") })
        val sink = call("GetProtocolInfo", service = UpnpDescriptions.CONNECTION_MANAGER)["Sink"].orEmpty()
        assertTrue("video/mp4" in sink)
        assertTrue("image/" !in sink)
    }
}
