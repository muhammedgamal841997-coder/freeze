package com.example.netfreeze

import android.content.Context
import android.util.Base64
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

object PinManager {
    private const val PREFS = "NetFreezePrefs"
    private const val SALT_KEY = "pin_salt"
    private const val HASH_KEY = "pin_hash"

    fun hasPin(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).contains(HASH_KEY)

    fun savePin(context: Context, pin: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val hash = derive(pin, salt)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(SALT_KEY, Base64.encodeToString(salt, Base64.NO_WRAP))
            .putString(HASH_KEY, Base64.encodeToString(hash, Base64.NO_WRAP))
            .apply()
    }

    fun verify(context: Context, pin: String): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val saltText = prefs.getString(SALT_KEY, null) ?: return false
        val hashText = prefs.getString(HASH_KEY, null) ?: return false
        return try {
            val salt = Base64.decode(saltText, Base64.NO_WRAP)
            val expected = Base64.decode(hashText, Base64.NO_WRAP)
            java.security.MessageDigest.isEqual(expected, derive(pin, salt))
        } catch (e: Exception) { false }
    }

    private fun derive(pin: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, 120_000, 256)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally { spec.clearPassword() }
    }
}
