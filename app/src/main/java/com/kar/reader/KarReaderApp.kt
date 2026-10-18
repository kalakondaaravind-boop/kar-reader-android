package com.kar.reader

import android.app.Application
import com.kar.reader.data.files.DocumentStore
import com.kar.reader.data.repo.ProfileRepository
import com.kar.reader.data.repo.TrashRepository
import com.kar.reader.di.AppScope
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class KarReaderApp : Application() {

    @Inject lateinit var profiles: ProfileRepository
    @Inject lateinit var store: DocumentStore
    @Inject lateinit var trash: TrashRepository
    @Inject @AppScope lateinit var scope: CoroutineScope

    override fun onCreate() {
        super.onCreate()
        scope.launch(Dispatchers.IO) {
            profiles.ensureDefaultProfile()
            // Imports interrupted by process death leave files only in staging; remove them.
            store.cleanInterruptedStaging()
            // Items in the recycle bin for more than 30 days are removed for good.
            runCatching { trash.purgeExpired() }
        }
    }
}
