package com.kar.reader.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

/** Reads the transcript file Microsoft Teams produces (.vtt). Nothing leaves this computer. */
object VttImport {
    class Result(val lines: List<Line>, val speakers: List<String>, val durationMs: Long)

    private val stamp = Regex("""((?:\d+:)?\d{1,2}:\d{2}[.,]\d{1,3})\s*-->\s*((?:\d+:)?\d{1,2}:\d{2}[.,]\d{1,3})""")
    private val voice = Regex("""^<v(?:\.[^ >]*)?\s+([^>]*)>(.*?)(?:</v>)?$""", RegexOption.DOT_MATCHES_ALL)

    private fun ms(t: String): Long {
        val p = t.replace(',', '.').split(':')
        val sec = p.last().toDouble()
        val min = p[p.size - 2].toLong()
        val hr = if (p.size == 3) p[0].toLong() else 0L
        return ((hr * 3600 + min * 60 + sec) * 1000).toLong()
    }

    fun parse(text: String): Result {
        val names = ArrayList<String>()
        val out = ArrayList<Line>()
        val blocks = text.replace("\r\n", "\n").replace('\r', '\n').removePrefix("﻿").split(Regex("\n\\s*\n"))
        for (b in blocks) {
            val rows = b.lines()
            val i = rows.indexOfFirst { stamp.containsMatchIn(it) }
            if (i < 0) continue
            val m = stamp.find(rows[i])!!
            var body = rows.drop(i + 1).joinToString(" ") { it.trim() }.trim()
            if (body.isEmpty()) continue
            var who = ""
            voice.find(body)?.let { v -> who = v.groupValues[1].trim(); body = v.groupValues[2] }
            if (who.isEmpty()) Regex("""^([^:]{2,40}):\s+(.+)$""").find(body)?.let { v -> who = v.groupValues[1].trim(); body = v.groupValues[2] }
            body = body.replace(Regex("<[^>]+>"), "").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").trim()
            if (body.isEmpty()) continue
            if (who.isEmpty()) who = "Speaker"
            var sp = names.indexOf(who)
            if (sp < 0) { names += who; sp = names.lastIndex }
            val start = ms(m.groupValues[1]); val end = ms(m.groupValues[2])
            val prev = out.lastOrNull()
            if (prev != null && prev.speaker == sp && start - prev.endMs < 2500) out[out.lastIndex] = prev.copy(endMs = end, text = prev.text + " " + body)
            else out += Line(start, end, sp, body)
        }
        return Result(out, names, out.maxOfOrNull { it.endMs } ?: 0L)
    }

    /** Adds the file to Transcripts. There is no audio, so playback is unavailable. */
    fun importInto(state: AppState, file: java.io.File): String {
        val r = parse(file.readText(Charsets.UTF_8))
        if (r.lines.isEmpty()) return "That file has no transcript lines. In Teams, download the transcript as .vtt."
        val id = state.newId()
        val startedAt = file.lastModified() - r.durationMs
        val meta = TranscriptMeta(id, file.nameWithoutExtension.replace('_', ' '), file.lastModified(), r.durationMs, "", "", "From Teams",
            speakerNames = r.speakers.withIndex().associate { it.index to it.value }, status = "done", separate = true, startedAt = startedAt, meeting = true)
        TranscriptFiles.save(id, r.lines)
        state.update { it.copy(transcripts = it.transcripts + meta) }
        return ""
    }
}

class TeamsMeeting(val subject: String, val startMs: Long, val endMs: Long, val attendees: List<String>)

/**
 * Signs in to Microsoft with the "device code" flow (you type a short code on microsoft.com/devicelogin) and reads your own calendar.
 * It needs an app registration made by you or your IT team (Application client ID) with the delegated Calendars.Read permission.
 * Only calendar details of meetings you choose are used. The sign-in token is kept in memory and discarded when the app closes.
 */
object TeamsGraph {
    private val http = HttpClient.newHttpClient()
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun post(url: String, form: Map<String, String>): JsonObject {
        val body = form.entries.joinToString("&") { "${enc(it.key)}=${enc(it.value)}" }
        val req = HttpRequest.newBuilder(URI(url)).header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(body)).build()
        return Json.parseToJsonElement(http.send(req, HttpResponse.BodyHandlers.ofString()).body()).jsonObject
    }

    class DeviceCode(val userCode: String, val url: String, val deviceCode: String, val intervalSec: Int, val expiresSec: Int)

    fun start(clientId: String, tenant: String): DeviceCode {
        val r = post("https://login.microsoftonline.com/${tenant.ifBlank { "organizations" }}/oauth2/v2.0/devicecode", mapOf("client_id" to clientId, "scope" to "Calendars.Read"))
        r["error_description"]?.let { throw IllegalStateException(it.jsonPrimitive.content.lineSequence().first()) }
        return DeviceCode(r["user_code"]!!.jsonPrimitive.content, r["verification_uri"]!!.jsonPrimitive.content, r["device_code"]!!.jsonPrimitive.content,
            r["interval"]?.jsonPrimitive?.int ?: 5, r["expires_in"]?.jsonPrimitive?.int ?: 900)
    }

    suspend fun waitForToken(clientId: String, tenant: String, d: DeviceCode): String {
        var wait = d.intervalSec
        val until = System.currentTimeMillis() + d.expiresSec * 1000L
        while (System.currentTimeMillis() < until) {
            delay(wait * 1000L)
            val r = withContext(Dispatchers.IO) {
                post("https://login.microsoftonline.com/${tenant.ifBlank { "organizations" }}/oauth2/v2.0/token",
                    mapOf("grant_type" to "urn:ietf:params:oauth:grant-type:device_code", "client_id" to clientId, "device_code" to d.deviceCode))
            }
            r["access_token"]?.let { return it.jsonPrimitive.content }
            when (r["error"]?.jsonPrimitive?.content) {
                "authorization_pending" -> {}
                "slow_down" -> wait += 5
                else -> throw IllegalStateException(r["error_description"]?.jsonPrimitive?.content?.lineSequence()?.first() ?: "Sign-in failed.")
            }
        }
        throw IllegalStateException("The sign-in code expired. Try again.")
    }

    fun meetings(token: String, days: Int = 14): List<TeamsMeeting> {
        val now = Instant.now()
        val url = "https://graph.microsoft.com/v1.0/me/calendarView?startDateTime=${enc(now.minusSeconds(days * 86_400L).toString())}&endDateTime=${enc(now.plusSeconds(86_400L).toString())}" +
            "&\$select=subject,start,end,attendees&\$orderby=${enc("start/dateTime desc")}&\$top=60"
        val req = HttpRequest.newBuilder(URI(url)).header("Authorization", "Bearer $token").header("Prefer", "outlook.timezone=\"UTC\"").GET().build()
        val res = http.send(req, HttpResponse.BodyHandlers.ofString())
        val root = Json.parseToJsonElement(res.body()).jsonObject
        root["error"]?.let { throw IllegalStateException(it.jsonObject["message"]?.jsonPrimitive?.content ?: "Microsoft refused the request.") }
        return parseMeetings(root)
    }

    fun parseMeetings(root: JsonObject): List<TeamsMeeting> = root["value"]?.jsonArray.orEmpty().mapNotNull { e ->
        val o = e.jsonObject
        fun time(k: String) = o[k]?.jsonObject?.get("dateTime")?.jsonPrimitive?.content?.take(19)?.let { LocalDateTime.parse(it).toInstant(ZoneOffset.UTC).toEpochMilli() }
        val s = time("start") ?: return@mapNotNull null
        val en = time("end") ?: s
        TeamsMeeting(
            o["subject"]?.jsonPrimitive?.contentOrNull.orEmpty().ifBlank { "Meeting" }, s, en,
            o["attendees"]?.jsonArray.orEmpty().mapNotNull { a -> a.jsonObject["emailAddress"]?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } }.distinct(),
        )
    }
}

/** Pick a meeting from your Microsoft 365 calendar to fill the minutes. */
@Composable
fun TeamsMeetingsDialog(state: AppState, onPick: (TeamsMeeting) -> Unit, onDismiss: () -> Unit) {
    val s = state.data.settings
    var message by remember { mutableStateOf("") }
    var code by remember { mutableStateOf<TeamsGraph.DeviceCode?>(null) }
    var list by remember { mutableStateOf<List<TeamsMeeting>?>(null) }
    LaunchedEffect(Unit) {
        if (s.msClientId.isBlank()) { message = "Add your Application (client) ID in Settings › Microsoft Teams first."; return@LaunchedEffect }
        try {
            message = "Contacting Microsoft…"
            val d = withContext(Dispatchers.IO) { TeamsGraph.start(s.msClientId.trim(), s.msTenant.trim()) }
            code = d
            runCatching { java.awt.Desktop.getDesktop().browse(URI(d.url)) }
            message = "Waiting for you to finish signing in…"
            val token = TeamsGraph.waitForToken(s.msClientId.trim(), s.msTenant.trim(), d)
            message = "Reading your calendar…"
            list = withContext(Dispatchers.IO) { TeamsGraph.meetings(token) }
            message = if (list.isNullOrEmpty()) "No meetings found in the last two weeks." else ""
        } catch (e: Exception) { message = "Couldn't reach Microsoft: ${e.message}"; code = null }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Fill from your Teams calendar") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.widthIn(min = 440.dp)) {
                if (list == null) code?.let {
                    Text("Go to ${it.url} and enter this code:")
                    Text(it.userCode, style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
                }
                if (message.isNotEmpty()) Muted(message)
                list?.takeIf { it.isNotEmpty() }?.let { l ->
                    LazyColumn(Modifier.heightIn(max = 340.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(l) { mt ->
                            Panel(Modifier.fillMaxWidth(), onClick = { onPick(mt) }) {
                                Column(Modifier.padding(10.dp)) {
                                    Text(mt.subject, style = MaterialTheme.typography.titleSmall)
                                    Muted("${MinutesFormat.dayTime(mt.startMs)} · ${MinutesFormat.duration(mt.endMs - mt.startMs)} · ${mt.attendees.size} attendees")
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
