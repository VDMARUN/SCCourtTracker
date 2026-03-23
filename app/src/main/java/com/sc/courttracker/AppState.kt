package com.sc.courttracker

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

data class CourtReading(val item: Int, val timestamp: Long)

data class CourtData(
    val courtNo: Int,
    var currentItem: Int? = null,
    var lastUpdated: Long? = null,
    val readings: MutableList<CourtReading> = mutableListOf(),
    var sequenceRaw: String? = null,
    val sequenceRanges: MutableList<IntArray> = mutableListOf(),
    var sequenceRestOfMatters: Boolean = false
)

data class TrackedCourt(val courtNo: Int, val myItem: Int)

data class PaceResult(val pace: Double?, val estimated: Boolean)

object AppState {
    val trackedCourts: MutableList<TrackedCourt> = mutableListOf()
    val liveData: MutableMap<Int, CourtData> = mutableMapOf()
    var onBreak: Boolean = false
    var breakUntil: Long = 0L
    var notifEnabled: Boolean = false
    var notifMinutes: Int = 10
    var autoMinutes: Int = 1
    var lastSyncTime: Long? = null

    fun getPace(readings: List<CourtReading>): PaceResult {
        if (readings.size < 2) return PaceResult(null, false)
        for (end in readings.indices.reversed()) {
            for (start in 0 until end) {
                val dtMin = (readings[end].timestamp - readings[start].timestamp) / 60000.0
                val dItems = readings[end].item - readings[start].item
                if (dtMin >= 0.1 && dItems > 0) return PaceResult(dItems / dtMin, false)
            }
        }
        val dtMin = (readings.last().timestamp - readings.first().timestamp) / 60000.0
        if (dtMin >= 2) return PaceResult(1.0 / dtMin, true)
        return PaceResult(null, false)
    }

    fun getETA(courtNo: Int, myItem: Int): Double? {
        val d = liveData[courtNo] ?: return null
        val current = d.currentItem ?: return null
        if (myItem <= current) return 0.0
        val remaining: Int = if (d.sequenceRanges.isNotEmpty()) {
            var r = 0
            for (range in d.sequenceRanges) {
                val s = range[0]; val e = range[1]
                if (e <= current) continue
                val from = maxOf(s, current + 1)
                val to = minOf(e, myItem)
                if (from <= to) r += to - from + 1
            }
            val inRanges = d.sequenceRanges.any { myItem in it[0]..it[1] }
            if (!inRanges && d.sequenceRestOfMatters) myItem - current else r
        } else myItem - current
        if (remaining <= 0) return 0.0
        val pace = getPace(d.readings).pace ?: return null
        return remaining / pace
    }

    fun getUrgency(eta: Double?): String = when {
        eta == null -> "idle"
        eta <= 0 -> "now"
        eta < 5 -> "urgent"
        eta < 15 -> "warning"
        else -> "safe"
    }

    fun fmtETA(eta: Double?): String = when {
        eta == null -> "—"
        eta <= 0 -> "NOW"
        eta < 1 -> "<1m"
        eta < 60 -> "${eta.toInt()}m"
        else -> "${(eta / 60).toInt()}h${(eta % 60).toInt()}m"
    }

    fun fmtPace(pace: Double?, estimated: Boolean): String {
        if (pace == null) return "—"
        val p = if (estimated) "~" else ""
        return if (pace < 1) "$p${(pace * 60).toInt()}/hr" else "$p${"%.1f".format(pace)}/m"
    }

    fun toJson(): String {
        return try {
            val root = JSONObject()
            val tcArr = JSONArray()
            trackedCourts.forEach { tc ->
                tcArr.put(JSONObject().apply { put("courtNo", tc.courtNo); put("myItem", tc.myItem) })
            }
            root.put("trackedCourts", tcArr)

            val ldObj = JSONObject()
            liveData.forEach { (k, d) ->
                val dObj = JSONObject()
                dObj.put("courtNo", d.courtNo)
                d.currentItem?.let { dObj.put("currentItem", it) }
                d.lastUpdated?.let { dObj.put("lastUpdated", it) }
                val rArr = JSONArray()
                d.readings.forEach { r -> rArr.put(JSONObject().apply { put("item", r.item); put("t", r.timestamp) }) }
                dObj.put("readings", rArr)
                d.sequenceRaw?.let { dObj.put("sequenceRaw", it) }
                dObj.put("sequenceRestOfMatters", d.sequenceRestOfMatters)
                val srArr = JSONArray()
                d.sequenceRanges.forEach { r -> srArr.put(JSONArray().apply { put(r[0]); put(r[1]) }) }
                dObj.put("sequenceRanges", srArr)
                ldObj.put(k.toString(), dObj)
            }
            root.put("liveData", ldObj)
            root.put("onBreak", onBreak)
            root.put("breakUntil", breakUntil)
            root.put("notifEnabled", notifEnabled)
            root.put("notifMinutes", notifMinutes)
            root.put("autoMinutes", autoMinutes)
            lastSyncTime?.let { root.put("lastSyncTime", it) }
            root.toString()
        } catch (e: Exception) { "{}" }
    }

    fun fromJson(json: String) {
        try {
            val root = JSONObject(json)
            trackedCourts.clear()
            val tcArr = root.optJSONArray("trackedCourts") ?: JSONArray()
            for (i in 0 until tcArr.length()) {
                val o = tcArr.getJSONObject(i)
                trackedCourts.add(TrackedCourt(o.getInt("courtNo"), o.getInt("myItem")))
            }
            liveData.clear()
            val ldObj = root.optJSONObject("liveData") ?: JSONObject()
            ldObj.keys().forEach { k ->
                val dObj = ldObj.getJSONObject(k)
                val courtNo = dObj.getInt("courtNo")
                val d = CourtData(courtNo)
                if (dObj.has("currentItem")) d.currentItem = dObj.getInt("currentItem")
                if (dObj.has("lastUpdated")) d.lastUpdated = dObj.getLong("lastUpdated")
                val rArr = dObj.optJSONArray("readings") ?: JSONArray()
                for (i in 0 until rArr.length()) {
                    val r = rArr.getJSONObject(i)
                    d.readings.add(CourtReading(r.getInt("item"), r.getLong("t")))
                }
                if (dObj.has("sequenceRaw")) d.sequenceRaw = dObj.getString("sequenceRaw")
                d.sequenceRestOfMatters = dObj.optBoolean("sequenceRestOfMatters", false)
                val srArr = dObj.optJSONArray("sequenceRanges") ?: JSONArray()
                for (i in 0 until srArr.length()) {
                    val r = srArr.getJSONArray(i)
                    d.sequenceRanges.add(intArrayOf(r.getInt(0), r.getInt(1)))
                }
                liveData[courtNo] = d
            }
            onBreak = root.optBoolean("onBreak", false)
            breakUntil = root.optLong("breakUntil", 0L)
            notifEnabled = root.optBoolean("notifEnabled", false)
            notifMinutes = root.optInt("notifMinutes", 10)
            autoMinutes = root.optInt("autoMinutes", 1)
            lastSyncTime = if (root.has("lastSyncTime")) root.getLong("lastSyncTime") else null
            if (onBreak && breakUntil < System.currentTimeMillis()) { onBreak = false; breakUntil = 0 }
        } catch (e: Exception) { Log.e("SCTracker", "fromJson error: ${e.message}") }
    }
}
