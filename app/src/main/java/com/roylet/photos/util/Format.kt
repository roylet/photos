package com.roylet.photos.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToLong

object Format {
    fun bytes(bytes: Long): String {
        val b = bytes.toDouble()
        return when {
            b >= 1e12 -> "%.2f TB".format(b / 1e12)
            b >= 1e9 -> "%.2f GB".format(b / 1e9)
            b >= 1e6 -> "%.1f MB".format(b / 1e6)
            b >= 1e3 -> "%.0f KB".format(b / 1e3)
            else -> "$bytes B"
        }
    }

    fun speed(bytesPerSecond: Double): String = when {
        bytesPerSecond >= 1e6 -> "%.1f MB/s".format(bytesPerSecond / 1e6)
        bytesPerSecond >= 1e3 -> "%.0f KB/s".format(bytesPerSecond / 1e3)
        else -> "%.0f B/s".format(bytesPerSecond)
    }

    /** 75 -> "1m 15s", 4000 -> "1h 6m". */
    fun duration(seconds: Long): String {
        val s = seconds.coerceAtLeast(0)
        return when {
            s >= 3600 -> "${s / 3600}h ${(s % 3600) / 60}m"
            s >= 60 -> "${s / 60}m ${s % 60}s"
            else -> "${s}s"
        }
    }

    fun durationMillis(ms: Long) = duration((ms / 1000.0).roundToLong())

    fun day(epochMillis: Long): String = SimpleDateFormat("EEE d MMM yyyy", Locale.getDefault()).format(Date(epochMillis))

    fun shortDate(epochMillis: Long): String = SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date(epochMillis))

    fun dateTime(epochMillis: Long): String = SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()).format(Date(epochMillis))

    fun plural(n: Int, one: String, many: String = one + "s") = "$n ${if (n == 1) one else many}"
}
