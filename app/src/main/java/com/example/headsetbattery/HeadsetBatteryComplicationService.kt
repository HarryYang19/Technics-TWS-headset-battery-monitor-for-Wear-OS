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
    override fun getPreviewData(type: ComplicationType): ComplicationData {
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
        val battery = readBattery()
        val data: ComplicationData = if (battery >= 0) {
            val text = PlainComplicationText.Builder("$battery%").build()
            when (request.complicationType) {
                ComplicationType.RANGED_VALUE -> RangedValueComplicationData.Builder(
                    battery.toFloat(), 0f, 100f, text
                ).setTitle(PlainComplicationText.Builder("耳机").build()).build()
                ComplicationType.LONG_TEXT -> LongTextComplicationData.Builder(
                    PlainComplicationText.Builder("耳机 $battery%").build(),
                    PlainComplicationText.Builder("耳机电量").build()
                ).build()
                else -> ShortTextComplicationData.Builder(
                    text, PlainComplicationText.Builder("耳机电量").build()
                ).build()
            }
        } else {
            NoDataComplicationData()
        }
        listener.onComplicationData(data)
    }

    private fun readBattery(): Int {
        return try {
            val prefs = getSharedPreferences("headset_battery", MODE_PRIVATE)
            val right = prefs.getInt("right", -1)
            val left = prefs.getInt("left", -1)
            when {
                right >= 0 -> right
                left >= 0 -> left
                else -> -1
            }
        } catch (_: Exception) {
            -1
        }
    }
}
