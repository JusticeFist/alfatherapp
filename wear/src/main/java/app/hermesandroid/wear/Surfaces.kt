package app.hermesandroid.wear

import android.content.ComponentName
import android.content.Context
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.protolayout.material.Chip
import androidx.wear.protolayout.material.ChipColors
import androidx.wear.protolayout.material.Colors
import androidx.wear.protolayout.material.Text
import androidx.wear.protolayout.material.Typography
import androidx.wear.protolayout.material.layouts.PrimaryLayout
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.LongTextComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.runBlocking

// The tile (an Ask button + your next event) and the watch-face complication (next event, or that
// Allfather is working). Both read the status the phone pushes as a data item.

private const val GOLD = 0xFFE2B048.toInt()

private fun nextLine(): String? = WatchState.nextTitle?.let { "$it · ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(WatchState.nextAt))}" }

class Tile : TileService() {
    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> {
        runBlocking { Phone.loadStatus(this@Tile) }
        val open = ActionBuilders.LaunchAction.Builder().setAndroidActivity(
            ActionBuilders.AndroidActivity.Builder().setPackageName(packageName).setClassName(MainActivity::class.java.name)
                .addKeyToExtraMapping(MainActivity.EXTRA_LISTEN, ActionBuilders.AndroidBooleanExtra.Builder().setValue(true).build()).build()
        ).build()
        val click = ModifiersBuilders.Clickable.Builder().setId("ask").setOnClick(open).build()
        val device = requestParams.deviceConfiguration
        val layout = PrimaryLayout.Builder(device)
            .setPrimaryLabelTextContent(Text.Builder(this, WatchState.name).setTypography(Typography.TYPOGRAPHY_CAPTION1).setColor(argb(GOLD)).build())
            .setContent(Text.Builder(this, if (WatchState.working) getString(R.string.surfaces_working) else nextLine() ?: getString(R.string.surfaces_no_events))
                .setTypography(Typography.TYPOGRAPHY_BODY1).setMaxLines(3).setColor(argb(0xFFFFFFFF.toInt())).build())
            .setPrimaryChipContent(Chip.Builder(this, click, device).setPrimaryLabelContent(getString(R.string.surfaces_ask))
                .setChipColors(ChipColors.primaryChipColors(Colors(GOLD, 0xFF000000.toInt(), 0xFF303030.toInt(), 0xFFFFFFFF.toInt()))).build())
            .build()
        val tile = TileBuilders.Tile.Builder().setResourcesVersion("1").setFreshnessIntervalMillis(15 * 60_000L)
            .setTileTimeline(TimelineBuilders.Timeline.fromLayoutElement(layout)).build()
        return Futures.immediateFuture(tile)
    }

    override fun onTileResourcesRequest(requestParams: RequestBuilders.ResourcesRequest): ListenableFuture<ResourceBuilders.Resources> =
        Futures.immediateFuture(ResourceBuilders.Resources.Builder().setVersion("1").build())

    companion object {
        fun refresh(context: Context) = runCatching { getUpdater(context).requestUpdate(Tile::class.java) }
    }
}

class Complication : SuspendingComplicationDataSourceService() {
    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        Phone.loadStatus(this)
        return data(request.complicationType)
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData? = data(type, preview = true)

    private fun data(type: ComplicationType, preview: Boolean = false): ComplicationData? {
        val short = if (preview) "15:00" else if (WatchState.working) "…" else WatchState.nextTitle?.let { DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(WatchState.nextAt)) } ?: "AF"
        val long = if (preview) getString(R.string.surfaces_preview_event) else if (WatchState.working) getString(R.string.surfaces_name_working, WatchState.name) else nextLine() ?: WatchState.name
        val tap = android.app.PendingIntent.getActivity(this, 0, android.content.Intent(this, MainActivity::class.java), android.app.PendingIntent.FLAG_IMMUTABLE)
        return when (type) {
            ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(PlainComplicationText.Builder(short).build(), PlainComplicationText.Builder(long).build()).setTapAction(tap).build()
            ComplicationType.LONG_TEXT -> LongTextComplicationData.Builder(PlainComplicationText.Builder(long).build(), PlainComplicationText.Builder(long).build()).setTapAction(tap).build()
            else -> null
        }
    }

    companion object {
        fun refresh(context: Context) = runCatching { ComplicationDataSourceUpdateRequester.create(context, ComponentName(context, Complication::class.java)).requestUpdateAll() }
    }
}
