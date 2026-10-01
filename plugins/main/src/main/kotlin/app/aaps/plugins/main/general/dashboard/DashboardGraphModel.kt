package app.aaps.plugins.main.general.dashboard

import androidx.compose.runtime.Immutable
import app.aaps.core.data.model.SourceSensor
import app.aaps.core.graph.data.BolusDataPoint
import app.aaps.core.graph.data.CarbsDataPoint
import app.aaps.core.graph.data.DataPointWithLabelInterface
import app.aaps.core.graph.data.EffectiveProfileSwitchDataPoint
import app.aaps.core.graph.data.GlucoseValueDataPoint
import app.aaps.core.graph.data.Shape
import app.aaps.core.interfaces.graph.Scale
import app.aaps.core.interfaces.graph.SeriesData
import app.aaps.core.interfaces.overview.OverviewData
import app.aaps.core.interfaces.overview.OverviewMenus
import com.jjoe64.graphview.series.BaseSeries
import com.jjoe64.graphview.series.DataPointInterface

@Immutable
data class GraphPoint(val x: Long, val y: Double)

enum class PredictionKind { IOB, COB, A_COB, UAM, ZT }

enum class MarkerKind { BOLUS, SMB, CARBS, PROFILE, THERAPY }

@Immutable
data class GraphMarker(val x: Long, val y: Double, val kind: MarkerKind, val label: String, val invalid: Boolean = false)

@Immutable
data class GraphModel(
    val fromTime: Long = 0,
    val now: Long = 0,
    val endTime: Long = 0,
    val rangeHours: Int = 6,
    val maxY: Double = 0.0,
    val lowMark: Double = 0.0,
    val highMark: Double = 0.0,
    val isMgdl: Boolean = true,
    val bg: List<GraphPoint> = emptyList(),
    val predictions: Map<PredictionKind, List<GraphPoint>> = emptyMap(),
    val markers: List<GraphMarker> = emptyList(),
    val basal: List<GraphPoint> = emptyList(),
    val basalProfile: List<GraphPoint> = emptyList(),
    val targetLine: List<GraphPoint> = emptyList(),
    val activity: List<GraphPoint> = emptyList()
) {

    val isEmpty get() = fromTime == 0L
}

/**
 * Reads the BG-graph series OverviewData already prepared for the classic graph
 * and turns them into plain lists for Compose rendering.
 *
 * Scales in OverviewData are shared with OverviewFragment, so they are
 * temporarily reset to identity while reading and restored afterwards.
 */
object GraphModelBuilder {

    fun build(
        overviewData: OverviewData,
        overviewMenus: OverviewMenus,
        now: Long,
        lowMark: Double,
        highMark: Double,
        isMgdl: Boolean,
        showBasal: Boolean
    ): GraphModel {
        val settings = overviewMenus.setting
        if (settings.isEmpty()) return GraphModel()
        val main = settings[0]
        val from = overviewData.fromTime.toDouble()
        val to = overviewData.endTime.toDouble()

        val bg = points(overviewData.bgReadingGraphSeries, from, to).map { GraphPoint(it.x.toLong(), it.y) }

        val predictions =
            if (main[OverviewMenus.CharType.PRE.ordinal])
                points(overviewData.predictionsGraphSeries, from, to)
                    .filterIsInstance<GlucoseValueDataPoint>()
                    .mapNotNull { p -> p.kind()?.let { it to GraphPoint(p.x.toLong(), p.y) } }
                    .groupBy({ it.first }, { it.second })
                    .mapValues { e -> e.value.sortedBy { it.x } }
            else emptyMap()

        val markers = ArrayList<GraphMarker>()
        points(overviewData.treatmentsSeries, from, to).forEach { p ->
            when (p) {
                // y is the nearest BG (SMB: low mark), set by PrepareTreatmentsDataWorker like the classic graph
                is BolusDataPoint  -> markers.add(GraphMarker(p.x.toLong(), p.y, if (p.shape == Shape.SMB) MarkerKind.SMB else MarkerKind.BOLUS, p.label, !p.data.isValid))
                is CarbsDataPoint  -> markers.add(GraphMarker(p.x.toLong(), p.y, MarkerKind.CARBS, p.label, !p.data.isValid))
            }
        }
        points(overviewData.epsSeries, from, to).filterIsInstance<EffectiveProfileSwitchDataPoint>()
            .forEach { markers.add(GraphMarker(it.x.toLong(), 0.0, MarkerKind.PROFILE, it.label)) }
        if (main[OverviewMenus.CharType.TREAT.ordinal])
            points(overviewData.therapyEventSeries, from, to).filterIsInstance<DataPointWithLabelInterface>()
                .forEach { markers.add(GraphMarker(it.x.toLong(), it.y, MarkerKind.THERAPY, it.label)) }

        var basal = emptyList<GraphPoint>()
        var basalProfile = emptyList<GraphPoint>()
        if (showBasal && main[OverviewMenus.CharType.BAS.ordinal])
            unscaled(overviewData.basalScale) {
                basal = points(overviewData.absoluteBasalGraphSeries, from, to).map { GraphPoint(it.x.toLong(), it.y) }
                basalProfile = points(overviewData.basalLineGraphSeries, from, to).map { GraphPoint(it.x.toLong(), it.y) }
            }

        val activity =
            if (main[OverviewMenus.CharType.ACT.ordinal])
                unscaled(overviewData.actScale) {
                    (points(overviewData.activitySeries, from, to) + points(overviewData.activityPredictionSeries, from, to))
                        .map { GraphPoint(it.x.toLong(), it.y) }.sortedBy { it.x }
                }
            else emptyList()

        val targetLine = points(overviewData.temporaryTargetSeries, from, to).map { GraphPoint(it.x.toLong(), it.y) }

        // same y range rules as the classic graph (GraphData.addBgReadings / addTreatments / addTherapyEvents / addBasals)
        val baseMaxY = if (overviewData.bgReadingsArray.isEmpty()) (if (isMgdl) 180.0 else 10.0) else overviewData.maxBgValue
        val maxY = maxOf(
            baseMaxY,
            overviewData.maxTreatmentsValue,
            if (main[OverviewMenus.CharType.TREAT.ordinal]) overviewData.maxTherapyEventValue else 0.0,
            highMark
        )
        return GraphModel(
            fromTime = overviewData.fromTime,
            now = now,
            endTime = overviewData.endTime,
            rangeHours = overviewData.rangeToDisplay,
            maxY = maxY,
            lowMark = lowMark,
            highMark = highMark,
            isMgdl = isMgdl,
            bg = bg,
            predictions = predictions,
            markers = markers,
            basal = basal,
            basalProfile = basalProfile,
            targetLine = targetLine,
            activity = activity
        )
    }

    private fun GlucoseValueDataPoint.kind(): PredictionKind? = when (data.sourceSensor) {
        SourceSensor.IOB_PREDICTION   -> PredictionKind.IOB
        SourceSensor.COB_PREDICTION   -> PredictionKind.COB
        SourceSensor.A_COB_PREDICTION -> PredictionKind.A_COB
        SourceSensor.UAM_PREDICTION   -> PredictionKind.UAM
        SourceSensor.ZT_PREDICTION    -> PredictionKind.ZT
        else                          -> null
    }

    @Suppress("UNCHECKED_CAST")
    private fun points(series: SeriesData, from: Double, to: Double): List<DataPointInterface> {
        val base = series as? BaseSeries<DataPointInterface> ?: return emptyList()
        if (base.isEmpty) return emptyList()
        val out = ArrayList<DataPointInterface>()
        base.getValues(from, to).forEach { out.add(it) }
        return out
    }

    private inline fun <T> unscaled(scale: Scale, block: () -> T): T {
        val shift = scale.shift
        val multiplier = scale.multiplier
        scale.shift = 0.0
        scale.multiplier = 1.0
        try {
            return block()
        } finally {
            scale.shift = shift
            scale.multiplier = multiplier
        }
    }
}
