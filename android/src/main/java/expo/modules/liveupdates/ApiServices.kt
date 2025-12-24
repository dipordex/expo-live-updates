package expo.modules.liveupdates

import retrofit2.Response
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

interface ApiServices {

    @POST("/api/stopwatch/")
    suspend fun postStopWatchStatus(
        @Path("id") id:Int,
        @Query("status") status:String
    ) : Response<Any>

}