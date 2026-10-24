package com.atakmaps.meshcore.plugin.crypto;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

public class EncryptionManagerTest {

    /** Low iteration count keeps tests fast; the production default is unchanged. */
    private static final int ITER = 1000;
    private static final byte[] MSG = "<event uid='x'/>".getBytes(StandardCharsets.UTF_8);

    private long now;
    private EncryptionManager.Clock clock;

    @Before
    public void setUp() {
        now = 1_000_000L;
        clock = () -> now;
    }

    private EncryptionManager mgr(String secret) {
        EncryptionManager m = new EncryptionManager(clock, ITER);
        m.setSharedSecret(secret);
        return m;
    }

    /** Builds a v3 envelope the way pre-caching builds did: fresh salt per payload. */
    private static byte[] legacyEnvelope(String secret, byte[] plain) throws Exception {
        SecureRandom r = new SecureRandom();
        byte[] salt = new byte[16];
        byte[] iv = new byte[12];
        r.nextBytes(salt);
        r.nextBytes(iv);
        byte[] key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(new PBEKeySpec(secret.toCharArray(), salt, ITER, 256))
                .getEncoded();
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
        byte[] ct = c.doFinal(plain);
        return ByteBuffer.allocate(1 + 16 + 12 + ct.length)
                .put((byte) 0x03).put(salt).put(iv).put(ct).array();
    }

    @Test
    public void roundTrip() {
        EncryptionManager a = mgr("net-secret");
        EncryptionManager b = mgr("net-secret");
        byte[] env = a.encrypt(MSG);
        assertNotNull(env);
        assertEquals(0x03, env[0]);
        assertEquals(MSG.length + 1 + 16 + 12 + 16, env.length);
        assertArrayEquals(MSG, b.decrypt(env));
    }

    @Test
    public void disabledIsPassthrough() {
        EncryptionManager m = mgr(null);
        assertFalse(m.isEnabled());
        assertSame(MSG, m.encrypt(MSG));
        assertSame(MSG, m.decrypt(MSG));
    }

    @Test
    public void wrongSecretFails() {
        byte[] env = mgr("alpha").encrypt(MSG);
        assertNull(mgr("bravo").decrypt(env));
    }

    @Test
    public void tamperedCiphertextFails() {
        EncryptionManager m = mgr("s");
        byte[] env = m.encrypt(MSG);
        env[env.length - 1] ^= 1;
        assertNull(m.decrypt(env));
    }

    @Test
    public void plaintextAndShortInputFail() {
        EncryptionManager m = mgr("s");
        assertNull(m.decrypt(MSG));
        assertNull(m.decrypt(new byte[10]));
        assertNull(m.decrypt(null));
    }

    @Test
    public void interoperatesWithLegacyPerPacketSaltEnvelopes() throws Exception {
        EncryptionManager m = mgr("shared");
        assertArrayEquals(MSG, m.decrypt(legacyEnvelope("shared", MSG)));
        assertArrayEquals(MSG, m.decrypt(legacyEnvelope("shared", MSG)));
    }

    @Test
    public void sessionSaltIsReusedButNonceIsFresh() {
        EncryptionManager m = mgr("s");
        byte[] e1 = m.encrypt(MSG);
        byte[] e2 = m.encrypt(MSG);
        assertArrayEquals(Arrays.copyOfRange(e1, 1, 17), Arrays.copyOfRange(e2, 1, 17));
        assertFalse(Arrays.equals(Arrays.copyOfRange(e1, 17, 29), Arrays.copyOfRange(e2, 17, 29)));
        assertFalse(Arrays.equals(e1, e2));
    }

    @Test
    public void receiverCachesOneKeyPerPeerSession() {
        EncryptionManager tx = mgr("s");
        EncryptionManager rx = mgr("s");
        for (int i = 0; i < 50; i++) {
            assertArrayEquals(MSG, rx.decrypt(tx.encrypt(MSG)));
        }
        // rx's own session salt is not created until it sends, so only tx's salt is cached.
        assertEquals(1, rx.cachedKeyCount());
    }

    @Test
    public void junkSaltsAreRateLimitedAndNotCached() throws Exception {
        EncryptionManager rx = mgr("s");
        SecureRandom r = new SecureRandom();
        for (int i = 0; i < EncryptionManager.NEW_SALT_BURST + 5; i++) {
            byte[] junk = new byte[80];
            r.nextBytes(junk);
            junk[0] = 0x03;
            assertNull(rx.decrypt(junk));
        }
        assertEquals(0, rx.cachedKeyCount());

        // Burst exhausted: a genuine new peer is dropped until a token refills...
        byte[] fromNewPeer = legacyEnvelope("s", MSG);
        assertNull(rx.decrypt(fromNewPeer));
        // ...and accepted once time passes.
        now += EncryptionManager.NEW_SALT_REFILL_MS;
        assertArrayEquals(MSG, rx.decrypt(fromNewPeer));
        assertEquals(1, rx.cachedKeyCount());
    }

    @Test
    public void cachedPeersAreNotAffectedByRateLimit() throws Exception {
        EncryptionManager tx = mgr("s");
        EncryptionManager rx = mgr("s");
        assertNotNull(rx.decrypt(tx.encrypt(MSG)));
        SecureRandom r = new SecureRandom();
        for (int i = 0; i < EncryptionManager.NEW_SALT_BURST + 5; i++) {
            byte[] junk = new byte[80];
            r.nextBytes(junk);
            junk[0] = 0x03;
            rx.decrypt(junk);
        }
        assertArrayEquals(MSG, rx.decrypt(tx.encrypt(MSG)));
    }

    @Test
    public void changingSecretClearsCachedKeys() {
        EncryptionManager tx = mgr("old");
        EncryptionManager rx = mgr("old");
        byte[] env = tx.encrypt(MSG);
        assertNotNull(rx.decrypt(env));
        rx.setSharedSecret("new");
        assertEquals(0, rx.cachedKeyCount());
        assertNull(rx.decrypt(env));
        assertTrue(rx.isEnabled());
    }

    @Test
    public void decryptCacheIsBounded() throws Exception {
        EncryptionManager rx = mgr("s");
        for (int i = 0; i < EncryptionManager.DECRYPT_CACHE_SIZE + 10; i++) {
            now += EncryptionManager.NEW_SALT_REFILL_MS;
            assertNotNull(rx.decrypt(legacyEnvelope("s", MSG)));
        }
        assertEquals(EncryptionManager.DECRYPT_CACHE_SIZE, rx.cachedKeyCount());
    }
}
