package com.anand.envoymonitor.data

import com.google.gson.JsonParser
import java.util.Base64

data class TokenMetadata(val issuedAt: Long?, val expiresAt: Long?, val notBefore: Long?, val role: String?, val gatewaySerial: String?) {
    val lifetimeSeconds: Long? get() = if (issuedAt != null && expiresAt != null && expiresAt > issuedAt) expiresAt - issuedAt else null
    fun expired(now: Long) = expiresAt?.let { now >= it } == true
    fun warning(now: Long, serial: String): String? = when {
        gatewaySerial != null && gatewaySerial != serial -> "Token reports a different gateway serial. Generate a token for this gateway."
        expired(now) -> "Token reports that it has expired. Generate a new owner token."
        notBefore != null && notBefore > now + 60 -> "Token is not yet valid according to the phone clock. Check the phone and gateway time."
        role == "installer" -> "Installer token reported. Use your system-owner account for longer-lived local access."
        lifetimeSeconds?.let { it <= 86400 } == true -> "Short-lived token reported. Verify that Enphase lists your account as the system owner."
        else -> null
    }
}
data class StoredTokenState(val saved: Boolean = false, val metadata: TokenMetadata? = null)

fun normalizeLocalToken(input: String): String {
    val clean = input.trim().replaceFirst(Regex("^Bearer\\s+", RegexOption.IGNORE_CASE), "")
    require(clean.length in 20..16384 && clean.matches(Regex("[a-zA-Z0-9_-]+\\.[a-zA-Z0-9_-]+\\.[a-zA-Z0-9_-]+"))) {
        "Paste the JWT token value only, without JSON or quotes."
    }
    return clean
}
/** Decode display hints only. The pinned gateway, not these unsigned claims, verifies access. */
fun tokenMetadata(token: String): TokenMetadata? = runCatching {
    val clean = normalizeLocalToken(token)
    val json = JsonParser.parseString(String(Base64.getUrlDecoder().decode(clean.split('.')[1]), Charsets.UTF_8)).asJsonObject
    fun timestamp(name: String) = json.get(name)?.asString?.toLongOrNull()?.takeIf { it in 1..253402300799L }
    val role = json.get("enphaseUser")?.asString?.lowercase()?.takeIf { it in setOf("owner", "installer", "host", "system_owner", "system_host") }
    val serial = json.get("aud")?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.matches(Regex("[0-9]{12}")) }
    TokenMetadata(timestamp("iat"), timestamp("exp"), timestamp("nbf"), role, serial)
}.getOrNull()
fun tokenDuration(seconds: Long): String {
    if (seconds <= 0) return "Expired"
    return when {
        seconds >= 86400 -> "${seconds / 86400} d ${(seconds % 86400) / 3600} h"
        seconds >= 3600 -> "${seconds / 3600} h ${(seconds % 3600) / 60} min"
        seconds >= 60 -> "${seconds / 60} min ${seconds % 60} s"
        else -> "$seconds s"
    }
}
