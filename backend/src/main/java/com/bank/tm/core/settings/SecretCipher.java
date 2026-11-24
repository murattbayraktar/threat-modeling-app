package com.bank.tm.core.settings;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * API anahtarlarını veritabanına yazmadan önce AES-256-GCM ile şifreler.
 * Anahtar, ortam değişkeninden gelen bir paroladan (tm.security.settings-encryption-key / TM_SETTINGS_KEY) SHA-256 ile türetilir.
 * Parola tanımlı değilse değerler "plain:" önekiyle saklanır ve ekranda uyarı gösterilir.
 * Canlı ortamda parola kasadan (Vault vb.) verilmeli ve değiştirilmemelidir; değişirse kayıtlı anahtarlar yeniden girilmelidir.
 */
public final class SecretCipher {

    private static final String ENC = "enc:v1:";
    private static final String PLAIN = "plain:";
    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public SecretCipher(String passphrase) {
        if (passphrase == null || passphrase.isBlank()) {
            this.key = null;
        } else {
            try {
                byte[] k = MessageDigest.getInstance("SHA-256").digest(passphrase.getBytes(StandardCharsets.UTF_8));
                this.key = new SecretKeySpec(k, "AES");
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
    }

    public boolean enabled() {
        return key != null;
    }

    public String encrypt(String plain) {
        if (plain == null || plain.isEmpty()) return "";
        if (key == null) return PLAIN + plain;
        try {
            byte[] iv = new byte[12];
            random.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
            byte[] ct = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            return ENC + Base64.getEncoder().encodeToString(ByteBuffer.allocate(iv.length + ct.length).put(iv).put(ct).array());
        } catch (Exception e) {
            throw new IllegalStateException("Şifreleme başarısız", e);
        }
    }

    public String decrypt(String stored) {
        if (stored == null || stored.isEmpty()) return "";
        if (stored.startsWith(PLAIN)) return stored.substring(PLAIN.length());
        if (!stored.startsWith(ENC)) return stored;
        if (key == null) throw new IllegalStateException("Kayıtlı anahtar şifreli ama şifreleme parolası (TM_SETTINGS_KEY) tanımlı değil");
        try {
            byte[] all = Base64.getDecoder().decode(stored.substring(ENC.length()));
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, all, 0, 12));
            return new String(c.doFinal(all, 12, all.length - 12), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Kayıtlı anahtar çözülemedi (şifreleme parolası değişmiş olabilir)", e);
        }
    }
}
