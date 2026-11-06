package app.aaps.plugins.main.general.dashboard

import androidx.compose.runtime.Immutable

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
    /** end of the BG part (the predictions go on to [endTime]) */
    val toTime: Long = 0,
    val rangeMinutes: Int = 360,
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
