package com.example.network

import android.content.Context
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

sealed interface PairingScanResult {
    data class Code(val value: String) : PairingScanResult
    data object Cancelled : PairingScanResult
    data object Invalid : PairingScanResult
    data object Unavailable : PairingScanResult

    companion object {
        fun fromRaw(raw: String?): PairingScanResult {
            if (raw == null) return Invalid
            return try {
                PairingCode.parse(raw)
                Code(raw.trim())
            } catch (_: IllegalArgumentException) {
                Invalid
            }
        }
    }
}

class PairingQrScanner(private val context: Context) {
    suspend fun scan(): PairingScanResult = suspendCancellableCoroutine { continuation ->
        fun finish(result: PairingScanResult) {
            // Navigating away cancels the coroutine; late camera results are ignored.
            if (continuation.isActive) continuation.resume(result)
        }
        try {
            val options = GmsBarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .enableAutoZoom()
                .build()
            GmsBarcodeScanning.getClient(context, options).startScan()
                .addOnSuccessListener { finish(PairingScanResult.fromRaw(it.rawValue)) }
                .addOnCanceledListener { finish(PairingScanResult.Cancelled) }
                .addOnFailureListener { finish(PairingScanResult.Unavailable) }
        } catch (_: Exception) {
            // SDK errors may include untrusted values; never display or log raw exceptions.
            finish(PairingScanResult.Unavailable)
        }
    }
}
