package org.moxie.confer.proxy.attestation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.interfaces.XECPublicKey;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AttestationServiceTest {

    @Mock
    private KeyPair mockKeyPair;

    private AttestationService service;

    @BeforeEach
    void setUp() {
    }

    @Test
    void getRawPublicKey_validKeyLength_returnsRawKey() throws NoSuchAlgorithmException {
        KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("X25519");
        KeyPair keyPair = keyPairGenerator.generateKeyPair();
        service = new TestableAttestationService(keyPair);

        byte[] rawKey = service.getRawPublicKey();

        assertEquals(32, rawKey.length, "Should return a 32-byte raw public key");
    }

    @Test
    void getRawPublicKey_validKey_returnsCorrectly() {
        // A valid 32-byte X25519 key as BigInteger
        byte[] keyBytes = new byte[32];
        keyBytes[0] = (byte) 0x42;
        keyBytes[31] = (byte) 0x99;
        BigInteger keyValue = new BigInteger(1, keyBytes); // positive number

        XECPublicKey mockPublicKey = mock(XECPublicKey.class);
        when(mockPublicKey.getU()).thenReturn(keyValue);

        mockKeyPair = mock(KeyPair.class);
        when(mockKeyPair.getPublic()).thenReturn(mockPublicKey);
        service = new TestableAttestationService(mockKeyPair);

        byte[] result = service.getRawPublicKey();

        assertEquals(32, result.length);
        // Verify the reversal: first byte of result should be last byte of original
        assertEquals((byte) 0x99, result[0]);
        assertEquals((byte) 0x42, result[31]);
    }

    @Test
    void getRawPublicKey_smallKey_paddsWithLeadingZeros() {
        // Small key that requires padding
        BigInteger keyValue = BigInteger.valueOf(0x1234);

        XECPublicKey mockPublicKey = mock(XECPublicKey.class);
        when(mockPublicKey.getU()).thenReturn(keyValue);

        mockKeyPair = mock(KeyPair.class);
        when(mockKeyPair.getPublic()).thenReturn(mockPublicKey);
        service = new TestableAttestationService(mockKeyPair);

        byte[] result = service.getRawPublicKey();

        assertEquals(32, result.length);
        assertEquals(0x1234, result[0] + (result[1] * 256));
        // Highest bytes should be zero (padding)
        for (int i = 2; i < 32; i++) {
            assertEquals(0, result[i]);
        }
    }

    @Test
    void getRawPublicKey_keyWithLeadingZeroByte_handlesCorrectly() {
        // BigInteger adds a leading zero byte if high bit is set
        byte[] keyBytes = new byte[32];
        keyBytes[0] = (byte) 0xFF; // high bit set
        BigInteger keyValue = new BigInteger(1, keyBytes);
        // This will have 33 bytes when calling toByteArray()

        XECPublicKey mockPublicKey = mock(XECPublicKey.class);
        when(mockPublicKey.getU()).thenReturn(keyValue);

        mockKeyPair = mock(KeyPair.class);
        when(mockKeyPair.getPublic()).thenReturn(mockPublicKey);
        service = new TestableAttestationService(mockKeyPair);

        byte[] result = service.getRawPublicKey();

        assertEquals(32, result.length);
        assertEquals((byte) 0xFF, result[31]);
        // Lowest bytes should be zero (padding)
        for (int i = 0; i < 31; i++) {
            assertEquals(0, result[i]);
        }
    }

    @Test
    void getRawPublicKey_invalidLeadingByte_throwsException() {
        // Create a byte array with a non-zero leading byte that BigInteger will preserve
        byte[] keyBytes = new byte[33];
        keyBytes[0] = (byte) 0x7F; // Non-zero but positive (high bit not set, so no extra zero added)
        keyBytes[1] = (byte) 0xFF;
        BigInteger keyValue = new BigInteger(1, keyBytes);

        XECPublicKey mockPublicKey = mock(XECPublicKey.class);
        when(mockPublicKey.getU()).thenReturn(keyValue);

        mockKeyPair = mock(KeyPair.class);
        when(mockKeyPair.getPublic()).thenReturn(mockPublicKey);
        service = new TestableAttestationService(mockKeyPair);

        IllegalStateException exception = assertThrows(IllegalStateException.class, service::getRawPublicKey);
        assertEquals("Expected leading zero byte but got non-zero value", exception.getMessage());
    }

    @Test
    void getRawPublicKey_tooLargeKey_throwsException() {
        // Create a BigInteger that will have 34 bytes in toByteArray()
        byte[] keyBytes = new byte[34];
        keyBytes[0] = (byte) 0x42; // Ensure high bit is not set so leading zero is stripped
        BigInteger keyValue = new BigInteger(1, keyBytes);

        XECPublicKey mockPublicKey = mock(XECPublicKey.class);
        when(mockPublicKey.getU()).thenReturn(keyValue);

        mockKeyPair = mock(KeyPair.class);
        when(mockKeyPair.getPublic()).thenReturn(mockPublicKey);
        service = new TestableAttestationService(mockKeyPair);

        IllegalStateException exception = assertThrows(IllegalStateException.class, service::getRawPublicKey);
        assertTrue(exception.getMessage().contains("at most 33 bytes"));
    }

    @Test
    void getRawPublicKey_wrongKeyType_throwsException() {
        PublicKey mockPublicKey = mock(PublicKey.class);

        mockKeyPair = mock(KeyPair.class);
        when(mockKeyPair.getPublic()).thenReturn(mockPublicKey);
        service = new TestableAttestationService(mockKeyPair);

        IllegalStateException exception = assertThrows(IllegalStateException.class, service::getRawPublicKey);
        assertEquals("Expected XECPublicKey but got: " + mockPublicKey.getClass(), exception.getMessage());
    }

    @Test
    void getRawPublicKey_nonXECKey_throwsException() {
        mockKeyPair = mock(KeyPair.class);
        when(mockKeyPair.getPublic()).thenReturn(new NonXECPublicKey());
        service = new TestableAttestationService(mockKeyPair);

        IllegalStateException exception = assertThrows(IllegalStateException.class, service::getRawPublicKey);
        assertEquals("Expected XECPublicKey but got: " + NonXECPublicKey.class, exception.getMessage());
    }

    private static class TestableAttestationService extends AttestationService {
        protected TestableAttestationService(KeyPair serverKeyPair) {
            super(serverKeyPair, null, null, null);
        }

        @Override
        protected boolean needsRefresh(AttestationResponse attestation) {
            return true; // Placeholder
        }

        @Override
        protected AttestationResponse generateAttestation() {
            return null; // Placeholder
        }

        @Override
        public boolean isPlatformSupported() {
            return true; // Placeholder
        }

        @Override
        public String getPlatformType() {
            return "Test"; // Placeholder
        }
    }

    private static class NonXECPublicKey implements java.security.PublicKey {
        @Override
        public String getAlgorithm() {
            return "Non-XEC";
        }

        @Override
        public String getFormat() {
            return "DummyFormat";
        }

        @Override
        public byte[] getEncoded() {
            return new byte[0];
        }
    }
}
