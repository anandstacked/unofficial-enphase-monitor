package com.anand.envoymonitor.network

import java.security.MessageDigest
import java.security.cert.CertificateException

fun certificateFingerprint(encoded: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(encoded).joinToString("") { "%02x".format(it) }
fun requireCertificatePin(encoded: ByteArray, expected: String) {
    if (!expected.matches(Regex("[a-fA-F0-9]{64}")) || !certificateFingerprint(encoded).equals(expected, ignoreCase = true))
        throw CertificateException("Gateway certificate changed. Re-pair in Settings.")
}
