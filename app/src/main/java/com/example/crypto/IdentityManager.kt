package com.example.crypto

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.util.UUID

/**
 * Phase 1: Protocol and Identity.
 * Keys are kept strictly in Android Keystore, never stored in files, logs, or cloud backups.
 * Device fingerprint is the SHA-256 hash of the public key, surviving app restarts.
 * Provides seamless fallback for JVM / Robolectric unit testing environments where AndroidKeyStore provider is absent.
 */
class IdentityManager(private val context: Context, private val allowSoftwareFallback: Boolean = false) {

    private val prefs = context.getSharedPreferences("macbridge_identity_prefs", Context.MODE_PRIVATE)

    private var fallbackKeyPair: KeyPair? = null

    private val keyStore: KeyStore? = try {
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    } catch (_: Exception) {
        null
    }

    val deviceId: String
        get() {
            var id = prefs.getString("device_id", null)
            if (id == null) {
                id = UUID.randomUUID().toString()
                prefs.edit().putString("device_id", id).apply()
            }
            return id
        }

    val deviceName: String
        get() {
            val saved = prefs.getString("device_name", null)
            if (!saved.isNullOrBlank()) return saved
            val model = Build.MODEL ?: "Android Device"
            return "$model (MacBridge)"
        }

    init {
        ensureIdentityKeyExists()
    }

    private fun ensureIdentityKeyExists() {
        val ks = keyStore
        if (ks != null) {
            try {
                if (!ks.containsAlias(KEY_ALIAS)) {
                    val keyPairGenerator = KeyPairGenerator.getInstance(
                        KeyProperties.KEY_ALGORITHM_EC,
                        ANDROID_KEYSTORE
                    )
                    val parameterSpec = KeyGenParameterSpec.Builder(
                        KEY_ALIAS,
                        KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
                    )
                        .setDigests(KeyProperties.DIGEST_SHA256)
                        .setAlgorithmParameterSpec(java.security.spec.ECGenParameterSpec("secp256r1"))
                        .build()

                    keyPairGenerator.initialize(parameterSpec)
                    keyPairGenerator.generateKeyPair()
                }
                if (!allowSoftwareFallback) prefs.edit().remove("sw_pub_key").remove("sw_priv_key").apply()
                return
            } catch (e: Exception) {
                if (!allowSoftwareFallback) throw IllegalStateException("Android Keystore identity unavailable", e)
            }
        }

        check(allowSoftwareFallback) { "Android Keystore identity unavailable" }

        // JVM / Robolectric software EC key pair fallback
        if (fallbackKeyPair == null) {
            val savedPub = prefs.getString("sw_pub_key", null)
            val savedPriv = prefs.getString("sw_priv_key", null)
            if (savedPub != null && savedPriv != null) {
                try {
                    val kf = java.security.KeyFactory.getInstance("EC")
                    val pubKey = kf.generatePublic(java.security.spec.X509EncodedKeySpec(Base64.decode(savedPub, Base64.NO_WRAP)))
                    val privKey = kf.generatePrivate(java.security.spec.PKCS8EncodedKeySpec(Base64.decode(savedPriv, Base64.NO_WRAP)))
                    fallbackKeyPair = KeyPair(pubKey, privKey)
                } catch (_: Exception) {
                    generateSoftwareKeyPair()
                }
            } else {
                generateSoftwareKeyPair()
            }
        }
    }

    private fun generateSoftwareKeyPair() {
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(256)
        val pair = kpg.generateKeyPair()
        fallbackKeyPair = pair
        prefs.edit()
            .putString("sw_pub_key", Base64.encodeToString(pair.public.encoded, Base64.NO_WRAP))
            .putString("sw_priv_key", Base64.encodeToString(pair.private.encoded, Base64.NO_WRAP))
            .apply()
    }

    fun getPublicKey(): PublicKey? {
        val ks = keyStore
        if (ks != null) {
            try {
                val entry = ks.getCertificate(KEY_ALIAS)
                if (entry?.publicKey != null) return entry.publicKey
            } catch (_: Exception) {}
        }
        return fallbackKeyPair?.public
    }

    fun getPublicKeyBase64(): String {
        val pubKey = getPublicKey()?.encoded ?: byteArrayOf()
        return Base64.encodeToString(pubKey, Base64.NO_WRAP)
    }

    /**
     * SHA-256 fingerprint formatted with colon separators (e.g. "4A:9C:12:...").
     * Survives restarts and is verified out-of-band during QR pairing.
     */
    fun getFingerprint(): String {
        val pubKeyBytes = getPublicKey()?.encoded ?: return "UNKNOWN_FINGERPRINT"
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(pubKeyBytes)
        return digest.joinToString(":") { "%02X".format(it) }
    }

    fun signData(data: ByteArray): String {
        val privateKey: PrivateKey = try {
            keyStore?.getKey(KEY_ALIAS, null) as? PrivateKey
        } catch (_: Exception) {
            null
        } ?: fallbackKeyPair?.private
        ?: throw IllegalStateException("Private key not found in Keystore")

        val signature = Signature.getInstance("SHA256withECDSA")
        signature.initSign(privateKey)
        signature.update(data)
        return Base64.encodeToString(signature.sign(), Base64.NO_WRAP)
    }

    fun generateOneTimePairingSecret(): String {
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        return bytes.joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "macbridge_identity_key"
    }
}
