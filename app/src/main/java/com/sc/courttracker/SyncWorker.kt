package com.sc.courttracker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.work.*
import java.util.concurrent.TimeUnit

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (AppState.onBreak && AppState.breakUntil > System.currentTimeMillis()) return Result.success()

        val prefs = applicationContext.getSharedPreferences("sc_tracker", Context.MODE_PRIVATE)
        AppState.fromJson(prefs.getString("state", "{}") ?: "{}")

        val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            Scraper.fetch()
        } ?: return Result.retry()

        val ts = System.currentTimeMillis()
        result.courts.forEach { (courtNo, item) ->
            val d = AppState.liveData.getOrPut(courtNo) { CourtData(courtNo) }
            d.currentItem = item
            d.lastUpdated = ts
            d.readings.add(CourtReading(item, ts))
            if (d.readings.size > 30) d.readings.removeAt(0)
            result.sequences[courtNo]?.let { seq ->
                d.sequenceRaw = seq.raw
                d.sequenceRanges.clear()
                seq.ranges.forEach { (s, e) -> d.sequenceRanges.add(intArrayOf(s, e)) }
                d.sequenceRestOfMatters = seq.restOfMatters
            }
        }
        AppState.lastSyncTime = ts

        // Check urgency
        AppState.trackedCourts.forEach { tc ->
            val d = AppState.liveData[tc.courtNo] ?: return@forEach
            val current = d.currentItem ?: return@forEach
            val remaining = tc.myItem - current
            if (remaining in 0..3) {
                sendUrgentNotification(tc.courtNo, tc.myItem, current, remaining)
            }
        }

        // Status notification
        if (AppState.notifEnabled) {
            val lastNotif = prefs.getLong("last_notif", 0)
            val intervalMs = AppState.notifMinutes * 60000L
            if (ts - lastNotif >= intervalMs) {
                sendStatusNotification()
                prefs.edit().putLong("last_notif", ts).apply()
            }
        }

        prefs.edit().putString("state", AppState.toJson()).apply()
        return Result.success()
    }

    private fun sendUrgentNotification(courtNo: Int, myItem: Int, current: Int, remaining: Int) {
        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        ensureChannel(nm)
        val intent = PendingIntent.getActivity(applicationContext, 0,
            Intent(applicationContext, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val notif = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("⚖ Rush to Court $courtNo NOW!")
            .setContentText("Only $remaining item(s) before yours (item $myItem). Current: $current")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(intent)
            .build()
        nm.notify(courtNo * 100, notif)
    }

    private fun sendStatusNotification() {
        val lines = AppState.trackedCourts.map { tc ->
            val d = AppState.liveData[tc.courtNo]
            val current = d?.currentItem
            if (current == null) return@map "Court ${tc.courtNo}: no data"
            val eta = AppState.getETA(tc.courtNo, tc.myItem)
            "Court ${tc.courtNo}: item $current/${tc.myItem} — ETA ${AppState.fmtETA(eta)}"
        }
        if (lines.isEmpty()) return
        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        ensureChannel(nm)
        val notif = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentTitle("⚖ SC Tracker Update")
            .setStyle(NotificationCompat.BigTextStyle().bigText(lines.joinToString("\n")))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()
        nm.notify(999, notif)
    }

    private fun ensureChannel(nm: NotificationManager) {
        val channel = NotificationChannel(CHANNEL_ID, "SC Court Tracker", NotificationManager.IMPORTANCE_HIGH)
        nm.createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_ID = "sc_tracker"
        const val WORK_TAG = "sc_sync"

        fun schedule(context: Context, intervalMinutes: Int) {
            WorkManager.getInstance(context).cancelAllWorkByTag(WORK_TAG)
            if (intervalMinutes <= 0) return
            val request = PeriodicWorkRequestBuilder<SyncWorker>(
                intervalMinutes.toLong(), TimeUnit.MINUTES,
                5L, TimeUnit.MINUTES
            ).addTag(WORK_TAG).build()
            WorkManager.getInstance(context).enqueue(request)
        }

        fun scheduleOnce(context: Context) {
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .addTag(WORK_TAG + "_once").build()
            WorkManager.getInstance(context).enqueue(request)
        }
    }
}
