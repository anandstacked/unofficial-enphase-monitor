package com.anand.envoymonitor.data

import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class TokenMetadataTest {
    private fun jwt(payload: String) = "eyJhbGciOiJFUzI1NiJ9." + Base64.getUrlEncoder().withoutPadding().encodeToString(payload.toByteArray()) + ".exampleSignature"
    @Test fun ownerAndInstallerLifetimesAreDecodedFromTheirActualClaims() {
        val owner = tokenMetadata(jwt("""{"iat":1700000000,"exp":1731536000,"enphaseUser":"owner","aud":"987654321098"}"""))!!
        val installer = tokenMetadata(jwt("""{"iat":1700000000,"exp":1700043200,"enphaseUser":"installer"}"""))!!
        assertEquals(31536000L, owner.lifetimeSeconds)
        assertEquals(43200L, installer.lifetimeSeconds)
        assertEquals("owner", owner.role)
        assertTrue(installer.warning(1700000000, "987654321098")!!.contains("Installer"))
    }
    @Test fun replacingATokenWithAnotherSavedTokenChangesExpiryMetadata() {
        val old = StoredTokenState(true, tokenMetadata(jwt("""{"exp":1700043200}""")))
        val replacement = StoredTokenState(true, tokenMetadata(jwt("""{"exp":1731536000}""")))
        assertNotEquals(old, replacement)
        assertEquals(1731536000L, replacement.metadata!!.expiresAt)
    }
    @Test fun expiryIsDetectedAtItsExactBoundary() {
        val metadata = tokenMetadata(jwt("""{"exp":1700043200}"""))!!
        assertFalse(metadata.expired(1700043199))
        assertTrue(metadata.expired(1700043200))
        assertTrue(metadata.warning(1700043200, "987654321098")!!.contains("expired"))
    }
    @Test fun differentGatewayAndFutureNotBeforeHaveSeparateDiagnostics() {
        val different = tokenMetadata(jwt("""{"aud":"123456789012","exp":1731536000}"""))!!
        assertTrue(different.warning(1700000000, "987654321098")!!.contains("different gateway"))
        val future = tokenMetadata(jwt("""{"nbf":1700043200,"exp":1731536000}"""))!!
        assertTrue(future.warning(1700000000, "987654321098")!!.contains("clock"))
    }
    @Test fun malformedInputAndOutOfRangeDatesCannotCrashExpiryDisplay() {
        assertNull(tokenMetadata("not a token"))
        assertNull(tokenMetadata(jwt("not JSON")))
        assertNull(tokenMetadata(jwt("""{"exp":9223372036854775807}"""))!!.expiresAt)
        assertNull(tokenMetadata(jwt("""{"exp":1.5}"""))!!.expiresAt)
        try { normalizeLocalToken("{\"token\":\"not-a-jwt\"}"); fail("JSON accepted") } catch (_: IllegalArgumentException) { }
    }
    @Test fun bearerPrefixDoesNotChangeTheStoredCredential() {
        val original = jwt("""{"exp":1731536000}""" )
        assertEquals(original, normalizeLocalToken("  bearer $original  "))
    }
    @Test fun remainingTimeIncludesSecondsForShortLivedTokens() {
        assertEquals("Expired", tokenDuration(0))
        assertEquals("59 s", tokenDuration(59))
        assertEquals("1 min 1 s", tokenDuration(61))
        assertEquals("12 h 0 min", tokenDuration(43200))
    }
}
