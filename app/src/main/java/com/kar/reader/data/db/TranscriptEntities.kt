package com.kar.reader.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/*
 * Transcripts (added in database version 2). The audio lives in app storage at files/recordings/<id>.wav
 * (16 kHz mono); everything here is derived from it, so a failed or cancelled run can always be retried.
 */

object TranscriptStatus {
    const val RECORDING = "RECORDING"
    const val QUEUED = "QUEUED"
    const val CONVERTING = "CONVERTING"
    const val SPEAKERS = "SPEAKERS"
    const val WORDS = "WORDS"
    const val DONE = "DONE"
    const val FAILED = "FAILED"
    const val CANCELLED = "CANCELLED"

    val working = setOf(QUEUED, CONVERTING, SPEAKERS, WORDS)
}

@Entity(tableName = "transcripts", indices = [Index("profile_id"), Index("deleted_at")])
data class TranscriptEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "profile_id") val profileId: String,
    val title: String,
    /** Optional book this recording belongs to (a lecture on a chapter, say). Not a foreign key: books can be purged. */
    @ColumnInfo(name = "book_id") val bookId: String? = null,
    /** RECORDED or IMPORTED. */
    val source: String,
    @ColumnInfo(name = "source_name") val sourceName: String? = null,
    @ColumnInfo(name = "audio_file") val audioFile: String,
    @ColumnInfo(name = "duration_ms") val durationMs: Long = 0,
    /** The language the user chose; empty means detect automatically. */
    @ColumnInfo(name = "language_choice") val languageChoice: String = "",
    /** Languages the recognizer reported, comma separated. */
    @ColumnInfo(name = "detected_languages") val detectedLanguages: String = "",
    @ColumnInfo(name = "separate_speakers") val separateSpeakers: Boolean = true,
    /** 0 means "not sure"; otherwise the number of people the user said were talking. */
    @ColumnInfo(name = "expected_speakers") val expectedSpeakers: Int = 0,
    @ColumnInfo(name = "speaker_count") val speakerCount: Int = 0,
    val status: String,
    val progress: Float = 0f,
    val error: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "processed_at") val processedAt: Long? = null,
    @ColumnInfo(name = "deleted_at") val deletedAt: Long? = null,
    @ColumnInfo(name = "modified_at") val modifiedAt: Long = createdAt,
)

@Entity(
    tableName = "transcript_segments",
    foreignKeys = [ForeignKey(entity = TranscriptEntity::class, parentColumns = ["id"], childColumns = ["transcript_id"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("transcript_id")],
)
data class TranscriptSegmentEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "transcript_id") val transcriptId: String,
    @ColumnInfo(name = "start_ms") val startMs: Long,
    @ColumnInfo(name = "end_ms") val endMs: Long,
    /** Counts from 0, in order of first appearance. */
    val speaker: Int,
    val text: String,
    val language: String = "",
    /** True when the user corrected the text by hand; re-running keeps nothing else, so this is informational. */
    val edited: Boolean = false,
)

@Entity(
    tableName = "transcript_speakers",
    primaryKeys = ["transcript_id", "speaker"],
    foreignKeys = [ForeignKey(entity = TranscriptEntity::class, parentColumns = ["id"], childColumns = ["transcript_id"], onDelete = ForeignKey.CASCADE)],
)
data class TranscriptSpeakerEntity(
    @ColumnInfo(name = "transcript_id") val transcriptId: String,
    val speaker: Int,
    val name: String,
)

@Entity(
    tableName = "transcript_marks",
    foreignKeys = [ForeignKey(entity = TranscriptEntity::class, parentColumns = ["id"], childColumns = ["transcript_id"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("transcript_id")],
)
data class TranscriptMarkEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "transcript_id") val transcriptId: String,
    @ColumnInfo(name = "at_ms") val atMs: Long,
    val label: String,
)

@Dao
interface TranscriptDao {
    @Query("SELECT * FROM transcripts WHERE profile_id = :profileId AND deleted_at IS NULL ORDER BY created_at DESC")
    fun observeForProfile(profileId: String): Flow<List<TranscriptEntity>>

    @Query("SELECT * FROM transcripts WHERE profile_id = :profileId AND deleted_at IS NOT NULL ORDER BY deleted_at DESC")
    fun observeDeleted(profileId: String): Flow<List<TranscriptEntity>>

    @Query("SELECT * FROM transcripts WHERE id = :id")
    fun observe(id: String): Flow<TranscriptEntity?>

    @Query("SELECT * FROM transcripts WHERE id = :id")
    suspend fun get(id: String): TranscriptEntity?

    @Query("SELECT * FROM transcripts WHERE status IN (:statuses) AND deleted_at IS NULL ORDER BY created_at")
    suspend fun withStatus(statuses: List<String>): List<TranscriptEntity>

    @Query("SELECT * FROM transcripts WHERE deleted_at IS NOT NULL AND deleted_at < :cutoff")
    suspend fun getDeletedBefore(cutoff: Long): List<TranscriptEntity>

    @Query("SELECT audio_file FROM transcripts")
    suspend fun allAudioFiles(): List<String>

    @Upsert
    suspend fun upsert(t: TranscriptEntity)

    @Query("UPDATE transcripts SET status = :status, progress = :progress, error = :error, modified_at = :now WHERE id = :id")
    suspend fun setStatus(id: String, status: String, progress: Float, error: String?, now: Long)

    @Query("UPDATE transcripts SET title = :title, modified_at = :now WHERE id = :id")
    suspend fun rename(id: String, title: String, now: Long)

    @Query("UPDATE transcripts SET deleted_at = :time, modified_at = :time WHERE id = :id")
    suspend fun softDelete(id: String, time: Long)

    @Query("UPDATE transcripts SET deleted_at = NULL, modified_at = :time WHERE id = :id")
    suspend fun restore(id: String, time: Long)

    @Query("DELETE FROM transcripts WHERE id = :id")
    suspend fun deleteForever(id: String)

    @Query("SELECT * FROM transcript_segments WHERE transcript_id = :id ORDER BY start_ms")
    fun observeSegments(id: String): Flow<List<TranscriptSegmentEntity>>

    @Query("SELECT * FROM transcript_segments WHERE transcript_id = :id ORDER BY start_ms")
    suspend fun segments(id: String): List<TranscriptSegmentEntity>

    @Query("UPDATE transcript_segments SET text = :text, edited = 1 WHERE id = :segmentId")
    suspend fun editSegment(segmentId: String, text: String)

    @Query("UPDATE transcript_segments SET speaker = :speaker WHERE id = :segmentId")
    suspend fun setSegmentSpeaker(segmentId: String, speaker: Int)

    @Query("UPDATE transcript_segments SET speaker = :into WHERE transcript_id = :id AND speaker = :from")
    suspend fun reassignSpeaker(id: String, from: Int, into: Int)

    @Query("DELETE FROM transcript_segments WHERE transcript_id = :id")
    suspend fun clearSegments(id: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSegments(segments: List<TranscriptSegmentEntity>)

    @Query("SELECT * FROM transcript_speakers WHERE transcript_id = :id ORDER BY speaker")
    fun observeSpeakers(id: String): Flow<List<TranscriptSpeakerEntity>>

    @Query("SELECT * FROM transcript_speakers WHERE transcript_id = :id ORDER BY speaker")
    suspend fun speakers(id: String): List<TranscriptSpeakerEntity>

    @Upsert
    suspend fun upsertSpeaker(s: TranscriptSpeakerEntity)

    @Query("DELETE FROM transcript_speakers WHERE transcript_id = :id AND speaker = :speaker")
    suspend fun deleteSpeaker(id: String, speaker: Int)

    @Query("DELETE FROM transcript_speakers WHERE transcript_id = :id")
    suspend fun clearSpeakers(id: String)

    @Query("SELECT * FROM transcript_marks WHERE transcript_id = :id ORDER BY at_ms")
    fun observeMarks(id: String): Flow<List<TranscriptMarkEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMark(mark: TranscriptMarkEntity)

    /** Replaces the result of a run in one transaction, so a transcript is never left half-written. */
    @Transaction
    suspend fun replaceResult(
        transcript: TranscriptEntity,
        segments: List<TranscriptSegmentEntity>,
        speakers: List<TranscriptSpeakerEntity>,
    ) {
        val keptNames = speakers(transcript.id).associate { it.speaker to it.name }
        clearSegments(transcript.id)
        clearSpeakers(transcript.id)
        insertSegments(segments)
        // A re-run keeps names the user already gave (by speaker number).
        speakers.forEach { upsertSpeaker(it.copy(name = keptNames[it.speaker] ?: it.name)) }
        upsert(transcript)
    }

    /** Moves every line of speaker [from] to [into] and renumbers the speakers above [from] down by one. */
    @Transaction
    suspend fun mergeSpeakers(id: String, from: Int, into: Int, now: Long) {
        if (from == into) return
        reassignSpeaker(id, from, into)
        deleteSpeaker(id, from)
        val rest = speakers(id).filter { it.speaker > from }.sortedBy { it.speaker }
        for (s in rest) {
            reassignSpeaker(id, s.speaker, s.speaker - 1)
            deleteSpeaker(id, s.speaker)
            upsertSpeaker(s.copy(speaker = s.speaker - 1))
        }
        val t = get(id) ?: return
        upsert(t.copy(speakerCount = (t.speakerCount - 1).coerceAtLeast(1), modifiedAt = now))
    }
}
