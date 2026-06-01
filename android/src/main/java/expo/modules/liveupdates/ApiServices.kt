package expo.modules.liveupdates

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

interface ApiServices {

    @POST("{id}/")
    suspend fun postStopWatchStatus(
        @Path("id") id: Int,
        @Query("status") status: String
    ): Response<Any>

    @POST(".")
    suspend fun postLapCreation(
        @Body lapObject: LapObject
    ):  Response<Any>

    @POST("{id}/stop/")
    suspend fun postTaskStop(
        @Path("id") id: Int
    ): Response<Any>

    @POST("{id}/stop")
    suspend fun postTapInStop(
        @Path("id") id: Int
    ): Response<Any>
}
