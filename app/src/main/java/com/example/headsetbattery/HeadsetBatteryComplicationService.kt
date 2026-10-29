package com.example.headsetbattery

import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.LongTextComplicationData
import androidx.wear.watchface.complications.data.NoDataComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.RangedValueComplicationData
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceService
import androidx.wear.watchface.complications.datasource.ComplicationRequest

class HeadsetBatteryComplicationService : ComplicationDataSourceService() {
    override fun getPreviewData(type: ComplicationType): ComplicationData? {
        val previewText = PlainComplicationText.Builder("68%").build()
        return when (type) {
            ComplicationType.RANGED_VALUE -> RangedValueComplicationData.Builder(
                68f, 0f, 100f, previewText
            ).setTitle(PlainComplicationText.Builder("耳机").build()).build()
            ComplicationType.LONG_TEXT -> LongTextComplicationData.Builder(
                PlainComplicationText.Builder("耳机 68%").build(),
                PlainComplicationText.Builder("耳机电量").build()
            ).build()
            else -> ShortTextComplicationData.Builder(
                previewText, PlainComplicationText.Builder("耳机电量").build()
            ).build()
        }
    }
    override fun onComplicationRequest(
        request: ComplicationRequest,
        listener: ComplicationDataSourceService.ComplicationRequestListener
    ) {
        val reading = readBattery()
        val data: ComplicationData = if (reading != null) {
            val text = PlainComplicationText.Builder("${reading.percent}%").build()
            val title = PlainComplicationText.Builder(reading.label).build()
            val description = PlainComplicationText.Builder("${reading.label}电量 ${reading.percent}%").build()
            when (request.complicationType) {
                ComplicationType.RANGED_VALUE -> RangedValueComplicationData.Builder(
                    reading.percent.toFloat(), 0f, 100f, description
                ).setTitle(title).build()
                ComplicationType.LONG_TEXT -> LongTextComplicationData.Builder(
                    PlainComplicationText.Builder("${reading.label} ${reading.percent}%").build(),
                    description
                ).build()
                else -> ShortTextComplicationData.Builder(text, description)
                    .setTitle(title).build()
            }
        } else {
            NoDataComplicationData()
        }
        listener.onComplicationData(data)
    }

    private fun readBattery(): BatteryReading? {
        return try {
            val prefs = getSharedPreferences("headset_battery", MODE_PRIVATE)
            val right = prefs.getInt("right", -1)
            val left = prefs.getInt("left", -1)
            val overall = prefs.getInt("overall", -1)
            when {
                right in 0..100 -> BatteryReading(right, "右耳")
                left in 0..100 -> BatteryReading(left, "左耳")
                overall in 0..100 -> BatteryReading(overall, "整机")
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    private data class BatteryReading(val percent: Int, val label: String)

}
