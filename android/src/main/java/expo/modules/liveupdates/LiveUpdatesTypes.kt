package expo.modules.liveupdates

import android.os.Bundle
import android.os.Parcelable
import android.util.Log
import expo.modules.kotlin.records.Field
import expo.modules.kotlin.records.Record
import kotlinx.parcelize.Parcelize
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
    @Field var task: TaskData?,
    @Field var tapIn: TapInData?,
    @Field var showInDynamicIsland: Boolean?,
) : Record

data class Stopwatch(
    @Field var id: String,
    @Field var startedAt: Long?,
    @Field var accumulated: Long,
    @Field var isRunning: Boolean,
    @Field var lapCount: Int,
) : Record

data class Timer(
    @Field var id: String?,
    @Field var duration: Double?,
    @Field var remaining: Double?,
    @Field var isRunning: Boolean?,
    @Field var endsAt: Long?,
    @Field var startTime: Long?,
) : Record

data class TaskData(
    @Field var id: String,
    @Field var startDate: Long?
) : Record

data class TapInData(
    @Field var id: String,
    @Field var startDate: Long?
) : Record

@Parcelize
data class LiveUpdateConfig(
    @Field val deepLinkUrl: String? = null,
    @Field val iconBackgroundColor: String? = null,
    @Field val backgroundColor: String? =null,
    @Field val accessToken: String? = null,
    @Field val apiEndpoint: ApiEndpoint?
) : Record, Parcelable

@Parcelize
data class ApiEndpoint(
    @Field var stopwatchEndpoints: StopwatchEndpoints?,
    @Field var taskEndpoints: String?,
    @Field var tapInEndpoints: String?
) : Record, Parcelable

@Parcelize
data class StopwatchEndpoints(
    @Field var common: String,
    @Field var lap: String
) : Record, Parcelable

object LiveUpdatesModuleEvents {
    const val ON_TOKEN_CHANGE = "onTokenChange"
    const val ON_NOTIFICATION_STATE_CHANGE = "onNotificationStateChange"
    const val ON_BUTTON_PRESSED = "onButtonPressed"
}

object NotificationStateTriggredEventEmitter {
    var sendEvent: ((String, Bundle) -> Unit)? = null

    fun emit(
        notificationId: Int?,
        action: String?,
        mode: String?,
    ) {
        val payload = Bundle().apply {
            putString("activityAction", action)
            putInt("stopwatchId", notificationId ?: -1)
            putInt("timerId", notificationId ?: -1)
            putString("mode", mode)
        }
        Log.d("It is triggreded",mode!!)
        sendEvent?.invoke(
            LiveUpdatesModuleEvents.ON_BUTTON_PRESSED,
            payload
        )
    }
}


data class LapObject(
    @Field var name:String,
    @Field var duration:Int,
    @Field var stopwatch:Int
)
