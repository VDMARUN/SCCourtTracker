package com.sc.courttracker

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: android.content.SharedPreferences
    private val handler = Handler(Looper.getMainLooper())
    private var syncRunnable: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences("sc_tracker", Context.MODE_PRIVATE)
        AppState.fromJson(prefs.getString("state", "{}") ?: "{}")

        setupNotificationChannel()
        requestNotificationPermission()
        setupUI()
        renderAll()
        scheduleAutoSync()

        // Initial sync
        lifecycleScope.launch { performSync() }
    }

    private fun setupNotificationChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(SyncWorker.CHANNEL_ID, "SC Court Tracker", NotificationManager.IMPORTANCE_HIGH)
        nm.createNotificationChannel(channel)
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
            }
        }
    }

    private fun setupUI() {
        // Court select
        val courtSpinner = findViewById<Spinner>(R.id.courtSpinner)
        val items = listOf("Select Court") + (1..17).map { "Court $it" }
        courtSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, items)

        // Add button
        findViewById<Button>(R.id.addBtn).setOnClickListener { addCourt() }
        findViewById<EditText>(R.id.itemInput).setOnEditorActionListener { _, _, _ -> addCourt(); true }

        // Sync button
        findViewById<Button>(R.id.syncBtn).setOnClickListener {
            lifecycleScope.launch { performSync() }
        }

        // Break presets
        findViewById<Button>(R.id.break210Btn).setOnClickListener { startBreakTill210() }
        findViewById<Button>(R.id.break30Btn).setOnClickListener { startBreak(30) }
        findViewById<Button>(R.id.break60Btn).setOnClickListener { startBreak(60) }
        findViewById<Button>(R.id.customBreakBtn).setOnClickListener {
            val mins = findViewById<EditText>(R.id.customMinsInput).text.toString().toIntOrNull()
            if (mins != null && mins > 0) startBreak(mins)
        }
        findViewById<Button>(R.id.resumeBtn).setOnClickListener { endBreak() }
        findViewById<Button>(R.id.resumeBannerBtn).setOnClickListener { endBreak() }

        // Notif toggle
        val notifToggle = findViewById<Button>(R.id.notifToggle)
        notifToggle.setOnClickListener {
            AppState.notifEnabled = !AppState.notifEnabled
            saveState()
            updateNotifToggle()
        }

        // Notif interval
        val notifSpinner = findViewById<Spinner>(R.id.notifIntervalSpinner)
        val notifItems = listOf("5 min", "10 min", "15 min", "30 min")
        val notifValues = listOf(5, 10, 15, 30)
        notifSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, notifItems)
        notifSpinner.setSelection(notifValues.indexOf(AppState.notifMinutes).coerceAtLeast(0))
        notifSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                AppState.notifMinutes = notifValues[pos]; saveState()
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        // Auto sync interval
        val autoSpinner = findViewById<Spinner>(R.id.autoIntervalSpinner)
        val autoItems = listOf("Off", "1 min", "2 min", "5 min")
        val autoValues = listOf(0, 1, 2, 5)
        autoSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, autoItems)
        autoSpinner.setSelection(autoValues.indexOf(AppState.autoMinutes).coerceAtLeast(0))
        autoSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                AppState.autoMinutes = autoValues[pos]; saveState(); scheduleAutoSync()
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }

    private suspend fun performSync() {
        if (AppState.onBreak) return
        runOnUiThread {
            findViewById<Button>(R.id.syncBtn).text = "⟳ Syncing..."
            findViewById<Button>(R.id.syncBtn).isEnabled = false
            findViewById<View>(R.id.statusDot).setBackgroundResource(R.drawable.dot_yellow)
        }

        val result = withContext(Dispatchers.IO) { Scraper.fetch() }
        val ts = System.currentTimeMillis()

        if (result != null) {
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
            saveState()
            runOnUiThread {
                findViewById<View>(R.id.statusDot).setBackgroundResource(R.drawable.dot_green)
                renderAll()
            }
        } else {
            runOnUiThread {
                findViewById<View>(R.id.statusDot).setBackgroundResource(R.drawable.dot_red)
                Toast.makeText(this, "Sync failed — check internet", Toast.LENGTH_SHORT).show()
            }
        }

        runOnUiThread {
            findViewById<Button>(R.id.syncBtn).text = "↻ Sync"
            findViewById<Button>(R.id.syncBtn).isEnabled = true
        }
    }

    private fun renderAll() {
        renderCards()
        renderBreakState()
        renderAlertBar()
        updateNotifToggle()
        updateLastSync()
    }

    private fun renderCards() {
        val container = findViewById<LinearLayout>(R.id.cardsContainer)
        container.removeAllViews()

        if (AppState.trackedCourts.isEmpty()) {
            findViewById<View>(R.id.emptyState).visibility = View.VISIBLE
            return
        }
        findViewById<View>(R.id.emptyState).visibility = View.GONE

        AppState.trackedCourts.forEach { tc ->
            val eta = if (AppState.onBreak) null else AppState.getETA(tc.courtNo, tc.myItem)
            val urg = if (AppState.onBreak) "break" else AppState.getUrgency(eta)
            val d = AppState.liveData[tc.courtNo]
            val paceResult = AppState.getPace(d?.readings ?: emptyList())
            val current = d?.currentItem
            val remaining = if (current != null) maxOf(0, tc.myItem - current) else null

            val cardView = LayoutInflater.from(this).inflate(R.layout.card_court, container, false)

            cardView.findViewById<TextView>(R.id.tvCourtName).text = "Court ${tc.courtNo}"
            cardView.findViewById<TextView>(R.id.tvRemaining).text =
                if (remaining != null) "$remaining items remaining" else "Waiting for data..."
            cardView.findViewById<TextView>(R.id.tvCurrent).text = current?.toString() ?: "—"
            cardView.findViewById<TextView>(R.id.tvMyItem).text = tc.myItem.toString()
            cardView.findViewById<TextView>(R.id.tvPace).text = AppState.fmtPace(paceResult.pace, paceResult.estimated)
            cardView.findViewById<TextView>(R.id.tvETA).text = if (AppState.onBreak) "⏸" else AppState.fmtETA(eta)

            // Sequence strip
            val seqStrip = cardView.findViewById<TextView>(R.id.tvSequence)
            if (d?.sequenceRaw != null) {
                seqStrip.text = "📋 ${d.sequenceRaw}"
                seqStrip.visibility = View.VISIBLE
            } else {
                seqStrip.visibility = View.GONE
            }

            // Last updated
            val updated = d?.lastUpdated
            cardView.findViewById<TextView>(R.id.tvUpdated).text =
                if (updated != null) "Updated: ${SimpleDateFormat("hh:mm:ss a", Locale.getDefault()).format(Date(updated))}"
                else "Updated: Never"

            // Badge
            val badge = cardView.findViewById<TextView>(R.id.tvBadge)
            when (urg) {
                "now" -> { badge.text = "GO NOW"; badge.setBackgroundResource(R.drawable.badge_urgent) }
                "urgent" -> { badge.text = "RUSH SOON"; badge.setBackgroundResource(R.drawable.badge_urgent) }
                "warning" -> { badge.text = "Getting close"; badge.setBackgroundResource(R.drawable.badge_warning) }
                "safe" -> { badge.text = "Safe"; badge.setBackgroundResource(R.drawable.badge_safe) }
                "break" -> { badge.text = "On break"; badge.setBackgroundResource(R.drawable.badge_idle) }
                else -> { badge.text = "Waiting"; badge.setBackgroundResource(R.drawable.badge_idle) }
            }

            // Card border color
            val cardBg = when (urg) {
                "now", "urgent" -> R.drawable.card_urgent
                "warning" -> R.drawable.card_warning
                else -> R.drawable.card_normal
            }
            cardView.setBackgroundResource(cardBg)

            // Remove button
            cardView.findViewById<Button>(R.id.removeBtn).setOnClickListener {
                AppState.trackedCourts.removeIf { it.courtNo == tc.courtNo }
                saveState(); renderAll()
            }

            container.addView(cardView)
        }
    }

    private fun renderBreakState() {
        val banner = findViewById<View>(R.id.breakBanner)
        val resumeBtn = findViewById<Button>(R.id.resumeBtn)
        if (AppState.onBreak && AppState.breakUntil > System.currentTimeMillis()) {
            banner.visibility = View.VISIBLE
            val t = SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date(AppState.breakUntil))
            findViewById<TextView>(R.id.tvBreakText).text = "⏸ Break — resumes at $t"
            resumeBtn.visibility = View.VISIBLE
        } else {
            banner.visibility = View.GONE
            resumeBtn.visibility = View.GONE
        }
    }

    private fun renderAlertBar() {
        val bar = findViewById<View>(R.id.alertBar)
        val txt = findViewById<TextView>(R.id.tvAlert)
        val urgent = AppState.trackedCourts.filter { tc ->
            val eta = AppState.getETA(tc.courtNo, tc.myItem)
            !AppState.onBreak && eta != null && eta < 5
        }
        if (urgent.isNotEmpty()) {
            txt.text = "🚨 RUSH NOW → " + urgent.joinToString(", ") { "Court ${it.courtNo} (item ${it.myItem})" }
            bar.visibility = View.VISIBLE
        } else {
            bar.visibility = View.GONE
        }
    }

    private fun updateNotifToggle() {
        val btn = findViewById<Button>(R.id.notifToggle)
        btn.text = if (AppState.notifEnabled) "On ✓" else "Off"
        btn.setBackgroundResource(if (AppState.notifEnabled) R.drawable.btn_green else R.drawable.btn_normal)
    }

    private fun updateLastSync() {
        val tv = findViewById<TextView>(R.id.tvLastSync)
        tv.text = if (AppState.lastSyncTime != null)
            "Last synced: ${SimpleDateFormat("hh:mm:ss a", Locale.getDefault()).format(Date(AppState.lastSyncTime!!))}"
        else "Not synced yet — tap ↻ Sync"
    }

    private fun addCourt() {
        val spinner = findViewById<Spinner>(R.id.courtSpinner)
        val itemInput = findViewById<EditText>(R.id.itemInput)
        val hintTv = findViewById<TextView>(R.id.tvAddHint)

        val pos = spinner.selectedItemPosition
        if (pos == 0) { hintTv.text = "Please select a court"; return }
        val courtNo = pos
        val myItem = itemInput.text.toString().toIntOrNull()
        if (myItem == null || myItem < 1) { hintTv.text = "Enter a valid item number"; return }
        if (AppState.trackedCourts.any { it.courtNo == courtNo }) { hintTv.text = "Court $courtNo already tracked"; return }

        AppState.trackedCourts.add(TrackedCourt(courtNo, myItem))
        itemInput.text.clear()
        hintTv.text = "Court $courtNo added!"
        saveState()
        renderAll()
    }

    private fun startBreak(mins: Int) {
        AppState.onBreak = true
        AppState.breakUntil = System.currentTimeMillis() + mins * 60000L
        saveState()
        renderAll()
        handler.postDelayed({ endBreak() }, mins * 60000L)
        Toast.makeText(this, "Break started — ${mins}min", Toast.LENGTH_SHORT).show()
    }

    private fun startBreakTill210() {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 14)
        cal.set(Calendar.MINUTE, 10)
        cal.set(Calendar.SECOND, 0)
        if (cal.timeInMillis <= System.currentTimeMillis()) cal.add(Calendar.DAY_OF_YEAR, 1)
        val mins = ((cal.timeInMillis - System.currentTimeMillis()) / 60000).toInt()
        startBreak(mins)
    }

    private fun endBreak() {
        AppState.onBreak = false
        AppState.breakUntil = 0
        saveState()
        renderAll()
        Toast.makeText(this, "Tracking resumed!", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch { performSync() }
    }

    private fun scheduleAutoSync() {
        syncRunnable?.let { handler.removeCallbacks(it) }
        if (AppState.autoMinutes <= 0) return
        val interval = AppState.autoMinutes * 60000L
        syncRunnable = object : Runnable {
            override fun run() {
                if (!AppState.onBreak) lifecycleScope.launch { performSync() }
                handler.postDelayed(this, interval)
            }
        }
        handler.postDelayed(syncRunnable!!, interval)
        SyncWorker.schedule(this, AppState.autoMinutes)
    }

    private fun saveState() {
        prefs.edit().putString("state", AppState.toJson()).apply()
    }

    override fun onPause() { super.onPause(); saveState() }
    override fun onDestroy() { super.onDestroy(); syncRunnable?.let { handler.removeCallbacks(it) } }
}
