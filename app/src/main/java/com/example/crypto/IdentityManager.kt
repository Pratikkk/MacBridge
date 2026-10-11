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
class IdentityManager private constructor(private val context: Context,
    private val allowSoftwareFallback: Boolean, private val keyStore: KeyStore?,
    private val generateKey: () -> Unit = ::generateAndroidKey) {

    constructor(context: Context, allowSoftwareFallback: Boolean = false) :
        this(context, allowSoftwareFallback, openKeyStore())

    internal constructor(context: Context, keyStoreForTest: KeyStore, generateForTest: () -> Unit = ::generateAndroidKey) :
        this(context, false, keyStoreForTest, generateForTest)

    private val prefs = context.getSharedPreferences("macbridge_identity_prefs", Context.MODE_PRIVATE)

    private var fallbackKeyPair: KeyPair? = null

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
        if (!allowSoftwareFallback) {
            validateIdentity()
            if (!prefs.getBoolean("identity_created", false))
                check(prefs.edit().putBoolean("identity_created", true).commit())
        }
    }

    private fun ensureIdentityKeyExists() {
        val ks = keyStore
        if (ks != null) {
            try {
                if (!ks.containsAlias(KEY_ALIAS)) {
                    // An existing device ID means this is recovery, not first use.
                    // Never silently replace a lost/revoked production identity.
                    if (!allowSoftwareFallback && (prefs.contains("device_id") || prefs.getBoolean("identity_created", false))) {
                        throw IdentityAccessException(IdentityFailure.MISSING,
                            IllegalStateException("Existing identity is missing; explicit identity recovery is required"))
                    }
                    generateKey()
                }
                if (!allowSoftwareFallback) prefs.edit().remove("sw_pub_key").remove("sw_priv_key").apply()
                return
            } catch (e: Exception) {
                if (!allowSoftwareFallback) throw identityAccessFailure(e)
            }
        }

        if (!allowSoftwareFallback) throw IdentityAccessException(IdentityFailure.UNAVAILABLE)

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

    private fun validateIdentity() {
        try {
            val public = keyStore?.getCertificate(KEY_ALIAS)?.publicKey
                ?: throw IdentityAccessException(IdentityFailure.MISSING)
            val private = keyStore.getKey(KEY_ALIAS, null) as? PrivateKey
                ?: throw IdentityAccessException(IdentityFailure.MISSING)
            // Presence alone does not establish that an invalidated key can sign.
            val probe = "MacBridge identity availability".toByteArray(Charsets.UTF_8)
            val signer = Signature.getInstance("SHA256withECDSA")
            signer.initSign(private); signer.update(probe)
            val signature = signer.sign()
            val verifier = Signature.getInstance("SHA256withECDSA")
            verifier.initVerify(public); verifier.update(probe)
            if (!verifier.verify(signature)) throw IdentityAccessException(IdentityFailure.MISSING)
        } catch (error: Exception) { throw identityAccessFailure(error) }
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
        private fun generateAndroidKey() {
            val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE)
            generator.initialize(KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setAlgorithmParameterSpec(java.security.spec.ECGenParameterSpec("secp256r1")).build())
            generator.generateKeyPair()
        }

        /** Called only inside a durable, explicitly confirmed reset. Never a startup fallback. */
        internal fun prepareReplacement(context: Context) {
            val ks = openKeyStore() ?: throw IdentityAccessException(IdentityFailure.UNAVAILABLE)
            prepareReplacement(context, ks, ::generateAndroidKey)
        }

        internal fun prepareReplacement(context: Context, ks: KeyStore, generate: () -> Unit) {
            if (ks.containsAlias(KEY_ALIAS)) {
                try {
                    IdentityManager(context, ks, generate)
                    return // A previous confirmed reset already generated its key.
                } catch (error: IdentityAccessException) {
                    if (error.reason != IdentityFailure.MISSING) throw error
                    ks.deleteEntry(KEY_ALIAS)
                }
            }
            generate()
            // Validate without the missing-alias first-use guard; an old ID is kept
            // until all reset stages have completed, so interruption fails closed.
            IdentityManager(context, ks, generate)
        }

        private fun openKeyStore(): KeyStore? = try {
            KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        } catch (_: Exception) { null }
    }
}
