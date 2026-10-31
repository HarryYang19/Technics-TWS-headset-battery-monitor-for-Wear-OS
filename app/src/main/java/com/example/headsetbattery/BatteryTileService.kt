package com.example.headsetbattery

import android.bluetooth.BluetoothSocket
import android.graphics.Color
import androidx.wear.protolayout.DimensionBuilders
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.tiles.EventBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.google.common.util.concurrent.SettableFuture
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * A Wear OS Tile (swipeable card), not a watch-face complication.
 * Every Tile timeline request makes a fresh RFCOMM/RACE read from the paired earbuds.
 * Old SharedPreferences values are deliberately never used as a displayed fallback.
 *
 * Compatibility: classic TileService + ProtoLayout 1.0, Tiles 1.2.0, minSdk/API 30
 * (Wear OS 3). The classic enter callback forces a new timeline request when the card appears.
 */
class BatteryTileService : TileService() {
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "technics-tile-reader").apply { isDaemon = true }
    }
    private val timer: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "technics-tile-timeout").apply { isDaemon = true }
    }
    private val activeRequest = AtomicReference<SettableFuture<TileBuilders.Tile>?>(null)
    private val activeSocket = AtomicReference<BluetoothSocket?>(null)

    override fun onTileRequest(
        requestParams: RequestBuilders.TileRequest
    ): ListenableFuture<TileBuilders.Tile> {
        val existing = activeRequest.get()
        if (existing != null && !existing.isDone) return existing

        val resultFuture = SettableFuture.create<TileBuilders.Tile>()
        if (!activeRequest.compareAndSet(existing, resultFuture)) {
            val raced = activeRequest.get()
            if (raced != null && !raced.isDone) return raced
            if (!activeRequest.compareAndSet(raced, resultFuture)) return immediateTile(
                TechnicsRaceReader.Result.failure("正在准备电量数据，请稍后重新进入卡片")
            )
        }

        val taskRef = AtomicReference<Future<*>?>(null)
        val task = worker.submit {
            try {
                val live = TechnicsRaceReader.readLive(applicationContext, activeSocket)
                if (live.live) saveLiveResult(live)
                resultFuture.set(buildTile(live))
            } catch (error: Exception) {
                val message = error.message ?: error.javaClass.simpleName
                resultFuture.set(buildTile(TechnicsRaceReader.Result.failure("实时读取失败：$message")))
            } finally {
                activeSocket.getAndSet(null)?.let { socket ->
                    try { socket.close() } catch (_: Exception) { }
                }
                activeRequest.compareAndSet(resultFuture, null)
            }
        }
        taskRef.set(task)

        // Schedule only after the Future reference is published, so timeout can always cancel it.
        val timeout = timer.schedule({
            activeSocket.getAndSet(null)?.let { socket ->
                try { socket.close() } catch (_: Exception) { }
            }
            taskRef.get()?.cancel(true)
            resultFuture.set(buildTile(TechnicsRaceReader.Result.failure("实时读取超时；请确认耳机已连接手表")))
        }, TILE_REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)

        resultFuture.addListener({
            if (resultFuture.isCancelled) {
                activeSocket.getAndSet(null)?.let { socket ->
                    try { socket.close() } catch (_: Exception) { }
                }
                task.cancel(true)
            }
            timeout.cancel(false)
            activeRequest.compareAndSet(resultFuture, null)
        }, MoreExecutors.directExecutor())

        return resultFuture
    }

    /** Called when this card enters the visible carousel viewport. Always request a fresh
     * timeline. If onTileRequest is already reading, activeRequest coalesces the two requests. */
    @Suppress("DEPRECATION")
    override fun onTileEnterEvent(requestParams: EventBuilders.TileEnterEvent) {
        super.onTileEnterEvent(requestParams)
        try {
            TileService.getUpdater(this).requestUpdate(BatteryTileService::class.java)
        } catch (_: RuntimeException) {
            // The ordinary onTileRequest path remains the fallback on unusual renderers.
        }
    }

    /** Stop an in-flight Bluetooth transaction after the user leaves this card. */
    @Suppress("DEPRECATION")
    override fun onTileLeaveEvent(requestParams: EventBuilders.TileLeaveEvent) {
        super.onTileLeaveEvent(requestParams)
        cancelActiveRead()
    }

    private fun cancelActiveRead() {
        activeSocket.getAndSet(null)?.let { socket ->
            try { socket.close() } catch (_: Exception) { }
        }
        activeRequest.getAndSet(null)?.cancel(true)
    }

    override fun onTileResourcesRequest(
        requestParams: RequestBuilders.ResourcesRequest
    ): ListenableFuture<ResourceBuilders.Resources> {
        val resources = ResourceBuilders.Resources.Builder()
            .setVersion(RESOURCE_VERSION)
            .build()
        return com.google.common.util.concurrent.Futures.immediateFuture(resources)
    }

    private fun saveLiveResult(result: TechnicsRaceReader.Result) {
        val edit = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
            .putString("device_name", result.deviceName)
            .putString("status", result.status)
            .putLong("updated", System.currentTimeMillis())
        putOrRemove(edit, "left", result.left)
        putOrRemove(edit, "right", result.right)
        putOrRemove(edit, "case", result.box)
        edit.apply()
    }

    private fun putOrRemove(
        edit: android.content.SharedPreferences.Editor,
        key: String,
        value: Int
    ) {
        if (value in 0..100) edit.putInt(key, value) else edit.remove(key)
    }

    private fun buildTile(result: TechnicsRaceReader.Result): TileBuilders.Tile {
        val model = modelName(result.deviceName)
        val lines = ArrayList<LayoutElementBuilders.LayoutElement>()
        lines.add(text("Technics · $model", 13f))

        if (result.live) {
            lines.add(text("左耳/L ${format(result.left)}   右耳/R ${format(result.right)}", 17f))
            lines.add(text("充电盒 ${format(result.box)}", 15f))
            lines.add(text("刚刚从耳机实时读取", 10f))
        } else {
            // Never label a cached percentage as current. A failed live read is visibly a failure.
            lines.add(text("--%   --%", 22f))
            lines.add(text("本次实时读取失败", 12f))
            lines.add(text(shortError(result.status), 10f))
        }

        val column = LayoutElementBuilders.Column.Builder()
            .setWidth(DimensionBuilders.expand())
            .setHeight(DimensionBuilders.expand())
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .setModifiers(
                ModifiersBuilders.Modifiers.Builder()
                    .setPadding(
                        ModifiersBuilders.Padding.Builder()
                            .setStart(DimensionBuilders.dp(16f))
                            .setEnd(DimensionBuilders.dp(16f))
                            .setTop(DimensionBuilders.dp(20f))
                            .setBottom(DimensionBuilders.dp(20f))
                            .build()
                    )
                    .build()
              )

        lines.forEach { column.addContent(it) }

        return TileBuilders.Tile.Builder()
            .setResourcesVersion(RESOURCE_VERSION)
            // No background polling. Opening/entering the Tile triggers the next fresh read.
            .setFreshnessIntervalMillis(0L)
            .setTileTimeline(TimelineBuilders.Timeline.fromLayoutElement(column.build()))
            .build()
    }

    private fun text(value: String, sizeSp: Float): LayoutElementBuilders.Text {
        val font = LayoutElementBuilders.FontStyle.Builder()
            .setSize(DimensionBuilders.sp(sizeSp))
            .setColor(androidx.wear.protolayout.ColorBuilders.argb(Color.WHITE))
            .build()
        return LayoutElementBuilders.Text.Builder()
            .setText(value)
            .setFontStyle(font)
            .setMaxLines(2)
            .build()
    }

    private fun format(value: Int): String = if (value in 0..100) "$value%" else "--%"

    private fun modelName(name: String): String {
        val match = Regex("(?i)EAH[- ]?AZ\\d{2,3}[A-Z0-9]*|AZ\\d{2,3}[A-Z0-9]*").find(name)
        return match?.value?.uppercase() ?: "EAH-AZ"
    }

    private fun shortError(message: String): String {
        val cleaned = message.replace('\n', ' ').trim()
        return if (cleaned.length <= 48) cleaned else cleaned.take(45) + "…"
    }

    private fun immediateTile(result: TechnicsRaceReader.Result): ListenableFuture<TileBuilders.Tile> =
        com.google.common.util.concurrent.Futures.immediateFuture(buildTile(result))

    override fun onDestroy() {
        activeSocket.getAndSet(null)?.let { socket ->
            try { socket.close() } catch (_: Exception) { }
        }
        activeRequest.getAndSet(null)?.cancel(true)
        worker.shutdownNow()
        timer.shutdownNow()
        super.onDestroy()
    }

    companion object {
        private const val PREFS_NAME = "headset_battery"
        private const val RESOURCE_VERSION = "technics-live-wearos3-v2"
        // Leave margin below TileService's hard 10-second response deadline.
        private const val TILE_REQUEST_TIMEOUT_MS = 8_500L
    }
}
