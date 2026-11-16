package com.kar.reader.data.db

import android.app.Application
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Upgrading from the first released database keeps every row and adds the transcript tables. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class MigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), KarDatabase::class.java)

    @Test
    fun migrate1To2KeepsUserData() {
        helper.createDatabase(DB, 1).apply {
            execSQL("INSERT INTO profiles (id, name, color_index, pin_hash, created_at) VALUES ('p1', 'Asha', 2, NULL, 1)")
            execSQL("INSERT INTO notes (id, profile_id, book_id, highlight_id, position, title, body, created_at, deleted_at, modified_at) VALUES ('n1', 'p1', NULL, NULL, NULL, 'Kept', 'Body text', 5, NULL, 5)")
            close()
        }
        val db = helper.runMigrationsAndValidate(DB, 2, true)
        db.query("SELECT name FROM profiles WHERE id = 'p1'").use { c -> assertTrue(c.moveToFirst()); assertEquals("Asha", c.getString(0)) }
        db.query("SELECT body FROM notes WHERE id = 'n1'").use { c -> assertTrue(c.moveToFirst()); assertEquals("Body text", c.getString(0)) }
        db.execSQL(
            "INSERT INTO transcripts (id, profile_id, title, book_id, source, source_name, audio_file, duration_ms, language_choice, detected_languages, " +
                "separate_speakers, expected_speakers, speaker_count, status, progress, error, created_at, processed_at, deleted_at, modified_at) " +
                "VALUES ('t1', 'p1', 'Lecture', NULL, 'RECORDED', NULL, 't1.wav', 1000, '', '', 1, 0, 0, 'DONE', 1.0, NULL, 1, NULL, NULL, 1)",
        )
        db.query("SELECT COUNT(*) FROM transcripts").use { c -> c.moveToFirst(); assertEquals(1, c.getInt(0)) }
        db.close()
    }

    private companion object { const val DB = "migration-test.db" }
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class TranscriptDaoTest {
    private lateinit var db: KarDatabase
    private lateinit var dao: TranscriptDao

    @Before
    fun open() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), KarDatabase::class.java).allowMainThreadQueries().build()
        dao = db.transcriptDao()
    }

    @After
    fun close() = db.close()

    private fun transcript(id: String = "t") = TranscriptEntity(
        id = id, profileId = "p", title = "Seminar", source = "RECORDED", audioFile = "$id.wav", status = TranscriptStatus.CANCELLED, createdAt = 1,
    )

    private fun seg(id: String, start: Long, speaker: Int) = TranscriptSegmentEntity(id, "t", start, start + 900, speaker, "line $id")

    @Test
    fun rerunKeepsSpeakerNamesByNumber() = runBlocking {
        val t = transcript()
        dao.upsert(t)
        dao.replaceResult(t.copy(status = TranscriptStatus.DONE), listOf(seg("a", 0, 0), seg("b", 1000, 1)), listOf(TranscriptSpeakerEntity("t", 0, "Speaker 1"), TranscriptSpeakerEntity("t", 1, "Speaker 2")))
        dao.upsertSpeaker(TranscriptSpeakerEntity("t", 1, "Prof. Mehta"))
        dao.replaceResult(t.copy(status = TranscriptStatus.DONE), listOf(seg("c", 0, 1)), listOf(TranscriptSpeakerEntity("t", 0, "Speaker 1"), TranscriptSpeakerEntity("t", 1, "Speaker 2")))
        assertEquals(listOf("c"), dao.segments("t").map { it.id })
        assertEquals("Prof. Mehta", dao.speakers("t").first { it.speaker == 1 }.name)
        assertEquals(TranscriptStatus.DONE, dao.get("t")!!.status)
    }

    @Test
    fun mergingRenumbersTheRest() = runBlocking {
        val t = transcript().copy(speakerCount = 3)
        dao.upsert(t)
        dao.replaceResult(
            t,
            listOf(seg("a", 0, 0), seg("b", 1000, 1), seg("c", 2000, 2)),
            listOf(TranscriptSpeakerEntity("t", 0, "A"), TranscriptSpeakerEntity("t", 1, "B"), TranscriptSpeakerEntity("t", 2, "C")),
        )
        dao.mergeSpeakers("t", from = 1, into = 0, now = 5)
        assertEquals(listOf(0, 0, 1), dao.segments("t").map { it.speaker })
        assertEquals(listOf(0 to "A", 1 to "C"), dao.speakers("t").map { it.speaker to it.name })
        assertEquals(2, dao.get("t")!!.speakerCount)
    }

    @Test
    fun deletingTranscriptRemovesItsLines() = runBlocking {
        dao.upsert(transcript())
        dao.insertSegments(listOf(seg("a", 0, 0)))
        dao.insertMark(TranscriptMarkEntity("m", "t", 10, "Here"))
        dao.deleteForever("t")
        assertTrue(dao.segments("t").isEmpty())
    }

    @Test
    fun softDeleteHidesFromListAndShowsInBin() = runBlocking {
        dao.upsert(transcript())
        dao.softDelete("t", 100)
        assertEquals(1, dao.getDeletedBefore(200).size)
        assertEquals(0, dao.getDeletedBefore(50).size)
    }
}
