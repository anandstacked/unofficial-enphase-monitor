package com.anand.envoymonitor.network

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException

/** Cancelling the visible collector also cancels its socket, including a pending read. */
internal suspend fun awaitGatewayResponse(call: Call): String = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { call.cancel() }
    call.enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) { continuation.resumeWith(Result.failure(e)) }
        override fun onResponse(call: Call, response: Response) {
            val result = runCatching { response.use {
                if (it.code == 401 || it.code == 403) throw GatewayFailure(it.code, "Gateway access denied. Replace or check the owner token in Settings.")
                if (!it.isSuccessful) throw GatewayFailure(it.code, "Gateway returned HTTP ${it.code}.")
                it.body?.string() ?: throw GatewayFailure(0, "Gateway returned an empty response.")
            } }
            continuation.resumeWith(result)
        }
    })
}
