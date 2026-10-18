package com.kar.reader.data.repo

import android.content.Context
import androidx.glance.appwidget.updateAll
import com.kar.reader.di.AppScope
import com.kar.reader.widget.ContinueReadingWidget
import com.kar.reader.widget.FlashcardsDueWidget
import com.kar.reader.widget.ReadingGoalWidget
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** Refreshes home-screen widgets after data changes, at most once every two seconds. */
@Singleton
class WidgetRefresher @Inject constructor(
    @ApplicationContext private val context: Context,
    @AppScope private val scope: CoroutineScope,
) {
    private var pending: Job? = null

    fun requestRefresh() {
        if (pending?.isActive == true) return
        pending = scope.launch {
            delay(2_000)
            runCatching {
                ContinueReadingWidget().updateAll(context)
                ReadingGoalWidget().updateAll(context)
                FlashcardsDueWidget().updateAll(context)
            }
        }
    }
}
