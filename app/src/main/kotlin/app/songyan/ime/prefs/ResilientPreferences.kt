package app.songyan.ime.prefs

import android.util.Log
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import java.io.IOException

/**
 * A preferences file that cannot be parsed is replaced by an empty one (every preference then reads
 * its default) instead of failing every read and write for good.
 */
fun preferencesCorruptionHandler(name: String): ReplaceFileCorruptionHandler<Preferences> =
    ReplaceFileCorruptionHandler { error: CorruptionException ->
        Log.w(TAG, "preferences '$name' were corrupt and were reset", error)
        emptyPreferences()
    }

/** Disk errors while reading preferences fall back to the defaults; anything else is a bug and propagates. */
fun Flow<Preferences>.orDefaultsOnIoError(): Flow<Preferences> =
    catch { error ->
        if (error !is IOException) throw error
        Log.w(TAG, "preferences could not be read; using defaults", error)
        emit(emptyPreferences())
    }

private const val TAG = "SongyanPrefs"

/**
 * For long-lived scopes that run fire-and-forget writes: an unexpected failure (disk full, a write
 * to a store that went away) is logged and the scope carries on; it must not take the process down.
 * Cancellation never reaches a handler.
 */
val LogAndContinue: CoroutineExceptionHandler =
    CoroutineExceptionHandler { _, error -> Log.e(TAG, "background task failed", error) }
