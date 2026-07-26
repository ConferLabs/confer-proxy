package org.moxie.confer.proxy.crypto;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Base64;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ChunkedCipherInputStreamTest {

  private static final int SMALL_CHUNK_SIZE = 64;
  private static final int MAX_CHUNK_SIZE   = 64 * 1024 * 1024;

  @Test
  void decryptsEveryChunkBoundary() throws Exception {
    int[] sizes = new int[] {
        0,
        1,
        SMALL_CHUNK_SIZE - 1,
        SMALL_CHUNK_SIZE,
        SMALL_CHUNK_SIZE + 1,
        (2 * SMALL_CHUNK_SIZE) - 1,
        2 * SMALL_CHUNK_SIZE,
        (2 * SMALL_CHUNK_SIZE) + 1,
        (3 * SMALL_CHUNK_SIZE) - 1,
        3 * SMALL_CHUNK_SIZE,
        (3 * SMALL_CHUNK_SIZE) + 1
    };

    for (int size : sizes) {
      ChunkedCipherTestVector vector = vector(size, SMALL_CHUNK_SIZE);
      try (ChunkedCipherInputStream input = open(vector)) {
        assertEquals(size, input.plaintextLength(vector.ciphertext().length), "size=" + size);
        assertArrayEquals(vector.plaintext(), input.readAllBytes(), "size=" + size);
      }
    }
  }

  @Test
  void decryptsWhenTheUnderlyingStreamReturnsOneByteAtATime() throws Exception {
    ChunkedCipherTestVector vector = vector((2 * SMALL_CHUNK_SIZE) + 7, SMALL_CHUNK_SIZE);
    FragmentedInputStream encrypted = new FragmentedInputStream(vector.ciphertext(), 1);

    try (ChunkedCipherInputStream input = new ChunkedCipherInputStream(
        encrypted,
        vector.encodedKey()))
    {
      assertArrayEquals(vector.plaintext(), input.readAllBytes());
    }
  }

  @Test
  void decryptsRandomizedLengthsAndReadBoundaries() throws Exception {
    Random random = new Random(0x434950484552L);
    int[] chunkSizes = new int[] {1, 2, 3, 7, 31, 64, 255};

    for (int iteration = 0; iteration < 100; iteration++) {
      int size = random.nextInt(2049);
      int chunkSize = chunkSizes[random.nextInt(chunkSizes.length)];
      byte[] plaintext = new byte[size];
      random.nextBytes(plaintext);
      ChunkedCipherTestVector vector = new ChunkedCipherTestVector(plaintext, chunkSize);
      FragmentedInputStream encrypted = new FragmentedInputStream(
          vector.ciphertext(),
          1 + random.nextInt(23));

      try (ChunkedCipherInputStream input = new ChunkedCipherInputStream(
          encrypted,
          vector.encodedKey()))
      {
        assertArrayEquals(
            plaintext,
            readWithRandomBoundaries(input, random),
            "iteration=" + iteration + ", size=" + size + ", chunkSize=" + chunkSize);
      }
    }
  }

  @Test
  void supportsSingleByteReadsAndUnsignedValues() throws Exception {
    byte[] plaintext = new byte[] {0, 1, 127, (byte) 128, (byte) 255};
    ChunkedCipherTestVector vector = new ChunkedCipherTestVector(plaintext, SMALL_CHUNK_SIZE);

    try (ChunkedCipherInputStream input = open(vector)) {
      assertEquals(0, input.read());
      assertEquals(1, input.read());
      assertEquals(127, input.read());
      assertEquals(128, input.read());
      assertEquals(255, input.read());
      assertEquals(-1, input.read());
      assertEquals(-1, input.read());
    }
  }

  @Test
  void obeysInputStreamRangeAndZeroLengthContracts() throws Exception {
    ChunkedCipherTestVector vector = vector(3, SMALL_CHUNK_SIZE);

    try (ChunkedCipherInputStream input = open(vector)) {
      byte[] destination = new byte[8];
      assertEquals(0, input.read(destination, 4, 0));
      assertThrows(IndexOutOfBoundsException.class, () -> input.read(destination, -1, 1));
      assertThrows(IndexOutOfBoundsException.class, () -> input.read(destination, 8, 1));
      assertThrows(IndexOutOfBoundsException.class, () -> input.read(destination, 0, 9));
      assertThrows(NullPointerException.class, () -> input.read(null, 0, 1));

      assertArrayEquals(vector.plaintext(), input.readAllBytes());
      assertEquals(0, input.read(destination, 0, 0));
      assertEquals(-1, input.read(destination, 0, destination.length));
      assertEquals(-1, input.read(destination, 0, destination.length));
    }
  }

  @Test
  void closesTheUnderlyingStreamExactlyOnceAndRejectsFurtherReads() throws Exception {
    ChunkedCipherTestVector vector = vector(8, SMALL_CHUNK_SIZE);
    FragmentedInputStream encrypted = new FragmentedInputStream(vector.ciphertext(), 3);
    ChunkedCipherInputStream input = new ChunkedCipherInputStream(encrypted, vector.encodedKey());

    input.close();
    input.close();

    assertEquals(1, encrypted.closeCalls());
    assertThrows(IOException.class, input::read);
    assertThrows(IOException.class, () -> input.read(new byte[1], 0, 0));
  }

  @Test
  void propagatesUnderlyingReadFailuresFromTheHeaderAndBody() throws Exception {
    ChunkedCipherTestVector vector = vector(SMALL_CHUNK_SIZE + 7, SMALL_CHUNK_SIZE);
    FaultInjectingInputStream headerFailure = new FaultInjectingInputStream(
        vector.ciphertext(),
        10,
        false);
    assertThrows(
        IOException.class,
        () -> new ChunkedCipherInputStream(headerFailure, vector.encodedKey()));

    FaultInjectingInputStream bodyFailure = new FaultInjectingInputStream(
        vector.ciphertext(),
        ChunkedCipherTestVector.HEADER_SIZE + 10,
        false);
    try (ChunkedCipherInputStream input = new ChunkedCipherInputStream(
        bodyFailure,
        vector.encodedKey()))
    {
      assertThrows(IOException.class, input::readAllBytes);
    }
  }

  @Test
  void remainsClosedWhenTheUnderlyingCloseFails() throws Exception {
    ChunkedCipherTestVector vector = vector(8, SMALL_CHUNK_SIZE);
    FaultInjectingInputStream encrypted = new FaultInjectingInputStream(
        vector.ciphertext(),
        -1,
        true);
    ChunkedCipherInputStream input = new ChunkedCipherInputStream(
        encrypted,
        vector.encodedKey());

    assertThrows(IOException.class, input::close);
    input.close();

    assertEquals(1, encrypted.closeCalls());
    assertThrows(IOException.class, input::read);
  }

  @Test
  void rejectsEveryTruncationPoint() throws Exception {
    ChunkedCipherTestVector vector = vector((2 * SMALL_CHUNK_SIZE) + 7, SMALL_CHUNK_SIZE);
    byte[] ciphertext = vector.ciphertext();

    for (int length = 0; length < ciphertext.length; length++) {
      byte[] truncated = Arrays.copyOf(ciphertext, length);
      assertThrows(
          IOException.class,
          () -> decrypt(truncated, vector.encodedKey()),
          "length=" + length);
    }
  }

  @Test
  void rejectsMutationOfEveryEncryptedBodyByte() throws Exception {
    ChunkedCipherTestVector vector = vector(SMALL_CHUNK_SIZE + 7, SMALL_CHUNK_SIZE);
    byte[] ciphertext = vector.ciphertext();

    for (int index = ChunkedCipherTestVector.HEADER_SIZE; index < ciphertext.length; index++) {
      byte[] mutated = ciphertext.clone();
      mutated[index] ^= 1;
      assertThrows(
          IOException.class,
          () -> decrypt(mutated, vector.encodedKey()),
          "index=" + index);
    }
  }

  @Test
  void rejectsMutationOfEveryFileIdentifierByte() throws Exception {
    ChunkedCipherTestVector vector = vector(SMALL_CHUNK_SIZE + 7, SMALL_CHUNK_SIZE);
    byte[] ciphertext = vector.ciphertext();

    for (int index = 1 + Integer.BYTES; index < ChunkedCipherTestVector.HEADER_SIZE; index++) {
      byte[] mutated = ciphertext.clone();
      mutated[index] ^= 1;
      assertThrows(
          IOException.class,
          () -> decrypt(mutated, vector.encodedKey()),
          "index=" + index);
    }
  }

  @Test
  void rejectsAppendedGarbageAcrossChunkBoundaries() throws Exception {
    ChunkedCipherTestVector vector = vector(SMALL_CHUNK_SIZE, SMALL_CHUNK_SIZE);
    byte[] ciphertext = vector.ciphertext();
    int maximumGarbage = SMALL_CHUNK_SIZE + ChunkedCipherTestVector.CHUNK_OVERHEAD + 1;

    for (int garbageLength = 1; garbageLength <= maximumGarbage; garbageLength++) {
      byte[] appended = Arrays.copyOf(ciphertext, ciphertext.length + garbageLength);
      assertThrows(
          IOException.class,
          () -> decrypt(appended, vector.encodedKey()),
          "garbageLength=" + garbageLength);
    }
  }

  @Test
  void rejectsInvalidHeaderChunkSizes() throws Exception {
    int[] invalidChunkSizes = new int[] {0, -1, Integer.MIN_VALUE, MAX_CHUNK_SIZE + 1, Integer.MAX_VALUE};
    String key = vector(0, 1).encodedKey();

    for (int chunkSize : invalidChunkSizes) {
      assertThrows(
          IOException.class,
          () -> new ChunkedCipherInputStream(
              new ByteArrayInputStream(ChunkedCipherTestVector.header(chunkSize)),
              key),
          "chunkSize=" + chunkSize);
    }
  }

  @Test
  void acceptsMinimumAndMaximumHeaderChunkSizesWithoutEagerAllocation() throws Exception {
    ChunkedCipherTestVector minimum = vector(3, 1);
    try (ChunkedCipherInputStream input = open(minimum)) {
      assertArrayEquals(minimum.plaintext(), input.readAllBytes());
    }

    String key = minimum.encodedKey();
    try (ChunkedCipherInputStream input = new ChunkedCipherInputStream(
        new ByteArrayInputStream(ChunkedCipherTestVector.header(MAX_CHUNK_SIZE)),
        key))
    {
      assertEquals(
          0,
          input.plaintextLength(
              ChunkedCipherTestVector.HEADER_SIZE + ChunkedCipherTestVector.CHUNK_OVERHEAD));
    }
  }

  @Test
  void rejectsInvalidVersionAndEveryTruncatedHeaderLength() throws Exception {
    ChunkedCipherTestVector vector = vector(0, SMALL_CHUNK_SIZE);
    byte[] header = ChunkedCipherTestVector.header(SMALL_CHUNK_SIZE);

    for (int length = 0; length < ChunkedCipherTestVector.HEADER_SIZE; length++) {
      int truncatedLength = length;
      assertThrows(
          IOException.class,
          () -> new ChunkedCipherInputStream(
              new ByteArrayInputStream(Arrays.copyOf(header, truncatedLength)),
              vector.encodedKey()),
          "length=" + length);
    }

    header[0] = 2;
    assertThrows(
        IOException.class,
        () -> new ChunkedCipherInputStream(new ByteArrayInputStream(header), vector.encodedKey()));
  }

  @Test
  void rejectsInvalidBase64AndAesKeyLengths() throws Exception {
    ChunkedCipherTestVector vector = vector(3, SMALL_CHUNK_SIZE);

    assertThrows(
        IOException.class,
        () -> new ChunkedCipherInputStream(
            new ByteArrayInputStream(vector.ciphertext()),
            "not base64"));

    int[] invalidKeyLengths = new int[] {0, 1, 15, 17, 23, 25, 31, 33};
    for (int keyLength : invalidKeyLengths) {
      String encodedKey = Base64.getEncoder().encodeToString(new byte[keyLength]);
      assertThrows(
          IOException.class,
          () -> decrypt(vector.ciphertext(), encodedKey),
          "keyLength=" + keyLength);
    }
  }

  @Test
  void validatesPlaintextLengthAtSmallAndVeryLargeChunkCounts() throws Exception {
    ChunkedCipherTestVector vector = vector(0, SMALL_CHUNK_SIZE);
    try (ChunkedCipherInputStream input = open(vector)) {
      assertThrows(
          IOException.class,
          () -> input.plaintextLength(
              ChunkedCipherTestVector.HEADER_SIZE + ChunkedCipherTestVector.CHUNK_OVERHEAD - 1));

      long encryptedChunkSize = SMALL_CHUNK_SIZE + ChunkedCipherTestVector.CHUNK_OVERHEAD;
      assertThrows(
          IOException.class,
          () -> input.plaintextLength(
              ChunkedCipherTestVector.HEADER_SIZE
                  + encryptedChunkSize
                  + ChunkedCipherTestVector.CHUNK_OVERHEAD
                  - 1));

      long chunkCount = Integer.MAX_VALUE;
      long encryptedLength = ChunkedCipherTestVector.HEADER_SIZE + (chunkCount * encryptedChunkSize);
      assertEquals(chunkCount * SMALL_CHUNK_SIZE, input.plaintextLength(encryptedLength));

      long maximumChunkCount = (Long.MAX_VALUE - ChunkedCipherTestVector.HEADER_SIZE)
          / encryptedChunkSize;
      long maximumAlignedLength = ChunkedCipherTestVector.HEADER_SIZE
          + (maximumChunkCount * encryptedChunkSize);
      assertEquals(
          maximumChunkCount * SMALL_CHUNK_SIZE,
          input.plaintextLength(maximumAlignedLength));
    }
  }

  @Test
  void rejectsNullEncryptedStream() throws Exception {
    String key = vector(0, SMALL_CHUNK_SIZE).encodedKey();

    assertThrows(NullPointerException.class, () -> new ChunkedCipherInputStream(null, key));
  }

  private ChunkedCipherTestVector vector(int plaintextSize,
                                         int chunkSize)
    throws Exception
  {
    byte[] plaintext = new byte[plaintextSize];
    for (int index = 0; index < plaintext.length; index++) {
      plaintext[index] = (byte) index;
    }
    return new ChunkedCipherTestVector(plaintext, chunkSize);
  }

  private ChunkedCipherInputStream open(ChunkedCipherTestVector vector) throws IOException {
    return new ChunkedCipherInputStream(
        new ByteArrayInputStream(vector.ciphertext()),
        vector.encodedKey());
  }

  private byte[] decrypt(byte[] ciphertext,
                         String encodedKey)
    throws IOException
  {
    try (ChunkedCipherInputStream input = new ChunkedCipherInputStream(
        new ByteArrayInputStream(ciphertext),
        encodedKey))
    {
      return input.readAllBytes();
    }
  }

  private byte[] readWithRandomBoundaries(ChunkedCipherInputStream input,
                                          Random random)
    throws IOException
  {
    ByteArrayOutputStream plaintext = new ByteArrayOutputStream();
    byte[] destination = new byte[260];
    while (true) {
      int length = 1 + random.nextInt(256);
      int read = input.read(destination, 2, length);
      if (read < 0) {
        return plaintext.toByteArray();
      }
      plaintext.write(destination, 2, read);
    }
  }
}
