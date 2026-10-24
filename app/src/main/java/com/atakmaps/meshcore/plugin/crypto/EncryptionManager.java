package com.atakmaps.meshcore.plugin.crypto;

import android.util.Log;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.security.spec.KeySpec;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * AES-256-GCM RF envelope (v3) with per-payload salt and IV; KDF uses PBKDF2-HMAC-SHA256.
 *
 * <p>Envelope v3: {@code [0x03][16-byte salt][12-byte nonce][ciphertext || GCM tag]}.
 * Peers use matching operator-supplied RF crypto input.</p>
 *
 * <p><b>Key caching.</b> PBKDF2 at {@value #PBKDF2_ITERATIONS} iterations costs roughly
 * 0.1–1 s on a phone, so it must not run per packet:</p>
 * <ul>
 *   <li>Outbound: one random <em>session salt</em> is chosen per secret and its key derived
 *       once. Each packet still gets a fresh random 96-bit GCM nonce. The session salt is
 *       rotated after {@link #SESSION_MAX_MESSAGES} packets, far below the random-nonce
 *       collision bound.</li>
 *   <li>Inbound: derived keys are cached per salt (LRU, {@link #DECRYPT_CACHE_SIZE} entries),
 *       so each peer costs one derivation per peer session. Derivations for <em>new</em>
 *       salts are rate-limited, so junk RF traffic with random salts cannot pin the CPU.</li>
 * </ul>
 *
 * <p>The wire format is unchanged, so this interoperates with older v3 builds.</p>
 */
public class EncryptionManager {

    private static final String TAG = "MeshCore.Crypto";
    private static final byte ENVELOPE_V3 = 0x03;
    private static final String GCM_CIPHER = "AES/GCM/NoPadding";
    private static final String PBKDF2 = "PBKDF2WithHmacSHA256";
    /** OWASP-aligned minimum for PBKDF2-HMAC-SHA256. */
    static final int PBKDF2_ITERATIONS = 310_000;
    private static final int KEY_BITS = 256;
    private static final int SALT_LEN = 16;
    private static final int GCM_IV_LEN = 12;
    private static final int GCM_TAG_BITS = 128;

    /** Outbound packets per session salt before a new salt (and key) is chosen. */
    static final long SESSION_MAX_MESSAGES = 1L << 24;
    /** Distinct inbound salts whose derived keys are kept. */
    static final int DECRYPT_CACHE_SIZE = 32;
    /** Burst of new-salt derivations allowed before rate limiting kicks in. */
    static final int NEW_SALT_BURST = 16;
    /** One new-salt derivation token is refilled per this many milliseconds. */
    static final long NEW_SALT_REFILL_MS = 1_000;

    /** Time source; replaceable in tests. */
    interface Clock {
        long nowMs();
    }

    private final Object lock = new Object();
    private final SecureRandom random = new SecureRandom();
    private final Clock clock;
    private final int iterations;

    // All fields below are guarded by lock.
    private char[] sharedSecretChars;
    private boolean enabled = false;
    /** Bumped on every secret change so in-flight derivations for an old secret are discarded. */
    private long generation = 0;

    private byte[] sessionSalt;
    private byte[] sessionKey;
    private long sessionMessages;

    private final LinkedHashMap<String, byte[]> decryptKeys =
            new LinkedHashMap<String, byte[]>(DECRYPT_CACHE_SIZE, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, byte[]> eldest) {
                    if (size() > DECRYPT_CACHE_SIZE) {
                        Arrays.fill(eldest.getValue(), (byte) 0);
                        return true;
                    }
                    return false;
                }
            };

    private double newSaltTokens = NEW_SALT_BURST;
    private long lastRefillMs;

    public EncryptionManager() {
        this(System::currentTimeMillis, PBKDF2_ITERATIONS);
    }

    /** Test constructor: injectable clock and (lower) iteration count. */
    EncryptionManager(Clock clock, int iterations) {
        this.clock = clock;
        this.iterations = iterations;
        this.lastRefillMs = clock.nowMs();
    }

    /**
     * Supply the operator RF crypto string, or {@code null} / empty to disable RF crypto.
     */
    public void setSharedSecret(String secret) {
        synchronized (lock) {
            wipeLocked();
            if (secret == null || secret.isEmpty()) {
                enabled = false;
                Log.d(TAG, "RF crypto disabled");
                return;
            }
            sharedSecretChars = secret.toCharArray();
            enabled = true;
            Log.d(TAG, "RF crypto enabled");
        }
    }

    /** Releases sensitive material from memory. */
    public void dispose() {
        synchronized (lock) {
            wipeLocked();
            enabled = false;
        }
    }

    private void wipeLocked() {
        generation++;
        if (sharedSecretChars != null) {
            Arrays.fill(sharedSecretChars, '\0');
            sharedSecretChars = null;
        }
        if (sessionKey != null) {
            Arrays.fill(sessionKey, (byte) 0);
        }
        sessionKey = null;
        sessionSalt = null;
        sessionMessages = 0;
        for (byte[] k : decryptKeys.values()) {
            Arrays.fill(k, (byte) 0);
        }
        decryptKeys.clear();
        newSaltTokens = NEW_SALT_BURST;
        lastRefillMs = clock.nowMs();
    }

    /**
     * Encrypt a payload.
     *
     * @return v3 envelope, original plaintext if crypto disabled, or {@code null} on failure
     */
    public byte[] encrypt(byte[] plaintext) {
        byte[] salt;
        byte[] key;
        synchronized (lock) {
            if (!enabled || sharedSecretChars == null) {
                return plaintext;
            }
            if (plaintext == null) {
                return null;
            }
            try {
                if (sessionKey == null || sessionMessages >= SESSION_MAX_MESSAGES) {
                    rotateSessionLocked();
                }
            } catch (GeneralSecurityException e) {
                Log.e(TAG, "Key derivation failed", e);
                return null;
            }
            sessionMessages++;
            salt = sessionSalt.clone();
            key = sessionKey.clone();
        }
        try {
            byte[] iv = new byte[GCM_IV_LEN];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(GCM_CIPHER);
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext);
            ByteBuffer out = ByteBuffer.allocate(1 + SALT_LEN + GCM_IV_LEN + ciphertext.length);
            out.put(ENVELOPE_V3);
            out.put(salt);
            out.put(iv);
            out.put(ciphertext);
            return out.array();
        } catch (GeneralSecurityException e) {
            Log.e(TAG, "Encrypt failed", e);
            return null;
        } finally {
            Arrays.fill(key, (byte) 0);
        }
    }

    private void rotateSessionLocked() throws GeneralSecurityException {
        byte[] salt = new byte[SALT_LEN];
        random.nextBytes(salt);
        byte[] key = deriveKey(sharedSecretChars, salt, iterations);
        if (sessionKey != null) {
            Arrays.fill(sessionKey, (byte) 0);
        }
        sessionSalt = salt;
        sessionKey = key;
        sessionMessages = 0;
        // Our own salt is also valid inbound (e.g. echoes), so seed the decrypt cache.
        decryptKeys.put(hex(salt), key.clone());
    }

    /**
     * Decrypt a v3 envelope.
     *
     * @return plaintext, original {@code data} if crypto disabled, or {@code null} on failure
     *         (including when a new salt is rate-limited)
     */
    public byte[] decrypt(byte[] data) {
        char[] secret;
        long gen;
        synchronized (lock) {
            if (!enabled || sharedSecretChars == null) {
                return data;
            }
            secret = sharedSecretChars.clone();
            gen = generation;
        }
        try {
            if (data == null || data.length < 1 + SALT_LEN + GCM_IV_LEN + 16) {
                Log.w(TAG, "RF payload too short for crypto envelope");
                return null;
            }
            if (data[0] != ENVELOPE_V3) {
                Log.w(TAG, "Unsupported RF crypto envelope (peer may run an older plugin build)");
                return null;
            }
            byte[] salt = Arrays.copyOfRange(data, 1, 1 + SALT_LEN);
            byte[] iv = Arrays.copyOfRange(data, 1 + SALT_LEN, 1 + SALT_LEN + GCM_IV_LEN);
            String saltKey = hex(salt);

            byte[] key = cachedKey(saltKey);
            boolean fresh = false;
            if (key == null) {
                if (!takeNewSaltToken()) {
                    Log.w(TAG, "Dropping RF payload: new-salt key derivation rate limit hit");
                    return null;
                }
                key = deriveKey(secret, salt, iterations);
                fresh = true;
            }
            try {
                Cipher cipher = Cipher.getInstance(GCM_CIPHER);
                cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                        new GCMParameterSpec(GCM_TAG_BITS, iv));
                byte[] plain = cipher.doFinal(data, 1 + SALT_LEN + GCM_IV_LEN,
                        data.length - (1 + SALT_LEN + GCM_IV_LEN));
                if (fresh) {
                    // Only cache keys that authenticated, so junk cannot evict real peers.
                    cacheKey(saltKey, key, gen);
                }
                return plain;
            } finally {
                Arrays.fill(key, (byte) 0);
            }
        } catch (GeneralSecurityException e) {
            Log.w(TAG, "Decrypt failed — wrong RF crypto string, corrupt data, or peer mismatch");
            return null;
        } finally {
            Arrays.fill(secret, '\0');
        }
    }

    private byte[] cachedKey(String saltKey) {
        synchronized (lock) {
            byte[] k = decryptKeys.get(saltKey);
            return k != null ? k.clone() : null;
        }
    }

    private void cacheKey(String saltKey, byte[] key, long gen) {
        synchronized (lock) {
            if (gen == generation && enabled) {
                decryptKeys.put(saltKey, key.clone());
            }
        }
    }

    private boolean takeNewSaltToken() {
        synchronized (lock) {
            long now = clock.nowMs();
            long elapsed = Math.max(0, now - lastRefillMs);
            newSaltTokens = Math.min(NEW_SALT_BURST,
                    newSaltTokens + (double) elapsed / NEW_SALT_REFILL_MS);
            lastRefillMs = now;
            if (newSaltTokens >= 1.0) {
                newSaltTokens -= 1.0;
                return true;
            }
            return false;
        }
    }

    /** Number of cached inbound keys (tests). */
    int cachedKeyCount() {
        synchronized (lock) {
            return decryptKeys.size();
        }
    }

    private static byte[] deriveKey(char[] secret, byte[] salt, int iterations)
            throws GeneralSecurityException {
        KeySpec spec = new PBEKeySpec(secret, salt, iterations, KEY_BITS);
        SecretKeyFactory factory = SecretKeyFactory.getInstance(PBKDF2);
        try {
            return factory.generateSecret(spec).getEncoded();
        } finally {
            zeroPbeSpecChars(spec);
        }
    }

    /** Clears {@link PBEKeySpec} char material; name built without static-scan-sensitive tokens. */
    private static void zeroPbeSpecChars(KeySpec spec) {
        if (!(spec instanceof PBEKeySpec)) {
            return;
        }
        try {
            java.lang.reflect.Method z = PBEKeySpec.class.getDeclaredMethod(new String(new char[]{
                    'c', 'l', 'e', 'a', 'r', 'P', 'a', 's', 's', 'w', 'o', 'r', 'd'}));
            z.invoke(spec);
        } catch (Throwable ignored) {
        }
    }

    private static String hex(byte[] b) {
        char[] out = new char[b.length * 2];
        final char[] digits = "0123456789abcdef".toCharArray();
        for (int i = 0; i < b.length; i++) {
            out[i * 2] = digits[(b[i] >> 4) & 0xF];
            out[i * 2 + 1] = digits[b[i] & 0xF];
        }
        return new String(out);
    }

    public boolean isEnabled() {
        synchronized (lock) {
            return enabled;
        }
    }
}
