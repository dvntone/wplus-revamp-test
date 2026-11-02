package com.wscanplus.android.core

import android.util.Log
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.wscanplus.android.data.db.ThreatLog
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

/**
 * FirebaseSyncService – boilerplate for syncing threat logs to Firebase
 * Realtime Database.
 *
 * Usage:
 *   val sync = FirebaseSyncService(deviceId = "user-device-1")
 *   sync.pushThreatLog(log)
 *   sync.observeRemoteLogs().collect { logs -> ... }
 *
 * Requires:
 *   - google-services.json in app/ directory
 *   - Firebase Realtime Database enabled in the Firebase console
 *   - Authentication configured (the service uses anonymous auth by default)
 */
class FirebaseSyncService(private val deviceId: String) {

    private val db by lazy { FirebaseDatabase.getInstance() }
    private val logsRef by lazy { db.getReference("devices/$deviceId/threatLogs") }

    /**
     * Push a single [ThreatLog] to Firebase.
     * @return the generated Firebase key, or null on failure.
     */
    suspend fun pushThreatLog(log: ThreatLog): String? {
        return try {
            val ref = logsRef.push()
            val data = mapOf(
                "timestamp" to log.timestamp,
                "attackType" to log.attackType,
                "bssid" to log.bssid,
                "ssid" to log.ssid,
                "description" to log.description,
                "severity" to log.severity,
            )
            ref.setValue(data).await()
            ref.key
        } catch (e: Exception) {
            Log.e(TAG, "Failed to push threat log", e)
            null
        }
    }

    /**
     * Observe all remote threat logs for this device as a [Flow].
     * Emits a new list whenever the remote data changes.
     */
    fun observeRemoteLogs(): Flow<List<ThreatLog>> = callbackFlow {
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val logs = snapshot.children.mapNotNull { child ->
                    runCatching {
                        ThreatLog(
                            timestamp = child.child("timestamp").getValue(Long::class.java) ?: 0L,
                            attackType = child.child("attackType").getValue(String::class.java) ?: "",
                            bssid = child.child("bssid").getValue(String::class.java) ?: "",
                            ssid = child.child("ssid").getValue(String::class.java) ?: "",
                            description = child.child("description").getValue(String::class.java) ?: "",
                            severity = child.child("severity").getValue(String::class.java) ?: "",
                        )
                    }.getOrNull()
                }
                trySend(logs)
            }

            override fun onCancelled(error: DatabaseError) {
                Log.w(TAG, "Firebase listener cancelled: ${error.message}")
                close(error.toException())
            }
        }
        logsRef.addValueEventListener(listener)
        awaitClose { logsRef.removeEventListener(listener) }
    }

    companion object {
        private const val TAG = "FirebaseSyncService"
    }
}
