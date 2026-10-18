package com.kar.reader.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.kar.reader.MainActivity
import com.kar.reader.core.domain.ReadingStats
import com.kar.reader.core.domain.SessionFact
import com.kar.reader.data.db.KarDatabase
import com.kar.reader.data.db.displayAuthor
import com.kar.reader.data.db.displayTitle
import com.kar.reader.data.prefs.AppPreferences
import com.kar.reader.data.repo.ProfileRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.time.ZoneId

@EntryPoint
@InstallIn(SingletonComponent::class)
interface WidgetEntryPoint {
    fun profiles(): ProfileRepository
    fun database(): KarDatabase
    fun preferences(): AppPreferences
}

internal fun entry(context: Context): WidgetEntryPoint =
    EntryPointAccessors.fromApplication(context.applicationContext, WidgetEntryPoint::class.java)

/** Intent extras understood by MainActivity for widget deep links. */
object DeepLinks {
    const val EXTRA_OPEN_BOOK = "com.kar.reader.extra.OPEN_BOOK"
    const val EXTRA_DESTINATION = "com.kar.reader.extra.DESTINATION"
    const val DEST_REVIEW = "review"
    const val DEST_FOCUS = "focus"
    const val DEST_STATS = "stats"

    fun intent(context: Context, bookId: String? = null, destination: String? = null): Intent =
        Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            bookId?.let { putExtra(EXTRA_OPEN_BOOK, it) }
            destination?.let { putExtra(EXTRA_DESTINATION, it) }
        }
}

private val Navy = Color(0xFF1B3557)
private val Gold = Color(0xFFE0A33A)
private val Cream = Color(0xFFF5EBDC)
private val Mist = Color(0xFFDCE5F0)

@Composable
private fun Label(text: String, size: Int, color: Color, bold: Boolean = false, maxLines: Int = 1) {
    Text(
        text = text,
        maxLines = maxLines,
        style = TextStyle(color = ColorProvider(color), fontSize = size.sp, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal),
    )
}

// ---- Continue reading ----------------------------------------------------------------------------

private data class ContinueData(val bookId: String?, val title: String, val subtitle: String, val progress: Float)

class ContinueReadingWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val data = load(context)
        provideContent { Content(context, data) }
    }

    private suspend fun load(context: Context): ContinueData {
        val e = entry(context)
        val db = e.database()
        val profileId = e.profiles().currentIdNow()
        val hidePrivate = e.preferences().settings.first().hidePrivateFromRecent
        val state = db.bookStateDao().recent(profileId).firstOrNull { st ->
            val book = db.bookDao().get(st.bookId)
            book != null && book.deletedAt == null && !(hidePrivate && st.isPrivate)
        } ?: return ContinueData(null, "Nothing open yet", "Import a book to start reading", 0f)
        val book = db.bookDao().get(state.bookId)!!
        val progress = db.progressDao().get(profileId, book.id)?.totalProgression ?: 0f
        return ContinueData(book.id, book.displayTitle, book.displayAuthor ?: book.format, progress)
    }

    @Composable
    private fun Content(context: Context, d: ContinueData) {
        Column(
            modifier = GlanceModifier.fillMaxSize().cornerRadius(24.dp).background(Navy).padding(16.dp)
                .clickable(actionStartActivity(DeepLinks.intent(context, bookId = d.bookId))),
        ) {
            Label("KAR READER · CONTINUE", 11, Gold, bold = true)
            Spacer(GlanceModifier.height(6.dp))
            Label(d.title, 18, Color.White, bold = true, maxLines = 2)
            Label(d.subtitle, 13, Mist)
            Spacer(GlanceModifier.height(10.dp))
            if (d.bookId != null) {
                LinearProgressIndicator(
                    progress = d.progress,
                    modifier = GlanceModifier.fillMaxWidth().height(6.dp),
                    color = ColorProvider(Gold),
                    backgroundColor = ColorProvider(Color(0x55000000)),
                )
                Spacer(GlanceModifier.height(6.dp))
                Label("${(d.progress * 100).toInt()}% · Tap to resume", 12, Cream)
            }
        }
    }
}

class ContinueReadingWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ContinueReadingWidget()
}

// ---- Reading goal --------------------------------------------------------------------------------

private data class GoalData(val minutes: Int, val goal: Int, val streak: Int)

class ReadingGoalWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val e = entry(context)
        val profileId = e.profiles().currentIdNow()
        val zone = ZoneId.systemDefault()
        val facts = e.database().sessionDao().getForProfile(profileId).map { SessionFact(it.bookId.orEmpty(), it.startedAt, it.durationMs, it.focus) }
        val today = LocalDate.now(zone)
        val data = GoalData(
            minutes = ReadingStats.minutesOn(today, facts, zone),
            goal = e.preferences().dailyGoalMinutes(profileId).first(),
            streak = ReadingStats.currentStreak(facts, today, zone),
        )
        provideContent { Content(context, data) }
    }

    @Composable
    private fun Content(context: Context, d: GoalData) {
        Column(
            modifier = GlanceModifier.fillMaxSize().cornerRadius(24.dp).background(Navy).padding(14.dp)
                .clickable(actionStartActivity(DeepLinks.intent(context, destination = DeepLinks.DEST_STATS))),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Label("TODAY'S GOAL", 11, Gold, bold = true)
            Spacer(GlanceModifier.height(4.dp))
            Label("${d.minutes} / ${d.goal} min", 20, Color.White, bold = true)
            Spacer(GlanceModifier.height(8.dp))
            LinearProgressIndicator(
                progress = (d.minutes.toFloat() / d.goal.coerceAtLeast(1)).coerceIn(0f, 1f),
                modifier = GlanceModifier.fillMaxWidth().height(6.dp),
                color = ColorProvider(Gold),
                backgroundColor = ColorProvider(Color(0x55000000)),
            )
            Spacer(GlanceModifier.height(6.dp))
            Label(if (d.streak == 1) "1-day streak" else "${d.streak}-day streak", 12, Cream)
        }
    }
}

class ReadingGoalWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ReadingGoalWidget()
}

// ---- Flashcards due ------------------------------------------------------------------------------

class FlashcardsDueWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val e = entry(context)
        val due = e.database().flashcardDao().countDue(e.profiles().currentIdNow(), System.currentTimeMillis())
        provideContent {
            Row(
                modifier = GlanceModifier.fillMaxSize().cornerRadius(24.dp).background(Navy).padding(14.dp)
                    .clickable(actionStartActivity(DeepLinks.intent(context, destination = DeepLinks.DEST_REVIEW))),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Label(due.toString(), 28, Gold, bold = true)
                Spacer(GlanceModifier.width(12.dp))
                Column {
                    Label("Flashcards due", 15, Color.White, bold = true)
                    Label(if (due == 0) "All caught up" else "Tap to review", 12, Mist)
                }
            }
        }
    }
}

class FlashcardsDueWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = FlashcardsDueWidget()
}
