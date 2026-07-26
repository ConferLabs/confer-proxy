package org.moxie.confer.proxy.crypto;

import org.junit.jupiter.api.Test;

import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.Random;

import static org.moxie.confer.proxy.crypto.ChunkedCipher.Mode.DECRYPT;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkedCipherOutputStreamTest {

  private static final int    CHUNK_SIZE = 64 * 1024;
  private static final byte[] RAW_KEY    = new byte[32];
  private static final String CROSS_PLATFORM_CIPHERTEXT =
      "0100010000da486a0b25d25f5fa823ead0555256f0"
          + "1023e0057b4218d386311214ab0a7486d93b8644409ca7db054dc3135d46b3bacd2cd4789233f215d6";

  @Test
  void producesCiphertextAtEveryChunkBoundary() throws Exception {
    int[] sizes = new int[] {
        0,
        1,
        CHUNK_SIZE - 1,
        CHUNK_SIZE,
        CHUNK_SIZE + 1,
        (2 * CHUNK_SIZE) - 1,
        2 * CHUNK_SIZE,
        (2 * CHUNK_SIZE) + 1,
        (3 * CHUNK_SIZE) - 1,
        3 * CHUNK_SIZE,
        (3 * CHUNK_SIZE) + 1
    };

    for (int size : sizes) {
      byte[] plaintext = plaintext(size);
      assertArrayEquals(plaintext, decrypt(encrypt(plaintext)), "size=" + size);
    }
  }

  @Test
  void producesTheCrossPlatformCiphertextFormat() throws Exception {
    byte[] entropy = hex(
        "da486a0b25d25f5fa823ead0555256f0"
            + "1023e0057b4218d386311214");
    ByteArrayOutputStream encrypted = new ByteArrayOutputStream();
    try (ChunkedCipherOutputStream output = new ChunkedCipherOutputStream(
        encrypted,
        key(),
        new FixedSecureRandom(entropy)))
    {
      output.write("Hello, World!".getBytes());
    }

    assertArrayEquals(hex(CROSS_PLATFORM_CIPHERTEXT), encrypted.toByteArray());
  }

  @Test
  void acceptsRandomizedPlaintextAndWriteBoundaries() throws Exception {
    Random random = new Random(0x4f5554505554L);

    for (int iteration = 0; iteration < 100; iteration++) {
      byte[] plaintext = new byte[random.nextInt((3 * CHUNK_SIZE) + 2)];
      random.nextBytes(plaintext);
      ByteArrayOutputStream encrypted = new ByteArrayOutputStream();
      try (ChunkedCipherOutputStream output = new ChunkedCipherOutputStream(encrypted, key())) {
        int offset = 0;
        while (offset < plaintext.length) {
          int length = Math.min(1 + random.nextInt(8192), plaintext.length - offset);
          output.write(plaintext, offset, length);
          offset += length;
        }
      }

      assertArrayEquals(
          plaintext,
          decrypt(encrypted.toByteArray()),
          "iteration=" + iteration + ", size=" + plaintext.length);
    }
  }

  @Test
  void supportsSingleByteWritesIncludingUnsignedValues() throws Exception {
    ByteArrayOutputStream encrypted = new ByteArrayOutputStream();
    try (ChunkedCipherOutputStream output = new ChunkedCipherOutputStream(encrypted, key())) {
      output.write(0);
      output.write(128);
      output.write(255);
      output.write(511);
    }

    assertArrayEquals(
        new byte[] {0, (byte) 128, (byte) 255, (byte) 255},
        decrypt(encrypted.toByteArray()));
  }

  @Test
  void obeysOutputStreamRangeAndZeroLengthContracts() throws Exception {
    ByteArrayOutputStream encrypted = new ByteArrayOutputStream();
    ChunkedCipherOutputStream output = new ChunkedCipherOutputStream(encrypted, key());
    byte[] source = new byte[8];

    output.write(source, 4, 0);
    assertEquals(ChunkedCipherTestVector.HEADER_SIZE, encrypted.size());
    assertThrows(IndexOutOfBoundsException.class, () -> output.write(source, -1, 1));
    assertThrows(IndexOutOfBoundsException.class, () -> output.write(source, 8, 1));
    assertThrows(IndexOutOfBoundsException.class, () -> output.write(source, 0, 9));
    assertThrows(NullPointerException.class, () -> output.write(null, 0, 1));

    output.close();
  }

  @Test
  void emitsOnlyChunksKnownToBeNonFinalBeforeClose() throws Exception {
    ByteArrayOutputStream encrypted = new ByteArrayOutputStream();
    ChunkedCipherOutputStream output = new ChunkedCipherOutputStream(encrypted, key());

    output.write(plaintext(CHUNK_SIZE));
    assertEquals(ChunkedCipherTestVector.HEADER_SIZE, encrypted.size());

    output.write(1);
    assertEquals(
        ChunkedCipherTestVector.HEADER_SIZE
            + ChunkedCipherTestVector.IV_SIZE
            + CHUNK_SIZE
            + ChunkedCipherTestVector.TAG_SIZE,
        encrypted.size());

    output.close();
  }

  @Test
  void flushesTheUnderlyingStreamAndPropagatesFailures() throws Exception {
    FaultInjectingOutputStream successful = new FaultInjectingOutputStream(0, false, false);
    ChunkedCipherOutputStream output = new ChunkedCipherOutputStream(successful, key());

    output.flush();
    assertEquals(1, successful.flushCalls());
    output.close();

    FaultInjectingOutputStream failing = new FaultInjectingOutputStream(0, true, false);
    ChunkedCipherOutputStream failingOutput = new ChunkedCipherOutputStream(failing, key());
    assertThrows(IOException.class, failingOutput::flush);
    assertEquals(1, failing.flushCalls());
    failingOutput.close();
  }

  @Test
  void closesTheUnderlyingStreamExactlyOnceAndRejectsFurtherOperations() throws Exception {
    FaultInjectingOutputStream encrypted = new FaultInjectingOutputStream(0, false, false);
    ChunkedCipherOutputStream output = new ChunkedCipherOutputStream(encrypted, key());

    output.close();
    output.close();

    assertEquals(1, encrypted.closeCalls());
    assertThrows(IOException.class, () -> output.write(1));
    assertThrows(IOException.class, () -> output.write(new byte[0], 0, 0));
    assertThrows(IOException.class, output::flush);
  }

  @Test
  void propagatesHeaderWriteFailure() {
    FaultInjectingOutputStream encrypted = new FaultInjectingOutputStream(1, false, false);

    assertThrows(
        IOException.class,
        () -> new ChunkedCipherOutputStream(encrypted, key()));
  }

  @Test
  void propagatesNonFinalChunkWriteFailure() throws Exception {
    FaultInjectingOutputStream encrypted = new FaultInjectingOutputStream(2, false, false);
    ChunkedCipherOutputStream output = new ChunkedCipherOutputStream(encrypted, key());

    assertThrows(IOException.class, () -> output.write(plaintext(CHUNK_SIZE + 1)));
    output.close();
    assertEquals(1, encrypted.closeCalls());
  }

  @Test
  void propagatesFinalChunkWriteFailureAndStillClosesTheUnderlyingStream() throws Exception {
    FaultInjectingOutputStream encrypted = new FaultInjectingOutputStream(2, false, false);
    ChunkedCipherOutputStream output = new ChunkedCipherOutputStream(encrypted, key());
    output.write(1);

    assertThrows(IOException.class, output::close);
    output.close();
    assertEquals(1, encrypted.closeCalls());
  }

  @Test
  void propagatesUnderlyingCloseFailure() throws Exception {
    FaultInjectingOutputStream encrypted = new FaultInjectingOutputStream(0, false, true);
    ChunkedCipherOutputStream output = new ChunkedCipherOutputStream(encrypted, key());

    IOException failure = assertThrows(IOException.class, output::close);
    output.close();

    assertEquals("Injected close failure", failure.getMessage());
    assertEquals(1, encrypted.closeCalls());
  }

  @Test
  void suppressesCloseFailureBehindFinalWriteFailure() throws Exception {
    FaultInjectingOutputStream encrypted = new FaultInjectingOutputStream(2, false, true);
    ChunkedCipherOutputStream output = new ChunkedCipherOutputStream(encrypted, key());

    IOException failure = assertThrows(IOException.class, output::close);

    assertEquals("Injected write failure", failure.getMessage());
    assertEquals(1, failure.getSuppressed().length);
    assertEquals("Injected close failure", failure.getSuppressed()[0].getMessage());
    assertEquals(1, encrypted.closeCalls());
  }

  @Test
  void wrapsCipherFailureDuringUpdate() throws Exception {
    FaultInjectingOutputStream encrypted = new FaultInjectingOutputStream(0, false, false);
    ChunkedCipherOutputStream output = new ChunkedCipherOutputStream(
        encrypted,
        new SecretKeySpec(new byte[15], "AES"));

    IOException updateFailure = assertThrows(
        IOException.class,
        () -> output.write(plaintext(CHUNK_SIZE + 1)));
    IOException closeFailure = assertThrows(IOException.class, output::close);

    assertEquals("Chunked encryption failed", updateFailure.getMessage());
    assertEquals("Chunked encryption failed", closeFailure.getMessage());
    assertEquals(1, encrypted.closeCalls());
  }

  @Test
  void wrapsCipherFailureDuringCloseAndSuppressesUnderlyingCloseFailure() throws Exception {
    FaultInjectingOutputStream encrypted = new FaultInjectingOutputStream(0, false, true);
    ChunkedCipherOutputStream output = new ChunkedCipherOutputStream(
        encrypted,
        new SecretKeySpec(new byte[15], "AES"));

    IOException failure = assertThrows(IOException.class, output::close);

    assertEquals("Chunked encryption failed", failure.getMessage());
    assertTrue(failure.getCause() instanceof java.security.GeneralSecurityException);
    assertEquals(1, failure.getSuppressed().length);
    assertEquals("Injected close failure", failure.getSuppressed()[0].getMessage());
    assertEquals(1, encrypted.closeCalls());
  }

  @Test
  void rejectsNullDependencies() {
    assertThrows(NullPointerException.class, () -> new ChunkedCipherOutputStream(null, key()));
    assertThrows(
        NullPointerException.class,
        () -> new ChunkedCipherOutputStream(new ByteArrayOutputStream(), null));
    assertThrows(
        NullPointerException.class,
        () -> new ChunkedCipherOutputStream(new ByteArrayOutputStream(), key(), null));
  }

  private byte[] encrypt(byte[] plaintext) throws IOException {
    ByteArrayOutputStream encrypted = new ByteArrayOutputStream();
    try (ChunkedCipherOutputStream output = new ChunkedCipherOutputStream(encrypted, key())) {
      output.write(plaintext);
    }
    return encrypted.toByteArray();
  }

  private byte[] decrypt(byte[] encrypted) throws Exception {
    return new ChunkedCipher(
        Base64.getEncoder().encodeToString(RAW_KEY),
        DECRYPT).doFinal(encrypted);
  }

  private SecretKeySpec key() {
    return new SecretKeySpec(RAW_KEY, "AES");
  }

  private byte[] plaintext(int size) {
    byte[] plaintext = new byte[size];
    for (int index = 0; index < plaintext.length; index++) {
      plaintext[index] = (byte) index;
    }
    return plaintext;
  }

  private byte[] hex(String value) {
    byte[] data = new byte[value.length() / 2];
    for (int index = 0; index < data.length; index++) {
      data[index] = (byte) ((Character.digit(value.charAt(2 * index), 16) << 4)
          + Character.digit(value.charAt(2 * index + 1), 16));
    }
    return data;
  }
}
