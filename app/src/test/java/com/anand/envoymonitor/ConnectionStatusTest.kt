package com.anand.envoymonitor

import com.anand.envoymonitor.data.TokenMetadata
import com.anand.envoymonitor.monitor.*
import com.anand.envoymonitor.network.GatewayFailure
import org.junit.Assert.*
import org.junit.Test
import javax.net.ssl.SSLHandshakeException
import java.io.IOException

class ConnectionStatusTest {
    @Test fun expiredDeniedTokenIsReportedAsAnAuthenticationError() {
        val problem = connectionProblem(GatewayFailure(401, "ignored"), TokenMetadata(null, 100, null, null, null), 101)
        assertEquals(ConnectionStatus.AUTH_ERROR, problem.status)
        assertTrue(problem.message.contains("expiry"))
    }
    @Test fun unexpiredRejectedTokenIsNotMisdiagnosedAsExpired() {
        for (code in listOf(401, 403)) {
            val problem = connectionProblem(GatewayFailure(code, "sensitive server body"), TokenMetadata(null, 200, null, null, null), 100)
            assertEquals(ConnectionStatus.AUTH_ERROR, problem.status)
            assertTrue(problem.message.contains("expiry is not confirmed"))
            assertFalse(problem.message.contains("sensitive"))
        }
    }
    @Test fun certificateAndNetworkErrorsRemainDistinctFromAuthentication() {
        assertEquals(ConnectionStatus.UNAVAILABLE, connectionProblem(SSLHandshakeException("test certificate"), null, 100).status)
        assertTrue(connectionProblem(SSLHandshakeException("test certificate"), null, 100).message.contains("Certificate"))
        assertEquals(ConnectionStatus.UNAVAILABLE, connectionProblem(IOException(), null, 100).status)
    }
    @Test fun invalidPastedCredentialsNeverAppearInErrorMessages() {
        val problem = connectionProblem(IllegalArgumentException("fake-secret"), null, 100)
        assertFalse(problem.message.contains("fake-secret"))
        assertTrue(problem.message.contains("Invalid token format"))
    }
}
