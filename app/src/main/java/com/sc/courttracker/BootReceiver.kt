package com.sc.courttracker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val prefs = context.getSharedPreferences("sc_tracker", Context.MODE_PRIVATE)
            AppState.fromJson(prefs.getString("state", "{}") ?: "{}")
            if (AppState.autoMinutes > 0) {
                SyncWorker.schedule(context, AppState.autoMinutes)
            }
        }
    }
}
