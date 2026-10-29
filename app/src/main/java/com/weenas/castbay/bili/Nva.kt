package com.weenas.castbay.bili

import com.weenas.castbay.dlna.Ssdp
import com.weenas.castbay.dlna.UpnpDescriptions
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Bilibili's casting protocol, "哔哩必连" (internally NVA).
 *
 * The phone's TV button only offers 720p to a plain DLNA renderer: that path can carry a single
 * URL and nothing else, so Bilibili hands over its muxed `durl` stream, which is capped there.
 * A receiver that also advertises `NirvanaControl` is treated as one of Bilibili's own boxes
 * ("我的小电视"), and the phone then plays along a richer path: it sends the account's
 * `accessKey`, so the receiver fetches the streams itself — DASH, up to 1080p — and reports
 * progress, quality, speed and danmaku back.
 *
 * This file holds what is wire-format only: the advertised identities, the description XML and
 * the binary framing. Protocol notes: the framing and the device fields follow the MIT-licensed
 * Macast nirvana plugin (github.com/xfangfang/Macast-plugins) and the Kodi receiver built on it.
 */
object Nva {
    /** The service type the phone searches for. Its presence is what makes this an NVA device. */
    const val SERVICE_TYPE = "urn:app-bilibili-com:service:NirvanaControl:3"
    const val SERVICE_ID = "urn:app-bilibili-com:serviceId:NirvanaControl"
    const val SERVICE_NAME = "NirvanaControl"

    /** Preferred control port: the official "云视听小电视" app also uses it, so only one can run. */
    const val PORT = 9958

    const val DESCRIPTION_PATH = "/bilibili/description.xml"
    const val SERVER = "Linux/3.0.0, UPnP/1.0, Platinum/1.0.5.13"

    /**
     * Fields the phone reads to decide what this device can do. `ottVersion` and `capability`
     * are what a stock box reports; they are advertised as observed on receivers known to work.
     */
    private const val HOST_VERSION = "25"
    private const val OTT_VERSION = "105500"
    private const val CHANNEL_NAME = "master"
    private const val CAPABILITY = "255"

    /** Frame types on the control connection. */
    const val SEND = 0xE0
    const val RET = 0xC0
    const val PING = 0xE4

    /** The (notification type, unique service name) pairs announced over SSDP. */
    fun targets(uuid: String): List<Pair<String, String>> {
        val device = "uuid:$uuid"
        return listOf(
            Ssdp.ROOT_DEVICE to "$device::${Ssdp.ROOT_DEVICE}",
            device to device,
            Ssdp.MEDIA_RENDERER to "$device::${Ssdp.MEDIA_RENDERER}",
            Ssdp.AV_TRANSPORT to "$device::${Ssdp.AV_TRANSPORT}",
            Ssdp.RENDERING_CONTROL to "$device::${Ssdp.RENDERING_CONTROL}",
            Ssdp.CONNECTION_MANAGER to "$device::${Ssdp.CONNECTION_MANAGER}",
            SERVICE_TYPE to "$device::$SERVICE_TYPE"
        )
    }

    /** Our SCPD and control paths; the DLNA services keep the ones the renderer already serves. */
    val NIRVANA_SCPD = "/$SERVICE_NAME/scpd.xml"
    val NIRVANA_CONTROL = "/$SERVICE_NAME/control"
    val NIRVANA_EVENT = "/$SERVICE_NAME/event"

    fun device(friendlyName: String, uuid: String): String {
        val services = (UpnpDescriptions.SERVICES.map { service ->
            """      <service>
        <serviceType>${service.type}</serviceType>
        <serviceId>${service.id}</serviceId>
        <SCPDURL>${service.scpdPath}</SCPDURL>
        <controlURL>${service.controlPath}</controlURL>
        <eventSubURL>${service.eventPath}</eventSubURL>
      </service>"""
        } + """      <service>
        <serviceType>$SERVICE_TYPE</serviceType>
        <serviceId>$SERVICE_ID</serviceId>
        <SCPDURL>$NIRVANA_SCPD</SCPDURL>
        <controlURL>$NIRVANA_CONTROL</controlURL>
        <eventSubURL>$NIRVANA_EVENT</eventSubURL>
      </service>""").joinToString("\n")
        return """<?xml version="1.0" encoding="UTF-8"?>
<root xmlns="urn:schemas-upnp-org:device-1-0" xmlns:dlna="urn:schemas-dlna-org:device-1-0">
  <specVersion><major>1</major><minor>0</minor></specVersion>
  <device>
    <deviceType>${Ssdp.MEDIA_RENDERER}</deviceType>
    <friendlyName>${com.weenas.castbay.dlna.Soap.escape(friendlyName)}</friendlyName>
    <manufacturer>CastBay</manufacturer>
    <manufacturerURL>https://castbay.weenas.com</manufacturerURL>
    <modelDescription>CastBay</modelDescription>
    <modelName>CastBay</modelName>
    <modelNumber>1</modelNumber>
    <serialNumber>1</serialNumber>
    <UDN>uuid:$uuid</UDN>
    <X_brandName>CastBay</X_brandName>
    <hostVersion>$HOST_VERSION</hostVersion>
    <ottVersion>$OTT_VERSION</ottVersion>
    <channelName>$CHANNEL_NAME</channelName>
    <capability>$CAPABILITY</capability>
    <dlna:X_DLNADOC>DMR-1.50</dlna:X_DLNADOC>
    <serviceList>
$services
    </serviceList>
  </device>
</root>
"""
    }

    /** The NirvanaControl service description: the actions the phone may call. */
    val SCPD = """<?xml version="1.0" encoding="UTF-8"?>
<scpd xmlns="urn:schemas-upnp-org:service-1-0">
  <specVersion><major>1</major><minor>0</minor></specVersion>
  <actionList>
    <action><name>GetAppInfo</name><argumentList>
      <argument><name>PackageName</name><direction>out</direction><relatedStateVariable>A_ARG_TYPE_PackageName</relatedStateVariable></argument>
      <argument><name>AppKey</name><direction>out</direction><relatedStateVariable>A_ARG_TYPE_AppKey</relatedStateVariable></argument>
      <argument><name>Signature</name><direction>out</direction><relatedStateVariable>A_ARG_TYPE_Signature</relatedStateVariable></argument>
      <argument><name>CurrentSignedIn</name><direction>out</direction><relatedStateVariable>SignedIn</relatedStateVariable></argument>
    </argumentList></action>
    <action><name>LoginWithCode</name><argumentList>
      <argument><name>Code</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_UnlimitedString</relatedStateVariable></argument>
    </argumentList></action>
    <action><name>PrepareForMirrorProjection</name><argumentList>
      <argument><name>ScreenWidth</name><direction>out</direction><relatedStateVariable>A_ARG_TYPE_ScreenResolution</relatedStateVariable></argument>
      <argument><name>ScreenHeight</name><direction>out</direction><relatedStateVariable>A_ARG_TYPE_ScreenResolution</relatedStateVariable></argument>
      <argument><name>PushUrl</name><direction>out</direction><relatedStateVariable>A_ARG_TYPE_UnlimitedString</relatedStateVariable></argument>
    </argumentList></action>
    <action><name>SetDanmakuSwitch</name><argumentList>
      <argument><name>DesiredSwitch</name><direction>in</direction><relatedStateVariable>DanmakuSwitch</relatedStateVariable></argument>
    </argumentList></action>
    <action><name>AppendDanmaku</name><argumentList>
      <argument><name>Content</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_UnlimitedString</relatedStateVariable></argument>
      <argument><name>Size</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_UnlimitedInt</relatedStateVariable></argument>
      <argument><name>Type</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_UnlimitedInt</relatedStateVariable></argument>
      <argument><name>Color</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_UnlimitedInt</relatedStateVariable></argument>
      <argument><name>DanmakuId</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_UnlimitedString</relatedStateVariable></argument>
      <argument><name>Action</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_UnlimitedString</relatedStateVariable></argument>
    </argumentList></action>
    <action><name>GetPlayInfo</name><argumentList>
      <argument><name>Params</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_UnlimitedString</relatedStateVariable></argument>
      <argument><name>Content</name><direction>out</direction><relatedStateVariable>A_ARG_TYPE_UnlimitedString</relatedStateVariable></argument>
    </argumentList></action>
    <action><name>GetAccountInfo</name><argumentList>
      <argument><name>VipInfo</name><direction>out</direction><relatedStateVariable>A_ARG_TYPE_UnlimitedInt</relatedStateVariable></argument>
    </argumentList></action>
    <action><name>SwitchQuality</name><argumentList>
      <argument><name>Qn</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_UnlimitedInt</relatedStateVariable></argument>
    </argumentList></action>
  </actionList>
  <serviceStateTable>
    <stateVariable sendEvents="no"><name>A_ARG_TYPE_UnlimitedString</name><dataType>string</dataType></stateVariable>
    <stateVariable sendEvents="no"><name>A_ARG_TYPE_PackageName</name><dataType>string</dataType><defaultValue>com.xiaodianshi.tv.yst</defaultValue></stateVariable>
    <stateVariable sendEvents="no"><name>A_ARG_TYPE_AppKey</name><dataType>string</dataType><defaultValue>0000000000000000</defaultValue></stateVariable>
    <stateVariable sendEvents="no"><name>A_ARG_TYPE_Signature</name><dataType>string</dataType><defaultValue>0000000000000000</defaultValue></stateVariable>
    <stateVariable sendEvents="no"><name>A_ARG_TYPE_UnlimitedInt</name><dataType>i4</dataType></stateVariable>
    <stateVariable sendEvents="no"><name>A_ARG_TYPE_ScreenResolution</name><dataType>ui4</dataType></stateVariable>
    <stateVariable sendEvents="no"><name>SignedIn</name><dataType>boolean</dataType><defaultValue>1</defaultValue></stateVariable>
    <stateVariable sendEvents="no"><name>DanmakuSwitch</name><dataType>boolean</dataType></stateVariable>
  </serviceStateTable>
</scpd>
"""

    /** The reply that turns a plain connection into an NVA control channel. */
    fun handshake(session: String, uuid: String): String = listOf(
        "NVA/1.0 200 OK",
        "NvaVersion: 1",
        "Session: $session",
        "Connection: Keep-Alive",
        "UUID: $uuid",
        "Date: ${httpDate()}",
        "Content-Length: 0",
        "Server: $SERVER",
        "",
        ""
    ).joinToString("\r\n")

    fun httpDate(): String = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("GMT") }
        .format(Date())

    /** A command the phone sent: [method] (e.g. "Play") and its JSON payload, if any. */
    class Command(val counter: Int, val method: String, val payload: String?)

    /** A reply to a command, with no payload: the shape every acknowledgement takes. */
    fun ack(counter: Int): ByteArray = byteArrayOf(RET.toByte(), 0, intByte(counter, 0), intByte(counter, 1), intByte(counter, 2), intByte(counter, 3))

    /** A Ping frame; the phone drops the connection when these stop. */
    fun ping(counter: Int): ByteArray = byteArrayOf(PING.toByte(), 0, intByte(counter, 0), intByte(counter, 1), intByte(counter, 2), intByte(counter, 3))

    /** A reply carrying [json] (already encoded) — how play state, quality and speed are reported. */
    fun reply(counter: Int, json: String): ByteArray {
        val body = json.toByteArray(Charsets.UTF_8)
        val out = ByteArray(10 + body.size)
        out[0] = RET.toByte()
        out[1] = 1
        for (i in 0..3) out[2 + i] = intByte(counter, i)
        for (i in 0..3) out[6 + i] = intByte(body.size, i)
        System.arraycopy(body, 0, out, 10, body.size)
        return out
    }

    /**
     * A command from the receiver to the phone, e.g. "OnPlayState" with `{"playState":4}`.
     * The phone's UI (progress, quality, speed) is driven entirely by these.
     */
    fun broadcast(counter: Int, method: String, json: String): ByteArray {
        val name = method.toByteArray(Charsets.UTF_8)
        val body = json.toByteArray(Charsets.UTF_8)
        val out = ByteArray(1 + 1 + 4 + 1 + 1 + 7 + 1 + name.size + 4 + body.size)
        var at = 0
        out[at++] = SEND.toByte()
        out[at++] = 3
        for (i in 0..3) out[at++] = intByte(counter, i)
        out[at++] = 1
        out[at++] = 7
        "Command".toByteArray(Charsets.UTF_8).copyInto(out, at); at += 7
        out[at++] = name.size.toByte()
        name.copyInto(out, at); at += name.size
        for (i in 0..3) out[at++] = intByte(body.size, i)
        body.copyInto(out, at)
        return out
    }

    private fun intByte(value: Int, index: Int): Byte = ((value shr (24 - index * 8)) and 0xFF).toByte()

    private fun readInt(bytes: ByteArray, at: Int): Int =
        ((bytes[at].toInt() and 0xFF) shl 24) or ((bytes[at + 1].toInt() and 0xFF) shl 16) or
            ((bytes[at + 2].toInt() and 0xFF) shl 8) or (bytes[at + 3].toInt() and 0xFF)

    /**
     * Pulls whole frames out of [buffer]. Frames are scanned rather than assumed aligned: a
     * connection can start mid-stream, and the phone interleaves pings and replies with commands.
     * Returns how many bytes were consumed; [rest] receives the decoded frames.
     */
    fun parse(buffer: ByteArray, length: Int, rest: MutableList<Command>): Int {
        var index = 0
        while (index < length) {
            if (length - index < 6) break
            val type = buffer[index].toInt() and 0xFF
            if (type != SEND && type != RET) {
                index++
                continue
            }
            val kind = buffer[index + 1].toInt() and 0xFF
            val counter = readInt(buffer, index + 2)
            if (type == RET) {
                // A reply to something we sent: nothing to do but skip it.
                when (kind) {
                    0 -> index += 6
                    1 -> {
                        if (length - index - 6 < 4) return index
                        val size = readInt(buffer, index + 6)
                        if (length - index - 10 < size) return index
                        index += 10 + size
                    }
                    else -> index++
                }
                continue
            }
            if (kind != 2 && kind != 3) {
                index++
                continue
            }
            if (length - index - 6 < 10) return index
            val hasPayload = buffer[index + 6].toInt() == 1
            val nameLength = buffer[index + 7].toInt() and 0xFF
            val marker = String(buffer, index + 8, 7, Charsets.US_ASCII)
            val methodLength = buffer[index + 15].toInt() and 0xFF
            if (!hasPayload || marker != "Command" || nameLength != 7) {
                index++
                continue
            }
            val bodyAt = index + 16
            if (length - bodyAt < methodLength) return index
            val method = String(buffer, bodyAt, methodLength, Charsets.UTF_8)
            if (kind == 2) {
                rest.add(Command(counter, method, null))
                index = bodyAt + methodLength
                continue
            }
            if (length - bodyAt - methodLength < 4) return index
            val payloadAt = bodyAt + methodLength
            val size = readInt(buffer, payloadAt)
            if (length - payloadAt - 4 < size) return index
            val payload = String(buffer, payloadAt + 4, size, Charsets.UTF_8)
            rest.add(Command(counter, method, payload))
            index = payloadAt + 4 + size
        }
        return index
    }
}
