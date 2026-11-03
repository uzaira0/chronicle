package com.openlattice.chronicle.services.upload

import java.util.concurrent.TimeUnit
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request

/** Route by body before OkHttp starts the call's overall timeout. Both clients share sockets. */
internal class UploadTransport(val interactiveClient: OkHttpClient) : Call.Factory {
    val collectedClient: OkHttpClient = interactiveClient.newBuilder().callTimeout(0, TimeUnit.MILLISECONDS).build()

    override fun newCall(request: Request): Call =
        (if (request.body is CollectedUploadBody<*>) collectedClient else interactiveClient).newCall(request)
}
