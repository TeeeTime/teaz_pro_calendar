package pro.teaz.calendar.account;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

import pro.teaz.calendar.CalendarProperties;

/**
 * Encrypts the app-specific passwords for the database: AES-256-GCM with
 * {@code calendar.encryption-key} (CALENDAR_ENCRYPTION_KEY), a random IV per
 * value, and the user id as associated data, so a value only decrypts for the
 * user it was stored for. Stored as base64 of IV + ciphertext.
 *
 * Without a key, outside prod, a fixed key that's in this source file is
 * used: fine for local dev, worthless as protection. Prod doesn't start
 * without a key.
 */
@Component
public class PasswordCipher {

	private static final Logger log = LoggerFactory.getLogger(PasswordCipher.class);

	private static final String ALGORITHM = "AES/GCM/NoPadding";
	private static final int IV_BYTES = 12;
	private static final int TAG_BITS = 128;
	private static final byte[] DEV_KEY = "teaz-calendar-local-dev-only-key".getBytes(StandardCharsets.US_ASCII);

	private final SecretKey key;
	private final SecureRandom random = new SecureRandom();

	PasswordCipher(CalendarProperties properties, Environment environment) {
		String configured = properties.encryptionKey();
		byte[] bytes;
		if (configured == null || configured.isBlank()) {
			if (environment.acceptsProfiles(Profiles.of("prod"))) {
				throw new IllegalStateException("Set CALENDAR_ENCRYPTION_KEY (openssl rand -base64 32), see README.");
			}
			log.warn("No calendar.encryption-key set: using the public local-dev key for stored passwords.");
			bytes = DEV_KEY;
		} else {
			try {
				bytes = Base64.getDecoder().decode(configured.trim());
			} catch (IllegalArgumentException e) {
				throw new IllegalStateException("calendar.encryption-key is not valid base64.");
			}
		}
		if (bytes.length != 32) {
			throw new IllegalStateException("calendar.encryption-key must be 32 bytes (openssl rand -base64 32).");
		}
		this.key = new SecretKeySpec(bytes, "AES");
	}

	public String encrypt(String userId, String password) {
		byte[] iv = new byte[IV_BYTES];
		random.nextBytes(iv);
		try {
			Cipher cipher = Cipher.getInstance(ALGORITHM);
			cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
			cipher.updateAAD(userId.getBytes(StandardCharsets.UTF_8));
			byte[] encrypted = cipher.doFinal(password.getBytes(StandardCharsets.UTF_8));
			return Base64.getEncoder().encodeToString(ByteBuffer.allocate(iv.length + encrypted.length)
					.put(iv).put(encrypted).array());
		} catch (GeneralSecurityException e) {
			throw new IllegalStateException("Encrypting failed.", e);
		}
	}

	/** @throws IllegalStateException with another key, another user, or tampered data */
	public String decrypt(String userId, String stored) {
		try {
			byte[] data = Base64.getDecoder().decode(stored);
			Cipher cipher = Cipher.getInstance(ALGORITHM);
			cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, data, 0, IV_BYTES));
			cipher.updateAAD(userId.getBytes(StandardCharsets.UTF_8));
			return new String(cipher.doFinal(data, IV_BYTES, data.length - IV_BYTES), StandardCharsets.UTF_8);
		} catch (GeneralSecurityException | IllegalArgumentException e) {
			throw new IllegalStateException("The stored password can't be decrypted (was the key changed?).", e);
		}
	}
}
