package com.kar.reader.feature.transcribe

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.ContextCompat
import com.kar.reader.data.db.TranscriptDao
import com.kar.reader.data.db.TranscriptEntity
import com.kar.reader.data.db.TranscriptMarkEntity
import com.kar.reader.data.db.TranscriptSegmentEntity
import com.kar.reader.data.db.TranscriptSpeakerEntity
import com.kar.reader.data.db.TranscriptStatus
import com.kar.reader.data.files.DocumentStore
import com.kar.reader.di.AppScope
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt

data class RecordingState(
    val transcriptId: String,
    val title: String,
    val elapsedMs: Long = 0,
    val level: Float = 0f,
    val paused: Boolean = false,
    val marks: List<TranscriptMarkEntity> = emptyList(),
    val levels: List<Float> = emptyList(),
)

data class ProcessingState(val transcriptId: String, val title: String, val status: String, val progress: Float)

data class NewTranscriptOptions(
    val title: String,
    val language: String,
    val separateSpeakers: Boolean,
    val expectedSpeakers: Int,
    val bookId: String?,
)

/**
 * Owns recording and the transcription queue. Work runs in the app scope while [TranscribeService] keeps the
 * process in the foreground, so it continues with the screen off. One job runs at a time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class TranscriptionManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: TranscriptDao,
    private val store: DocumentStore,
    @AppScope private val scope: CoroutineScope,
) {
    private val _recording = MutableStateFlow<RecordingState?>(null)
    val recording: StateFlow<RecordingState?> = _recording.asStateFlow()

    private val _processing = MutableStateFlow<ProcessingState?>(null)
    val processing: StateFlow<ProcessingState?> = _processing.asStateFlow()

    private val queue = Channel<String>(Channel.UNLIMITED)
    private val pendingSources = ConcurrentHashMap<String, Uri>()
    private val cancelled = ConcurrentHashMap.newKeySet<String>()
    private val worker = Dispatchers.Default.limitedParallelism(1)

    @Volatile private var recorderThread: Thread? = null
    @Volatile private var recordingStop = false
    @Volatile private var recordingPaused = false

    val modelsAvailable: Boolean by lazy { SpeechModels.available(context) }

    init {
        scope.launch(Dispatchers.IO) { recoverInterrupted() }
        scope.launch(worker) { for (id in queue) runJob(id) }
    }

    val isBusy: Boolean get() = _recording.value != null || _processing.value != null

    // ---------------------------------------------------------------- recording

    /** Creates the transcript row and starts recording. RECORD_AUDIO must already be granted. */
    suspend fun startRecording(profileId: String, options: NewTranscriptOptions): String {
        check(_recording.value == null) { "Already recording" }
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val t = TranscriptEntity(
            id = id, profileId = profileId, title = options.title, bookId = options.bookId, source = "RECORDED",
            audioFile = "$id.wav", languageChoice = options.language, separateSpeakers = options.separateSpeakers,
            expectedSpeakers = options.expectedSpeakers, status = TranscriptStatus.RECORDING, createdAt = now,
        )
        dao.upsert(t)
        _recording.value = RecordingState(id, options.title)
        startService(TranscribeService.MODE_RECORD)
        recordingStop = false; recordingPaused = false
        recorderThread = Thread({ recordLoop(id, store.recordingFile(t.audioFile)) }, "kar-recorder").also { it.start() }
        return id
    }

    fun pauseRecording(paused: Boolean) {
        recordingPaused = paused
        _recording.update { it?.copy(paused = paused) }
    }

    suspend fun markMoment(label: String) {
        val r = _recording.value ?: return
        val mark = TranscriptMarkEntity(UUID.randomUUID().toString(), r.transcriptId, r.elapsedMs, label.ifBlank { "Marked moment" }.take(120))
        dao.insertMark(mark)
        _recording.update { it?.copy(marks = it.marks + mark) }
    }

    /** Stops recording; with [transcribe] the recording is queued for transcription straight away. */
    suspend fun stopRecording(transcribe: Boolean = true) {
        val r = _recording.value ?: return
        recordingStop = true
        withContext(Dispatchers.IO) { recorderThread?.join(5_000) }
        recorderThread = null
        val file = store.recordingFile("${r.transcriptId}.wav")
        val duration = withContext(Dispatchers.IO) { WavRepair.repair(file) } ?: 0L
        val t = dao.get(r.transcriptId)
        _recording.value = null
        if (t != null) {
            dao.upsert(t.copy(durationMs = duration, status = TranscriptStatus.CANCELLED, error = null, modifiedAt = System.currentTimeMillis()))
            if (transcribe && duration > 500) transcribe(t.id)
        }
    }

    @SuppressLint("MissingPermission") // checked by the screen before starting
    private fun recordLoop(id: String, file: File) {
        val minBuffer = AudioRecord.getMinBufferSize(SPEECH_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val bufferBytes = maxOf(minBuffer, SPEECH_RATE / 5 * 2) // 200 ms
        val record = try {
            AudioRecord(MediaRecorder.AudioSource.MIC, SPEECH_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferBytes * 2)
        } catch (e: Exception) { failRecording(id, "The microphone could not be opened."); return }
        if (record.state != AudioRecord.STATE_INITIALIZED) { record.release(); failRecording(id, "The microphone is not available. Another app may be using it."); return }
        val buffer = ByteArray(bufferBytes)
        val levels = ArrayDeque<Float>()
        try {
            WavWriter(file).use { writer ->
                var running = false
                var lastSync = 0L
                var lastUi = 0L
                while (!recordingStop) {
                    if (recordingPaused) {
                        if (running) { record.stop(); running = false; writer.sync() }
                        Thread.sleep(100)
                        continue
                    }
                    if (!running) { record.startRecording(); running = true }
                    val n = record.read(buffer, 0, buffer.size)
                    if (n <= 0) { if (n < 0) Thread.sleep(20); continue }
                    writer.write(buffer, n)
                    var sum = 0.0
                    for (i in 0 until n / 2) { val v = ((buffer[2 * i].toInt() and 0xFF) or (buffer[2 * i + 1].toInt() shl 8)) / 32768.0; sum += v * v }
                    val level = (sqrt(sum / (n / 2).coerceAtLeast(1)) * 4).toFloat().coerceIn(0f, 1f)
                    if (writer.dataBytes - lastSync > SPEECH_RATE * 2L * 5) { writer.sync(); lastSync = writer.dataBytes }
                    val now = System.currentTimeMillis()
                    if (now - lastUi > 100) {
                        lastUi = now
                        levels.addLast(level); while (levels.size > 48) levels.removeFirst()
                        val snapshot = levels.toList()
                        _recording.update { it?.copy(elapsedMs = writer.durationMs, level = level, levels = snapshot) }
                    }
                }
                if (running) record.stop()
            }
        } catch (e: Exception) {
            failRecording(id, "Recording stopped: ${e.message ?: "unknown error"}")
        } finally {
            record.release()
        }
    }

    private fun failRecording(id: String, message: String) {
        runBlocking { dao.get(id)?.let { dao.upsert(it.copy(status = TranscriptStatus.FAILED, error = message, modifiedAt = System.currentTimeMillis())) } }
        _recording.value = null
    }

    // ---------------------------------------------------------------- import and queue

    /** Imports an audio file: it is converted to a 16 kHz recording in app storage, then transcribed. */
    suspend fun importAudio(profileId: String, uri: Uri, options: NewTranscriptOptions): String {
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val name = withContext(Dispatchers.IO) { displayName(uri) }
        runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        dao.upsert(
            TranscriptEntity(
                id = id, profileId = profileId, title = options.title.ifBlank { name?.substringBeforeLast('.') ?: "Imported audio" },
                bookId = options.bookId, source = "IMPORTED", sourceName = name, audioFile = "$id.wav",
                languageChoice = options.language, separateSpeakers = options.separateSpeakers, expectedSpeakers = options.expectedSpeakers,
                status = TranscriptStatus.CONVERTING, createdAt = now,
            ),
        )
        pendingSources[id] = uri
        startService(TranscribeService.MODE_PROCESS)
        queue.send(id)
        return id
    }

    /** Queues an existing recording for (re)transcription. */
    suspend fun transcribe(id: String) {
        val t = dao.get(id) ?: return
        if (t.status in TranscriptStatus.working && _processing.value?.transcriptId == id) return
        cancelled.remove(id)
        dao.setStatus(id, TranscriptStatus.QUEUED, 0f, null, System.currentTimeMillis())
        startService(TranscribeService.MODE_PROCESS)
        queue.send(id)
    }

    /** Updates the options used by the next run (language, speakers). */
    suspend fun updateOptions(id: String, language: String, separateSpeakers: Boolean, expectedSpeakers: Int) {
        val t = dao.get(id) ?: return
        dao.upsert(t.copy(languageChoice = language, separateSpeakers = separateSpeakers, expectedSpeakers = expectedSpeakers, modifiedAt = System.currentTimeMillis()))
    }

    fun cancel(id: String) { cancelled += id }

    private suspend fun runJob(id: String) {
        val t = dao.get(id) ?: return
        if (t.deletedAt != null || id in cancelled) {
            if (t.status in TranscriptStatus.working) dao.setStatus(id, TranscriptStatus.CANCELLED, 0f, null, System.currentTimeMillis())
            cancelled.remove(id); pendingSources.remove(id)
            return
        }
        val file = store.recordingFile(t.audioFile)
        var lastWrite = 0L
        var lastProgress = -1f
        fun report(status: String, p: Float) {
            _processing.value = ProcessingState(id, t.title, status, p)
            val now = System.currentTimeMillis()
            if (now - lastWrite > 1_000 || p >= 1f || p - lastProgress > 0.05f) {
                lastWrite = now; lastProgress = p
                runBlocking { dao.setStatus(id, status, p, null, now) }
            }
        }
        val isCancelled = { id in cancelled }
        try {
            var durationMs = t.durationMs
            val source = pendingSources.remove(id)
            val needsConversion = t.status == TranscriptStatus.CONVERTING || durationMs == 0L && source != null
            report(if (needsConversion) TranscriptStatus.CONVERTING else TranscriptStatus.QUEUED, 0f)
            if (needsConversion) {
                val uri = source ?: throw IllegalStateException("The import was interrupted. Please import the file again.")
                durationMs = AudioConverter.toSpeechWav(context, uri, file, { report(TranscriptStatus.CONVERTING, it) }, isCancelled)
                dao.upsert(t.copy(durationMs = durationMs, status = TranscriptStatus.QUEUED, modifiedAt = System.currentTimeMillis()))
            }
            if (!file.isFile) throw IllegalStateException("The recording file is missing.")
            if (durationMs < 500) throw IllegalStateException("The recording is too short to transcribe.")
            if (!modelsAvailable) throw IllegalStateException("The speech models are not included in this build of the app.")
            val result = SpeechEngine(context).transcribe(
                wav = file,
                separateSpeakers = t.separateSpeakers,
                expectedSpeakers = t.expectedSpeakers,
                language = t.languageChoice,
                onProgress = { stage, p ->
                    when (stage) {
                        Stage.SPEAKERS -> report(TranscriptStatus.SPEAKERS, p)
                        Stage.WORDS -> report(TranscriptStatus.WORDS, p)
                    }
                },
                isCancelled = isCancelled,
            )
            val now = System.currentTimeMillis()
            val segments = result.lines.map { TranscriptSegmentEntity(UUID.randomUUID().toString(), id, it.startMs, it.endMs, it.speaker, it.text) }
            val speakers = (0 until result.speakerCount).map { TranscriptSpeakerEntity(id, it, "Speaker ${it + 1}") }
            val latest = dao.get(id) ?: t
            dao.replaceResult(
                latest.copy(
                    durationMs = durationMs, status = TranscriptStatus.DONE, progress = 1f, error = null,
                    speakerCount = result.speakerCount, detectedLanguages = result.languages, processedAt = now, modifiedAt = now,
                ),
                segments, speakers,
            )
        } catch (e: CancellationException) {
            dao.setStatus(id, TranscriptStatus.CANCELLED, 0f, null, System.currentTimeMillis())
        } catch (e: OutOfMemoryError) {
            dao.setStatus(id, TranscriptStatus.FAILED, 0f, "This phone ran out of memory. Try turning off \"Tell speakers apart\", or split the recording.", System.currentTimeMillis())
        } catch (e: Throwable) {
            val message = when (e) {
                is AudioConverter.Unsupported -> e.message
                is IllegalStateException -> e.message
                else -> "Transcription failed: ${e.message ?: e.javaClass.simpleName}"
            }
            dao.setStatus(id, TranscriptStatus.FAILED, 0f, message, System.currentTimeMillis())
        } finally {
            cancelled.remove(id)
            _processing.value = null
        }
    }

    private suspend fun recoverInterrupted() {
        val now = System.currentTimeMillis()
        dao.withStatus(listOf(TranscriptStatus.RECORDING)).forEach { t ->
            val duration = WavRepair.repair(store.recordingFile(t.audioFile)) ?: 0L
            dao.upsert(t.copy(durationMs = duration, status = TranscriptStatus.CANCELLED, error = "The recording stopped unexpectedly. Everything up to the last few seconds was saved.", modifiedAt = now))
        }
        dao.withStatus(TranscriptStatus.working.toList()).forEach { t ->
            if (t.status == TranscriptStatus.CONVERTING) dao.setStatus(t.id, TranscriptStatus.FAILED, 0f, "The import was interrupted. Please import the file again.", now)
            else dao.setStatus(t.id, TranscriptStatus.CANCELLED, 0f, "Transcription was interrupted. Tap Transcribe to run it again.", now)
        }
    }

    private fun startService(mode: String) {
        val intent = Intent(context, TranscribeService::class.java).putExtra(TranscribeService.EXTRA_MODE, mode)
        // Android can refuse a foreground start (for example when the app is in the background). The work still runs
        // while the process lives, and is marked interrupted at next start if it is stopped; never crash for it.
        runCatching { ContextCompat.startForegroundService(context, intent) }
    }

    private fun displayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull()
}
