package com.wscanplus.android.core

import android.content.Context
import android.util.Log
import com.wscanplus.android.data.db.ThreatLog
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * DataExporter – serialises threat logs and raw capture data to standard
 * interchange formats (.csv and .pcap) compatible with Wireshark / TShark.
 */
object DataExporter {

    private const val TAG = "DataExporter"
    private val CSV_DATE: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.of("UTC"))

    // ── CSV Export ────────────────────────────────────────────────────────────

    /**
     * Write a list of [ThreatLog] records to a CSV file in the app's
     * external files directory.
     *
     * @param context  Android context used to resolve the export directory.
     * @param logs     records to export.
     * @param fileName target file name (default: wscanplus_<timestamp>.csv).
     * @return the created [File], or null on failure.
     */
    fun exportToCsv(
        context: Context,
        logs: List<ThreatLog>,
        fileName: String = "wscanplus_${System.currentTimeMillis()}.csv",
    ): File? {
        val dir = context.getExternalFilesDir("exports") ?: return null
        dir.mkdirs()
        val file = File(dir, fileName)
        return try {
            file.bufferedWriter(Charsets.UTF_8).use { writer ->
                writer.append("timestamp,attack_type,bssid,ssid,severity,description\n")
                for (log in logs) {
                    writer.append(
                        "${CSV_DATE.format(Instant.ofEpochMilli(log.timestamp))}," +
                            "${log.attackType.csvEscape()}," +
                            "${log.bssid.csvEscape()}," +
                            "${log.ssid.csvEscape()}," +
                            "${log.severity.csvEscape()}," +
                            "${log.description.csvEscape()}\n"
                    )
                }
            }
            file
        } catch (e: IOException) {
            Log.e(TAG, "CSV export failed", e)
            null
        }
    }

    // ── PCAP Export ───────────────────────────────────────────────────────────

    /**
     * Write raw packet bytes to a .pcap file (libpcap format, link type 105 =
     * IEEE 802.11 without FCS).
     *
     * @param context  Android context used to resolve the export directory.
     * @param packets  list of raw 802.11 frame byte arrays with their capture
     *                 timestamps (epoch seconds, microseconds).
     * @param fileName target file name.
     * @return the created [File], or null on failure.
     */
    fun exportToPcap(
        context: Context,
        packets: List<RawPacket>,
        fileName: String = "wscanplus_${System.currentTimeMillis()}.pcap",
    ): File? {
        val dir = context.getExternalFilesDir("exports") ?: return null
        dir.mkdirs()
        val file = File(dir, fileName)
        return try {
            file.outputStream().use { out ->
                // Global PCAP header (24 bytes, little-endian)
                val globalHeader = ByteBuffer.allocate(24).apply {
                    order(ByteOrder.LITTLE_ENDIAN)
                    putInt(0xA1B2C3D4.toInt()) // magic number
                    putShort(2)                // major version
                    putShort(4)                // minor version
                    putInt(0)                  // thiszone (UTC)
                    putInt(0)                  // sigfigs
                    putInt(65535)              // snaplen
                    putInt(105)                // network (IEEE 802.11)
                }
                out.write(globalHeader.array())

                for (pkt in packets) {
                    val recHeader = ByteBuffer.allocate(16).apply {
                        order(ByteOrder.LITTLE_ENDIAN)
                        putInt(pkt.tsSec.toInt())
                        putInt(pkt.tsUsec.toInt())
                        putInt(pkt.data.size)
                        putInt(pkt.data.size)
                    }
                    out.write(recHeader.array())
                    out.write(pkt.data)
                }
            }
            file
        } catch (e: IOException) {
            Log.e(TAG, "PCAP export failed", e)
            null
        }
    }

    data class RawPacket(
        val tsSec: Long,
        val tsUsec: Long,
        val data: ByteArray,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is RawPacket) return false
            return tsSec == other.tsSec && tsUsec == other.tsUsec && data.contentEquals(other.data)
        }

        override fun hashCode(): Int {
            var result = tsSec.hashCode()
            result = 31 * result + tsUsec.hashCode()
            result = 31 * result + data.contentHashCode()
            return result
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun String.csvEscape(): String {
        val needsQuotes = contains(',') || contains('"') || contains('\n')
        val escaped = replace("\"", "\"\"")
        return if (needsQuotes) "\"$escaped\"" else escaped
    }
}
