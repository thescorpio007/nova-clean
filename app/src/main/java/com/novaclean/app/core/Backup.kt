package com.novaclean.app.core

import android.util.Base64
import org.json.JSONObject
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

object BackupCrypto {
    private fun key(pass: String, salt: ByteArray): SecretKeySpec {
        val f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1")   // available on Android 7
        val k = f.generateSecret(PBEKeySpec(pass.toCharArray(), salt, 65536, 256)).encoded
        return SecretKeySpec(k, "AES")
    }

    fun encrypt(plain: ByteArray, pass: String): ByteArray {
        val rnd = SecureRandom()
        val salt = ByteArray(16).also { rnd.nextBytes(it) }
        val iv = ByteArray(12).also { rnd.nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key(pass, salt), GCMParameterSpec(128, iv))
        val ct = c.doFinal(plain)
        return JSONObject()
            .put("app", "NovaClean").put("enc", true)
            .put("salt", Base64.encodeToString(salt, Base64.NO_WRAP))
            .put("iv", Base64.encodeToString(iv, Base64.NO_WRAP))
            .put("data", Base64.encodeToString(ct, Base64.NO_WRAP))
            .toString().toByteArray()
    }

    /** Returns null when the password is wrong or the file is damaged. */
    fun decrypt(wrapper: ByteArray, pass: String): ByteArray? = try {
        val o = JSONObject(String(wrapper))
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(
            Cipher.DECRYPT_MODE,
            key(pass, Base64.decode(o.getString("salt"), Base64.NO_WRAP)),
            GCMParameterSpec(128, Base64.decode(o.getString("iv"), Base64.NO_WRAP))
        )
        c.doFinal(Base64.decode(o.getString("data"), Base64.NO_WRAP))
    } catch (e: Exception) {
        null
    }
}

object Backup {
    data class Outcome(val ok: Boolean, val message: String)

    /** The API key is only included when a password protects the file. */
    fun create(ctx: android.content.Context, prefs: Prefs, password: String): Outcome {
        val encrypt = password.isNotEmpty()
        val inner = JSONObject()
            .put("app", "NovaClean").put("format", 1)
            .put("created", SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date()))
            .put("settings", prefs.export(includeKey = encrypt))
        val bytes = if (encrypt) BackupCrypto.encrypt(inner.toString().toByteArray(), password)
        else inner.toString(2).toByteArray()
        val name = "NovaClean-backup-" +
            SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date()) + ".json"
        val ok = NovaFiles.write(ctx, NovaFiles.BACKUPS, name, bytes, "application/json")
        return if (ok) Outcome(
            true,
            "Backup saved: ${NovaFiles.pathOf(NovaFiles.BACKUPS)}/$name" +
                if (encrypt) " (password protected, includes API key)" else " (no password: API key NOT included)"
        ) else Outcome(false, "Could not write to ${NovaFiles.pathOf(NovaFiles.BACKUPS)} (${NovaFiles.lastError}). Grant storage access in Settings → 4.")
    }

    fun isEncrypted(bytes: ByteArray): Boolean =
        runCatching { JSONObject(String(bytes)).optBoolean("enc", false) }.getOrDefault(false)

    fun restore(prefs: Prefs, bytes: ByteArray, password: String): Outcome {
        val plain = if (isEncrypted(bytes)) {
            BackupCrypto.decrypt(bytes, password) ?: return Outcome(false, "Wrong password or damaged file.")
        } else bytes
        return try {
            val o = JSONObject(String(plain))
            if (o.optString("app") != "NovaClean") return Outcome(false, "This is not a Nova Clean backup.")
            prefs.import(o.getJSONObject("settings"))
            Outcome(true, "Restored from backup made ${o.optString("created")}.")
        } catch (e: Exception) {
            Outcome(false, "Could not read this backup file.")
        }
    }
}
