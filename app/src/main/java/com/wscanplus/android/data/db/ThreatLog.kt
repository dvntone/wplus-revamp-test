package com.wscanplus.android.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "threat_logs")
data class ThreatLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val attackType: String,
    val bssid: String,
    val ssid: String,
    val description: String,
    val severity: String,
)
