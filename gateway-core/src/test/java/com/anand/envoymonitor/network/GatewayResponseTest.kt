package com.anand.envoymonitor.network

import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Timeout
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class GatewayResponseTest {
    private class DeferredCall : Call {
        lateinit var callback: Callback
        var cancelled = false
        private val request = Request.Builder().url("https://192.168.1.100/ivp/meters/readings").build()
        override fun request() = request
        override fun enqueue(responseCallback: Callback) { callback = responseCallback }
        override fun cancel() { cancelled = true }
        override fun isCanceled() = cancelled
        override fun isExecuted() = ::callback.isInitialized
        override fun timeout() = Timeout()
        override fun clone(): Call = DeferredCall()
        override fun execute(): Response = error("Only asynchronous requests are allowed")
        fun respond(code: Int, body: String) = callback.onResponse(this, Response.Builder().request(request)
            .protocol(Protocol.HTTP_1_1).code(code).message("test").body(body.toResponseBody()).build())
    }
    @Test fun closingCollectorCancelsThePendingGatewayCall() = runBlocking {
        val call = DeferredCall()
        val job = launch(start = CoroutineStart.UNDISPATCHED) { awaitGatewayResponse(call); fail("Cancelled read resumed") }
        assertTrue(call.isExecuted())
        job.cancelAndJoin()
        assertTrue(call.isCanceled())
        call.callback.onFailure(call, IOException("cancelled"))
    }
    @Test fun deniedResponseNeverBecomesAReading() = runBlocking {
        val call = DeferredCall()
        val result = async(start = CoroutineStart.UNDISPATCHED) { runCatching { awaitGatewayResponse(call) } }
        call.respond(401, "sensitive error body")
        val failure = result.await().exceptionOrNull() as GatewayFailure
        assertEquals(401, failure.code)
        assertFalse(failure.message!!.contains("sensitive"))
    }
    @Test fun successfulResponseRetainsOriginalPayload() = runBlocking {
        val call = DeferredCall()
        val result = async(start = CoroutineStart.UNDISPATCHED) { awaitGatewayResponse(call) }
        call.respond(200, "[{\"voltage\":172.3}]")
        assertEquals("[{\"voltage\":172.3}]", result.await())
    }
}
