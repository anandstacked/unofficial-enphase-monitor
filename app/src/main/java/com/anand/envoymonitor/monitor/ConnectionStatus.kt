package com.anand.envoymonitor.monitor

import com.anand.envoymonitor.data.TokenMetadata
import com.anand.envoymonitor.network.GatewayFailure
import kotlinx.coroutines.TimeoutCancellationException
import javax.net.ssl.SSLException

enum class ConnectionStatus { NOT_TESTED, TESTING, CONNECTED, AUTH_ERROR, UNAVAILABLE }
data class ConnectionProblem(val status: ConnectionStatus, val message: String)
fun connectionProblem(error: Exception, metadata: TokenMetadata?, now: Long): ConnectionProblem = when {
    error is GatewayFailure && error.code in listOf(401, 403) -> ConnectionProblem(ConnectionStatus.AUTH_ERROR,
        "Authentication failed (HTTP ${error.code}). " + if (metadata?.expired(now) == true) "Token reports expiry. Generate a new owner token."
        else "Gateway rejected this token or its permissions. Check the token's gateway serial and owner access; expiry is not confirmed.")
    error is SSLException -> ConnectionProblem(ConnectionStatus.UNAVAILABLE, "Certificate verification failed. Re-pair this gateway in Settings.")
    error is TimeoutCancellationException -> ConnectionProblem(ConnectionStatus.UNAVAILABLE, "Connection test timed out. Check home Wi-Fi and gateway connectivity.")
    error is GatewayFailure -> ConnectionProblem(ConnectionStatus.UNAVAILABLE, error.message ?: "Gateway connection failed")
    error is IllegalArgumentException -> ConnectionProblem(ConnectionStatus.UNAVAILABLE, "Invalid token format. Paste the JWT value only, without JSON or quotes.")
    else -> ConnectionProblem(ConnectionStatus.UNAVAILABLE, "Gateway unavailable. Check home Wi-Fi, address and gateway connectivity.")
}
