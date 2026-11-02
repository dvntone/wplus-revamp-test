package com.wscanplus.android.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ThreatLogDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(log: ThreatLog): Long

    @Query("SELECT * FROM threat_logs ORDER BY timestamp DESC")
    fun observeAll(): Flow<List<ThreatLog>>

    @Query("SELECT * FROM threat_logs ORDER BY timestamp DESC LIMIT :limit")
    fun observeRecent(limit: Int = 10): Flow<List<ThreatLog>>

    @Query("SELECT * FROM threat_logs ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecent(limit: Int = 50): List<ThreatLog>

    @Query("DELETE FROM threat_logs")
    suspend fun deleteAll()
}
