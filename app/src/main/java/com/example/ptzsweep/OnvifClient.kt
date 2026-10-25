package com.example.ptzsweep

import android.net.Uri
import android.util.Base64
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/** Minimal ONVIF client: just enough to find the PTZ service and send move/stop commands. */
class OnvifClient(
    host: String,
    port: Int,
    private val user: String,
    private val pass: String,
) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    private val deviceUrl = "http://$host:$port/onvif/device_service"
    private var mediaUrl = ""
    private var ptzUrl = ""
    private var profile = ""
    private var profileTokens: List<String> = emptyList()
    private var clockOffsetMs = 0L

    /** Discovers service URLs, the camera clock and the first media profile. Blocking. */
    fun connect() {
        syncClock()
        val caps = call(deviceUrl,
            """<GetCapabilities xmlns="http://www.onvif.org/ver10/device/wsdl"><Category>All</Category></GetCapabilities>""")
        mediaUrl = xaddr(caps, "Media") ?: deviceUrl.replace("device_service", "media_service")
        ptzUrl = xaddr(caps, "PTZ") ?: throw IllegalStateException("Camera reports no PTZ service")

        val profiles = call(mediaUrl, """<GetProfiles xmlns="http://www.onvif.org/ver10/media/wsdl"/>""")
        profileTokens = Regex("""Profiles[^>]*token="([^"]+)"""").findAll(profiles)
            .map { it.groupValues[1] }.distinct().toList()
        profile = profileTokens.firstOrNull() ?: throw IllegalStateException("No media profile found")
    }

    /** pan: -1.0 (left) .. +1.0 (right), tilt: -1.0 (down) .. +1.0 (up) */
    fun move(pan: Float, tilt: Float = 0f) {
        call(ptzUrl, """
            <ContinuousMove xmlns="http://www.onvif.org/ver20/ptz/wsdl">
              <ProfileToken>$profile</ProfileToken>
              <Velocity><PanTilt x="$pan" y="$tilt" xmlns="http://www.onvif.org/ver10/schema"/></Velocity>
            </ContinuousMove>""")
    }

    fun stop() {
        call(ptzUrl, """
            <Stop xmlns="http://www.onvif.org/ver20/ptz/wsdl">
              <ProfileToken>$profile</ProfileToken><PanTilt>true</PanTilt><Zoom>true</Zoom>
            </Stop>""")
    }

    /** Asks the camera for its RTSP video address and adds the login to it. */
    fun streamUri(preferSub: Boolean = false): String {
        val token = if (preferSub && profileTokens.size > 1) profileTokens[1] else profile
        val xml = call(mediaUrl, """
            <GetStreamUri xmlns="http://www.onvif.org/ver10/media/wsdl">
              <StreamSetup>
                <Stream xmlns="http://www.onvif.org/ver10/schema">RTP-Unicast</Stream>
                <Transport xmlns="http://www.onvif.org/ver10/schema"><Protocol>RTSP</Protocol></Transport>
              </StreamSetup>
              <ProfileToken>$token</ProfileToken>
            </GetStreamUri>""")
        val raw = Regex("""<(?:\w+:)?Uri>([^<]+)""").find(xml)?.groupValues?.get(1)?.trim()
            ?.replace("&amp;", "&")
            ?: throw IllegalStateException("Camera did not provide a video stream address")
        val hasLogin = raw.removePrefix("rtsp://").substringBefore("/").contains("@")
        return if (raw.startsWith("rtsp://") && !hasLogin)
            "rtsp://${Uri.encode(user)}:${Uri.encode(pass)}@" + raw.removePrefix("rtsp://")
        else raw
    }

    /** Lists the ONVIF services the camera offers, as namespace -> address. */
    private fun services(): Map<String, String> {
        val xml = call(deviceUrl,
            """<GetServices xmlns="http://www.onvif.org/ver10/device/wsdl"><IncludeCapability>false</IncludeCapability></GetServices>""")
        return Regex("""<(?:\w+:)?Namespace>([^<]+)</(?:\w+:)?Namespace>\s*<(?:\w+:)?XAddr>([^<]+)</(?:\w+:)?XAddr>""")
            .findAll(xml).associate { it.groupValues[1].trim() to it.groupValues[2].trim() }
    }

    /** Asks the camera what it offers for SD card recordings and reports in plain text. */
    fun storageReport(): String {
        val out = StringBuilder()
        val svc = try {
            services()
        } catch (e: Exception) {
            return "Could not read the camera's service list: ${e.message}"
        }
        val names = svc.keys.map { it.substringAfter("onvif.org/").substringBefore("/wsdl") }.sorted()
        out.appendLine("Services: " + names.joinToString(", "))

        val rec = svc.entries.firstOrNull { it.key.endsWith("/recording/wsdl") }?.value
        val search = svc.entries.firstOrNull { it.key.endsWith("/search/wsdl") }?.value
        val replay = svc.entries.firstOrNull { it.key.endsWith("/replay/wsdl") }?.value
        fun yn(v: String?) = if (v != null) "yes" else "no"
        out.appendLine("Recording: ${yn(rec)}   Search: ${yn(search)}   Replay: ${yn(replay)}")

        if (rec == null && search == null && replay == null) {
            out.appendLine()
            out.append("This camera does not offer SD card recordings over ONVIF.")
            return out.toString()
        }

        var token: String? = null
        if (rec != null) {
            runCatching {
                val x = call(rec, """<GetRecordings xmlns="http://www.onvif.org/ver10/recording/wsdl"/>""")
                val tokens = Regex("""<(?:\w+:)?RecordingToken>([^<]+)<""").findAll(x)
                    .map { it.groupValues[1] }.distinct().toList()
                out.appendLine("Recordings found: ${tokens.size}")
                token = tokens.firstOrNull()
            }.onFailure { out.appendLine("GetRecordings failed: ${it.message}") }
        }

        if (search != null) {
            runCatching {
                val x = call(search, """<GetRecordingSummary xmlns="http://www.onvif.org/ver10/search/wsdl"/>""")
                fun tag(n: String) = Regex("""<(?:\w+:)?$n>([^<]+)<""").find(x)?.groupValues?.get(1)
                out.appendLine("Storage: ${tag("NumberRecordings") ?: "?"} recordings, " +
                    "from ${tag("DataFrom") ?: "?"} to ${tag("DataUntil") ?: "?"}")
            }.onFailure { out.appendLine("Search failed: ${it.message}") }
        }

        val t = token
        if (replay != null && t != null) {
            runCatching {
                val x = call(replay, """<GetReplayUri xmlns="http://www.onvif.org/ver10/replay/wsdl"><StreamSetup><Stream xmlns="http://www.onvif.org/ver10/schema">RTP-Unicast</Stream><Transport xmlns="http://www.onvif.org/ver10/schema"><Protocol>RTSP</Protocol></Transport></StreamSetup><RecordingToken>$t</RecordingToken></GetReplayUri>""")
                val uri = Regex("""<(?:\w+:)?Uri>([^<]+)""").find(x)?.groupValues?.get(1)?.replace("&amp;", "&")
                out.appendLine("Playback address: ${uri ?: "none"}")
            }.onFailure { out.appendLine("Playback address failed: ${it.message}") }
        }
        return out.toString().trim()
    }

    // ---- internals ----

    private fun xaddr(xml: String, tag: String): String? =
        Regex("""<(?:\w+:)?$tag>\s*<(?:\w+:)?XAddr>([^<]+)""").find(xml)?.groupValues?.get(1)?.trim()

    /** Digest auth fails if phone and camera clocks differ, so measure the offset first. */
    private fun syncClock() {
        runCatching {
            val xml = call(deviceUrl,
                """<GetSystemDateAndTime xmlns="http://www.onvif.org/ver10/device/wsdl"/>""", auth = false)
            val m = Regex(
                """UTCDateTime>.*?Hour>(\d+)<.*?Minute>(\d+)<.*?Second>(\d+)<.*?Year>(\d+)<.*?Month>(\d+)<.*?Day>(\d+)<""",
                RegexOption.DOT_MATCHES_ALL
            ).find(xml) ?: return
            val (h, mi, s, y, mo, d) = m.destructured
            val cal = java.util.Calendar.getInstance(TimeZone.getTimeZone("UTC"))
            cal.set(y.toInt(), mo.toInt() - 1, d.toInt(), h.toInt(), mi.toInt(), s.toInt())
            clockOffsetMs = cal.timeInMillis - System.currentTimeMillis()
        }
    }

    private fun securityHeader(): String {
        val nonce = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
        val created = fmt.format(Date(System.currentTimeMillis() + clockOffsetMs))
        val sha = MessageDigest.getInstance("SHA-1")
        sha.update(nonce); sha.update(created.toByteArray()); sha.update(pass.toByteArray())
        val digest = Base64.encodeToString(sha.digest(), Base64.NO_WRAP)
        val nonceB64 = Base64.encodeToString(nonce, Base64.NO_WRAP)
        return """
            <Security xmlns="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd">
              <UsernameToken>
                <Username>$user</Username>
                <Password Type="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-username-token-profile-1.0#PasswordDigest">$digest</Password>
                <Nonce EncodingType="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-soap-message-security-1.0#Base64Binary">$nonceB64</Nonce>
                <Created xmlns="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-utility-1.0.xsd">$created</Created>
              </UsernameToken>
            </Security>"""
    }

    private fun call(url: String, body: String, auth: Boolean = true): String {
        val header = if (auth) "<s:Header>${securityHeader()}</s:Header>" else ""
        val envelope = """<?xml version="1.0" encoding="utf-8"?>
            <s:Envelope xmlns:s="http://www.w3.org/2003/05/soap-envelope">$header<s:Body>$body</s:Body></s:Envelope>"""
        val req = Request.Builder().url(url)
            .post(envelope.toRequestBody("application/soap+xml; charset=utf-8".toMediaType()))
            .build()
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                val reason = if (resp.code == 400 || resp.code == 401) " (check username/password)" else ""
                throw IllegalStateException("Camera returned HTTP ${resp.code}$reason")
            }
            return text
        }
    }
}
