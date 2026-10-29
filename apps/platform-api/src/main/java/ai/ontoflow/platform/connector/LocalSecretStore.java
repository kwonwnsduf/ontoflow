package ai.ontoflow.platform.connector;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

@Component
public class LocalSecretStore implements SecretStore {
    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public LocalSecretStore(@Value("${ontoflow.connector.encryption-key}") String configuredKey) {
        if (configuredKey == null || configuredKey.length() < 32) {
            throw new IllegalArgumentException("CONNECTOR_ENCRYPTION_KEY must contain at least 32 characters");
        }
        try {
            key = new SecretKeySpec(MessageDigest.getInstance("SHA-256")
                    .digest(configuredKey.getBytes(StandardCharsets.UTF_8)), "AES");
        } catch (Exception e) {
            throw new IllegalStateException("Cannot initialize connector secret storage", e);
        }
    }

    @Override
    public String seal(String plaintext) {
        try {
            byte[] nonce = new byte[12];
            random.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, nonce));
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] stored = new byte[nonce.length + encrypted.length];
            System.arraycopy(nonce, 0, stored, 0, nonce.length);
            System.arraycopy(encrypted, 0, stored, nonce.length, encrypted.length);
            return Base64.getEncoder().encodeToString(stored);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot encrypt connector credential", e);
        }
    }

    @Override
    public String open(String ciphertext) {
        try {
            byte[] stored = Base64.getDecoder().decode(ciphertext);
            if (stored.length < 29) throw new IllegalArgumentException("Invalid ciphertext");
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, stored, 0, 12));
            return new String(cipher.doFinal(stored, 12, stored.length - 12), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot decrypt connector credential", e);
        }
    }
}
