package com.kar.reader.data.repo

import com.kar.reader.core.domain.PinHasher
import com.kar.reader.data.db.ProfileDao
import com.kar.reader.data.db.ProfileEntity
import com.kar.reader.data.prefs.AppPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Family profiles. Every install has at least one profile; the first one is created on first launch.
 * Profiles live only on this device.
 */
@Singleton
class ProfileRepository @Inject constructor(
    private val dao: ProfileDao,
    private val prefs: AppPreferences,
) {
    private val initLock = Mutex()

    val profiles: Flow<List<ProfileEntity>> = dao.observeAll()

    /** The active profile; emits once a profile exists. */
    val current: Flow<ProfileEntity> = combine(profiles, prefs.currentProfileId) { list, id ->
        list.firstOrNull { it.id == id } ?: list.firstOrNull()
    }.filterNotNull().distinctUntilChanged()

    val currentId: Flow<String> = current.map { it.id }.distinctUntilChanged()

    suspend fun currentIdNow(): String {
        ensureDefaultProfile()
        return currentId.first()
    }

    suspend fun ensureDefaultProfile() = withContext(Dispatchers.IO) {
        initLock.withLock {
            if (dao.getAll().isEmpty()) {
                val profile = ProfileEntity(UUID.randomUUID().toString(), "Me", 0, null, System.currentTimeMillis())
                dao.insert(profile)
                prefs.setCurrentProfile(profile.id)
            }
        }
    }

    suspend fun create(name: String, colorIndex: Int, pin: String?): ProfileEntity {
        val profile = ProfileEntity(
            id = UUID.randomUUID().toString(),
            name = name.trim().ifEmpty { "Reader" }.take(30),
            colorIndex = colorIndex,
            pinHash = pin?.takeIf { it.length >= 4 }?.let { withContext(Dispatchers.Default) { PinHasher.hash(it) } },
            createdAt = System.currentTimeMillis(),
        )
        dao.insert(profile)
        return profile
    }

    suspend fun rename(profile: ProfileEntity, name: String) = dao.update(profile.copy(name = name.trim().ifEmpty { profile.name }.take(30)))

    suspend fun setPin(profile: ProfileEntity, pin: String?) {
        val hash = pin?.takeIf { it.length >= 4 }?.let { withContext(Dispatchers.Default) { PinHasher.hash(it) } }
        dao.update(profile.copy(pinHash = hash))
    }

    suspend fun verifyPin(profile: ProfileEntity, pin: String): Boolean =
        profile.pinHash == null || withContext(Dispatchers.Default) { PinHasher.verify(pin, profile.pinHash) }

    suspend fun switchTo(profile: ProfileEntity) = prefs.setCurrentProfile(profile.id)

    /** Deleting a profile removes only that profile row; its data stays until a later cleanup, so it is never lost by accident. */
    suspend fun delete(profile: ProfileEntity) {
        val all = dao.getAll()
        if (all.size <= 1) return
        dao.delete(profile.id)
        if (prefs.currentProfileId.first() == profile.id) prefs.setCurrentProfile(all.first { it.id != profile.id }.id)
    }
}
