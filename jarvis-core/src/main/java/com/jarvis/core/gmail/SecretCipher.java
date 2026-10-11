package com.jarvis.core.gmail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-256-GCM for secrets Core keeps for its users (refresh tokens of connected accounts). The key comes from
 * {@code JARVIS_SECRETS_KEY} (base64, 32 bytes) or, when unset, from a key file Core creates once with owner-only
 * permissions. A copied database alone therefore reveals no token. Each ciphertext is bound to its owner and
 * service (associated data), so a row moved to another user cannot be decrypted.
 */
@Component
public class SecretCipher {

    private static final Logger LOGGER = LoggerFactory.getLogger(SecretCipher.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String PREFIX = "v1:";
    private final String configuredKey;
    private final Path keyFile;
    private byte[] key;

    @Autowired
    public SecretCipher(@Value("${jarvis.secrets.key:${JARVIS_SECRETS_KEY:}}") String configuredKey,
                        @Value("${jarvis.secrets.key-file:${JARVIS_SECRETS_KEY_FILE:data/secrets.key}}") String keyFile) {
        this.configuredKey = configuredKey == null ? "" : configuredKey.strip();
        this.keyFile = Path.of(keyFile);
        if (!this.configuredKey.isEmpty()) {
            // A wrong key must stop Core at startup, not at the first connection.
            this.key = decodeKey(this.configuredKey);
        }
    }

    SecretCipher(byte[] key) {
        this.configuredKey = "";
        this.keyFile = Path.of("unused");
        this.key = key.clone();
    }

    /** Loaded on first use, so Core without connected accounts never creates a key file. */
    private synchronized byte[] key() {
        if (key == null) {
            key = fileKey(keyFile);
        }
        return key;
    }

    /**
     * Encrypts a secret.
     *
     * @param plain secret text
     * @param context owner and service, e.g. "user-1|gmail"
     * @return printable ciphertext
     */
    public String encrypt(String plain, String context) {
        try {
            byte[] nonce = new byte[12];
            RANDOM.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key(), "AES"), new GCMParameterSpec(128, nonce));
            cipher.updateAAD(context.getBytes(StandardCharsets.UTF_8));
            byte[] sealed = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[nonce.length + sealed.length];
            System.arraycopy(nonce, 0, out, 0, nonce.length);
            System.arraycopy(sealed, 0, out, nonce.length, sealed.length);
            return PREFIX + Base64.getEncoder().encodeToString(out);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Could not encrypt secret", exception);
        }
    }

    /**
     * Decrypts a secret written by {@link #encrypt}.
     *
     * @param sealed ciphertext
     * @param context the same owner and service used to encrypt it
     * @return secret text
     */
    public String decrypt(String sealed, String context) {
        if (sealed == null || !sealed.startsWith(PREFIX)) {
            throw new IllegalStateException("Unknown secret format");
        }
        try {
            byte[] in = Base64.getDecoder().decode(sealed.substring(PREFIX.length()));
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key(), "AES"), new GCMParameterSpec(128, in, 0, 12));
            cipher.updateAAD(context.getBytes(StandardCharsets.UTF_8));
            return new String(cipher.doFinal(in, 12, in.length - 12), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            throw new IllegalStateException("Could not decrypt secret: wrong key or damaged data", exception);
        }
    }

    private static byte[] decodeKey(String base64) {
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("JARVIS_SECRETS_KEY must be base64 of 32 random bytes (openssl rand -base64 32)");
        }
        if (decoded.length != 32) {
            throw new IllegalStateException("JARVIS_SECRETS_KEY must be base64 of 32 random bytes (openssl rand -base64 32)");
        }
        return decoded;
    }

    private static byte[] fileKey(Path file) {
        try {
            if (Files.exists(file)) {
                return decodeKey(Files.readString(file).strip());
            }
            byte[] generated = new byte[32];
            RANDOM.nextBytes(generated);
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(file, Base64.getEncoder().encodeToString(generated));
            try {
                Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException ignored) {
                // Windows development machines: the file stays in the user's own profile.
            }
            LOGGER.warn("[SECRETS] Created {} for connected-account tokens. Back it up: without it saved connections cannot be read.",
                    file.toAbsolutePath());
            return generated;
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("Cannot read or create the secrets key file " + file.toAbsolutePath(), exception);
        }
    }
}
