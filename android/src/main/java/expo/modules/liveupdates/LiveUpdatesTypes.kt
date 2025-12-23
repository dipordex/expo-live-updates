package expo.modules.liveupdates

import expo.modules.kotlin.records.Field
import expo.modules.kotlin.records.Record
import kotlinx.serialization.Serializable
import java.util.Date

data class LiveUpdateImage(@Field val url: String, @Field val isRemote: Boolean) : Record

@Serializable
data class LiveUpdateProgressPoint(@Field val position: Int, @Field val color: String? = null) :
    Record

@Serializable
data class LiveUpdateProgressSegment(@Field val length: Int, @Field val color: String? = null) :
    Record

data class LiveUpdateProgress(
    @Field val max: Int?,
    @Field val progress: Int?,
    @Field val indeterminate: Boolean?,
    @Field val points: ArrayList<LiveUpdateProgressPoint>? = null,
    @Field val segments: ArrayList<LiveUpdateProgressSegment>? = null,
) : Record

data class LiveUpdateState(
    @Field val title: String?,
    @Field var subtitle: String?,
    @Field var mode: String?,
    @Field var stopwatch: Stopwatch?,
    @Field var timer: Timer?,
    @Field var showInDynamicIsland: Boolean?,
    ) : Record

data class Stopwatch(
    @Field var id: String,
    @Field var startedAt: Date?,
    @Field var accumulated: Double,
    @Field var isRunning: Boolean,
    @Field var lapCount: Int,
) : Record

data class Timer(
    @Field var id: String?,
    @Field var duration: Double?,
    @Field var remaining: Double?,
    @Field var isRunning: Boolean?,
    @Field var endsAt: Date?,
    @Field var startTime: Date?,
): Record

class LiveUpdateConfig(
    @Field val deepLinkUrl: String? = null,
    @Field val iconBackgroundColor: String? = null,
) : Record

object LiveUpdatesModuleEvents {
    const val ON_TOKEN_CHANGE = "onTokenChange"
    const val ON_NOTIFICATION_STATE_CHANGE = "onNotificationStateChange"
}
