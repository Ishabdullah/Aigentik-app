package com.aigentik.app.core

import android.telephony.PhoneNumberUtils
import android.util.Log

// PhoneNormalizer v1.0 — ported from aigentik-android verbatim
// Single source of truth for phone number formatting across the agent layer.
// Used by: NotificationAdapter, MessageDeduplicator, ContactEngine (Stage 1)
object PhoneNormalizer {

    private const val TAG = "PhoneNormalizer"
    private const val DEFAULT_REGION = "US"

    fun toE164(raw: String): String {
        if (raw.isBlank()) return raw
        try {
            val normalized = PhoneNumberUtils.formatNumberToE164(raw, DEFAULT_REGION)
            if (normalized != null) {
                Log.d(TAG, "Normalized '$raw' → '$normalized' via PhoneNumberUtils")
                return normalized
            }
        } catch (e: Exception) {
            Log.w(TAG, "PhoneNumberUtils failed for '$raw': ${e.message}")
        }
        val digits = raw.filter { it.isDigit() }
        val result = when {
            digits.length == 10 -> "+1$digits"
            digits.length == 11 && digits.startsWith("1") -> "+${digits}"
            digits.length > 11 -> "+$digits"
            raw.startsWith("+") && digits.length >= 10 -> "+$digits"
            else -> { Log.w(TAG, "Could not normalize '$raw' — returning as-is"); raw }
        }
        Log.d(TAG, "Normalized '$raw' → '$result' via fallback")
        return result
    }

    fun isSameNumber(a: String, b: String): Boolean {
        if (toE164(a) == toE164(b)) return true
        val digitsA = a.filter { it.isDigit() }.takeLast(10)
        val digitsB = b.filter { it.isDigit() }.takeLast(10)
        return digitsA.isNotEmpty() && digitsA == digitsB
    }

    fun looksLikePhoneNumber(s: String): Boolean {
        val digits = s.filter { it.isDigit() }
        return digits.length >= 7 && s.filter {
            !it.isDigit() && it != '+' && it != '-' && it != '(' && it != ')' && it != ' '
        }.isEmpty()
    }
}
