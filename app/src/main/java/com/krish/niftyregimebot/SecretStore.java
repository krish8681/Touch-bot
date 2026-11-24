package com.krish.niftyregimebot;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Keeps the Dhan token and Telegram token encrypted with a key held in the Android Keystore. */
public final class SecretStore {
    private static final String ALIAS = "niftyregimebot_secrets";
    private final SharedPreferences prefs;

    public SecretStore(Context c) {
        prefs = c.getSharedPreferences("secrets", Context.MODE_PRIVATE);
    }

    public void put(String key, String value) {
        if (value == null || value.isEmpty()) { prefs.edit().remove(key).apply(); return; }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key());
            byte[] iv = cipher.getIV();
            byte[] enc = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
            String packed = Base64.encodeToString(iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(enc, Base64.NO_WRAP);
            prefs.edit().putString(key, packed).commit();
        } catch (Exception e) {
            throw new IllegalStateException("Could not encrypt secret: " + e.getMessage(), e);
        }
    }

    public String get(String key) {
        String packed = prefs.getString(key, null);
        if (packed == null) return "";
        try {
            String[] parts = packed.split(":", 2);
            byte[] iv = Base64.decode(parts[0], Base64.NO_WRAP);
            byte[] enc = Base64.decode(parts[1], Base64.NO_WRAP);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(enc), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    private SecretKey key() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (ks.containsAlias(ALIAS)) return ((KeyStore.SecretKeyEntry) ks.getEntry(ALIAS, null)).getSecretKey();
        KeyGenerator g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        g.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return g.generateKey();
    }
}
