package com.anand.envoymonitor.wear.complications

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.wear.watchface.complications.data.*
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import com.anand.envoymonitor.sync.*
import com.anand.envoymonitor.wear.WearActivity
import com.anand.envoymonitor.wear.WearApplication
import com.anand.envoymonitor.wear.nowSeconds
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.TimeUnit

abstract class SolarProvider(private val metric: FaceMetric) : SuspendingComplicationDataSourceService() {
    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData {
        val app = application as WearApplication
        val serial = app.settings.state.value.serial
        fun cached(source: ReadingSource) = app.cache.load(source)?.takeIf { !app.settings.state.value.paired || it.gatewaySerial == serial }
        val now = nowSeconds()
        val reading = faceReading(selectEnvelope(cached(ReadingSource.PHONE), cached(ReadingSource.WATCH_WIFI), now), metric, now, ZoneId.systemDefault())
            ?: return NoDataComplicationData()
        return build(reading)
    }
    private fun build(reading: FaceReading): ComplicationData {
        val tap = PendingIntent.getActivity(this, 0, Intent(this, WearActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val description = PlainComplicationText.Builder(reading.description).build()
        val validity = TimeRange.between(Instant.EPOCH, Instant.ofEpochSecond(reading.expiresAt))
        return if (metric == FaceMetric.STATUS) {
            val age = TimeDifferenceComplicationText.Builder(TimeDifferenceStyle.SHORT_SINGLE_UNIT,
                CountUpTimeReference(Instant.ofEpochSecond(reading.reportedAt)))
                .setMinimumTimeUnit(TimeUnit.MINUTES).setDisplayAsNow(false).setText("${reading.text} · %s ago").build()
            LongTextComplicationData.Builder(age, description).setValidTimeRange(validity).setTapAction(tap).build()
        } else ShortTextComplicationData.Builder(PlainComplicationText.Builder(reading.text).build(), description)
            .setTitle(PlainComplicationText.Builder(reading.title).build()).setValidTimeRange(validity).setTapAction(tap).build()
    }
    override fun getPreviewData(type: ComplicationType): ComplicationData? {
        val text = when (metric) { FaceMetric.VOLTAGE -> "230.1V"; FaceMetric.POWER -> "3.4kW"; FaceMetric.DAY -> "28.6kWh"; FaceMetric.STATUS -> "Phone · 2m ago" }
        val description = PlainComplicationText.Builder("Example solar reading").build()
        return if (type == ComplicationType.LONG_TEXT && metric == FaceMetric.STATUS)
            LongTextComplicationData.Builder(PlainComplicationText.Builder(text).build(), description).build()
        else if (type == ComplicationType.SHORT_TEXT && metric != FaceMetric.STATUS)
            ShortTextComplicationData.Builder(PlainComplicationText.Builder(text).build(), description).build()
        else null
    }
}
class VoltageProvider : SolarProvider(FaceMetric.VOLTAGE)
class SolarPowerProvider : SolarProvider(FaceMetric.POWER)
class DayEnergyProvider : SolarProvider(FaceMetric.DAY)
class StatusProvider : SolarProvider(FaceMetric.STATUS)

object ComplicationUpdates {
    @Synchronized fun request(context: Context) {
        val prefs = context.getSharedPreferences("complication_updates", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val last = prefs.getLong("last", 0)
        if (now >= last && now - last < 300_000) return
        prefs.edit().putLong("last", now).apply()
        listOf(VoltageProvider::class.java, SolarPowerProvider::class.java, DayEnergyProvider::class.java, StatusProvider::class.java).forEach {
            ComplicationDataSourceUpdateRequester.create(context, ComponentName(context, it)).requestUpdateAll()
        }
    }
}
