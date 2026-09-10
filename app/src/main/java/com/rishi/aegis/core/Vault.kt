package com.rishi.aegis.core

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * A small on-device secret store. Every secret (a note's body, a TOTP seed) is encrypted with
 * AES-256-GCM under a key that lives in the **Android Keystore** — on this phone that key is held in
 * hardware (TEE/StrongBox), is non-exportable, and never enters app memory in raw form. The
 * ciphertext is kept in app-private SharedPreferences; without the Keystore key it is useless, so a
 * stolen backup or a rooted dump of the prefs file yields nothing readable.
 *
 * Two collections share the same key:
 *  - **notes**  — {id, title(plaintext label), enc(body)}
 *  - **totp**   — {id, issuer, account, enc(base32 seed)}
 */
object Vault {

    private const val ALIAS = "aegis_vault_key"
    private const val PREFS = "aegis_vault"
    private const val K_NOTES = "notes"
    private const val K_TOTP = "totp"
    private const val GCM_TAG_BITS = 128
    private const val IV_LEN = 12

    // ---- Keystore key + AES-GCM ----

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        kg.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return kg.generateKey()
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv
        val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val out = ByteArray(iv.size + ct.size)
        System.arraycopy(iv, 0, out, 0, iv.size)
        System.arraycopy(ct, 0, out, iv.size, ct.size)
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    private fun decrypt(blob: String): String {
        val raw = Base64.decode(blob, Base64.NO_WRAP)
        val iv = raw.copyOfRange(0, IV_LEN)
        val ct = raw.copyOfRange(IV_LEN, raw.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        return String(cipher.doFinal(ct), Charsets.UTF_8)
    }

    /** True once a Keystore key exists — i.e. hardware-backed encryption is available and set up. */
    fun isBackedByKeystore(): Boolean = try {
        secretKey(); true
    } catch (_: Exception) {
        false
    }

    // ---- Notes ----

    data class Note(val id: String, val title: String, val body: String)

    fun notes(ctx: Context): List<Note> = readArray(ctx, K_NOTES).mapNotNull { o ->
        try { Note(o.getString("id"), o.getString("title"), decrypt(o.getString("enc"))) }
        catch (_: Exception) { null }
    }

    fun addNote(ctx: Context, title: String, body: String) {
        val arr = readArray(ctx, K_NOTES).toMutableList()
        arr.add(JSONObject().put("id", newId()).put("title", title).put("enc", encrypt(body)))
        writeArray(ctx, K_NOTES, arr)
    }

    fun deleteNote(ctx: Context, id: String) = removeById(ctx, K_NOTES, id)

    // ---- TOTP ----

    data class TotpEntry(val id: String, val issuer: String, val account: String, val secret: String)

    fun totpEntries(ctx: Context): List<TotpEntry> = readArray(ctx, K_TOTP).mapNotNull { o ->
        try { TotpEntry(o.getString("id"), o.getString("issuer"), o.getString("account"), decrypt(o.getString("enc"))) }
        catch (_: Exception) { null }
    }

    fun addTotp(ctx: Context, issuer: String, account: String, base32Secret: String) {
        val arr = readArray(ctx, K_TOTP).toMutableList()
        arr.add(
            JSONObject().put("id", newId()).put("issuer", issuer)
                .put("account", account).put("enc", encrypt(base32Secret))
        )
        writeArray(ctx, K_TOTP, arr)
    }

    fun deleteTotp(ctx: Context, id: String) = removeById(ctx, K_TOTP, id)

    // ---- shared JSON persistence ----

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun readArray(ctx: Context, key: String): List<JSONObject> {
        val s = prefs(ctx).getString(key, null) ?: return emptyList()
        return try {
            val a = JSONArray(s)
            (0 until a.length()).map { a.getJSONObject(it) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun writeArray(ctx: Context, key: String, items: List<JSONObject>) {
        val a = JSONArray()
        items.forEach { a.put(it) }
        prefs(ctx).edit().putString(key, a.toString()).apply()
    }

    private fun removeById(ctx: Context, key: String, id: String) {
        writeArray(ctx, key, readArray(ctx, key).filter { it.optString("id") != id })
    }

    private var counter = 0L
    private fun newId(): String = "e${System.currentTimeMillis()}_${counter++}"
}
