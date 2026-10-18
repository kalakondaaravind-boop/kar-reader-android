package com.kar.reader.di

import com.kar.reader.data.transfer.LanHostSession
import com.kar.reader.data.transfer.LanSync
import com.kar.reader.data.transfer.MergeReport
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Standard build: no network permission, so direct Wi-Fi sync is not available. Folder sync still works. */
@Module
@InstallIn(SingletonComponent::class)
object LanSyncModule {
    @Provides
    @Singleton
    fun provideLanSync(): LanSync = object : LanSync {
        override val isAvailable = false
        override suspend fun host(onStatus: (String) -> Unit): LanHostSession =
            throw UnsupportedOperationException("Wi-Fi sync is only in the KAR READER Sync build")
        override suspend fun join(address: String, code: String, onStatus: (String) -> Unit): MergeReport =
            throw UnsupportedOperationException("Wi-Fi sync is only in the KAR READER Sync build")
    }
}
