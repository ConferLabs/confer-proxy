package org.moxie.confer.proxy.crypto;

import javax.crypto.Cipher;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;

/**
 * Incremental authenticated encryption for the Confer chunked file format.
 *
 * <pre>
 * [Header]
 *   version    : 1 byte
 *   chunk size : 4 bytes, big-endian
 *   file ID    : 16 bytes
 *
 * [Chunk]
 *   IV         : 12 bytes
 *   ciphertext : up to chunk size bytes
 *   GCM tag    : 16 bytes
 *
 * AAD per chunk:
 *   file ID || sequence number || final chunk || running hash
 *
 * Running hash:
 *   first chunk : SHA-256(header)
 *   next chunk  : SHA-256(previous hash || previous ciphertext and tag)
 * </pre>
 */
public class ChunkedCipher {

  public enum Mode {
    ENCRYPT,
    DECRYPT
  }

  private static final byte VERSION            = 0x01;
  private static final int  DEFAULT_CHUNK_SIZE = 64 * 1024;
  private static final int  MAX_CHUNK_SIZE     = 64 * 1024 * 1024;
  private static final int  FILE_ID_SIZE       = 16;
  private static final int  IV_SIZE            = 12;
  private static final int  TAG_SIZE           = 16;
  private static final int  TAG_BITS           = TAG_SIZE * 8;
  private static final int  HASH_SIZE          = 32;
  private static final int  HEADER_SIZE        = 1 + Integer.BYTES + FILE_ID_SIZE;
  private static final int  CHUNK_OVERHEAD     = IV_SIZE + TAG_SIZE;

  private final SecretKey             key;
  private final Mode                  mode;
  private final SecureRandom          random;
  private final Cipher                cipher;
  private final MessageDigest         messageDigest;
  private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

  private byte[] header;
  private byte[] fileId;
  private byte[] runningHash;
  private int    chunkSize;
  private int    sequenceNumber;
  private boolean finalized;

  public ChunkedCipher(SecretKey key,
                       Mode mode)
  {
    this(key, mode, new SecureRandom());
  }

  public ChunkedCipher(String base64Key,
                       Mode mode)
  {
    this(new SecretKeySpec(Base64.getDecoder().decode(base64Key), "AES"), mode);
  }

  ChunkedCipher(SecretKey key,
                Mode mode,
                SecureRandom random)
  {
    this.key    = Objects.requireNonNull(key, "key"      );
    this.mode   = Objects.requireNonNull(mode, "mode"    );
    this.random = Objects.requireNonNull(random, "random");

    try {
      cipher        = Cipher.getInstance("AES/GCM/NoPadding");
      messageDigest = MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException | NoSuchPaddingException error) {
      throw new AssertionError(error);
    }

    if (mode == Mode.ENCRYPT) {
      initializeEncryption();
    }
  }

  /**
   * Processes input incrementally. Encryption retains a possible final chunk
   * until {@link #doFinal()} establishes its finality.
   */
  public byte[] update(byte[] input) throws GeneralSecurityException {
    return update(input, 0, input.length);
  }

  public byte[] update(byte[] input,
                       int offset,
                       int length)
    throws GeneralSecurityException
  {
    requireActive();
    Objects.checkFromIndexSize(offset, length, input.length);
    buffer.write(input, offset, length);
    return processAvailableChunks(false);
  }

  public byte[] doFinal() throws GeneralSecurityException {
    return doFinal(null);
  }

  /**
   * Processes any buffered input and optional additional input as final.
   */
  public byte[] doFinal(byte[] input) throws GeneralSecurityException {
    requireActive();
    finalized = true;

    if (input != null && input.length > 0) {
      buffer.write(input, 0, input.length);
    }
    return processAvailableChunks(true);
  }

  byte[] getHeader() {
    requireMode(Mode.ENCRYPT);
    return header.clone();
  }

  int getHeaderSize() {
    return HEADER_SIZE;
  }

  int getPlaintextChunkSize() {
    requireInitialized();
    return chunkSize;
  }

  int getEncryptedChunkSize() {
    return getPlaintextChunkSize() + CHUNK_OVERHEAD;
  }

  long getPlaintextLength(long encryptedLength) throws GeneralSecurityException {
    requireMode(Mode.DECRYPT);
    requireInitialized();

    if (encryptedLength < HEADER_SIZE + CHUNK_OVERHEAD) {
      throw new GeneralSecurityException("Invalid encrypted data: too short");
    }

    long encryptedBodyLength = encryptedLength - HEADER_SIZE;
    long encryptedChunkSize = getEncryptedChunkSize();
    long chunkCount = 1 + ((encryptedBodyLength - 1) / encryptedChunkSize);
    long finalChunkSize = encryptedBodyLength - ((chunkCount - 1) * encryptedChunkSize);
    if (finalChunkSize < CHUNK_OVERHEAD) {
      throw new GeneralSecurityException("Invalid encrypted data: final chunk too short");
    }

    return encryptedBodyLength - (chunkCount * CHUNK_OVERHEAD);
  }

  private byte[] processAvailableChunks(boolean finalInput) throws GeneralSecurityException {
    if (!finalInput && mode == Mode.ENCRYPT && buffer.size() <= chunkSize) {
      return new byte[0];
    }

    byte[] pending  = buffer.toByteArray();
    int    position = initialize(pending, finalInput);

    if (position < 0) {
      return new byte[0];
    }

    ByteArrayOutputStream output = new ByteArrayOutputStream();
    int inputChunkSize = getInputChunkSize();
    while (hasNonFinalChunk(pending.length - position, inputChunkSize, finalInput)) {
      output.writeBytes(processChunk(pending, position, inputChunkSize, false));
      position += inputChunkSize;
    }

    if (finalInput) {
      int remaining = pending.length - position;
      validateFinalChunk(remaining);
      output.writeBytes(processChunk(pending, position, remaining, true));
      position += remaining;
    }

    retainRemaining(pending, position);
    return output.toByteArray();
  }

  private int initialize(byte[] pending,
                         boolean finalInput)
    throws GeneralSecurityException
  {
    if (mode == Mode.ENCRYPT || fileId != null) {
      return 0;
    }
    if (pending.length < HEADER_SIZE) {
      if (finalInput) {
        throw new GeneralSecurityException("Invalid encrypted data: too short for header");
      }
      return -1;
    }

    initializeDecryption(pending);
    return HEADER_SIZE;
  }

  private int getInputChunkSize() {
    return mode == Mode.ENCRYPT ? getPlaintextChunkSize() : getEncryptedChunkSize();
  }

  private boolean hasNonFinalChunk(int remaining,
                                   int inputChunkSize,
                                   boolean finalInput)
  {
    if (finalInput || mode == Mode.ENCRYPT) {
      return remaining > inputChunkSize;
    }
    return remaining >= inputChunkSize;
  }

  private void validateFinalChunk(int length) throws GeneralSecurityException {
    if (mode == Mode.DECRYPT && length < CHUNK_OVERHEAD) {
      throw new GeneralSecurityException("Invalid encrypted data: final chunk too short");
    }
  }

  private byte[] processChunk(byte[] input,
                              int offset,
                              int length,
                              boolean finalChunk)
    throws GeneralSecurityException
  {
    return mode == Mode.ENCRYPT
        ? encryptChunk(input, offset, length, finalChunk)
        : decryptChunk(input, offset, length, finalChunk);
  }

  private void initializeEncryption() {
    chunkSize = DEFAULT_CHUNK_SIZE;
    fileId = getRandomBytes(FILE_ID_SIZE);
    header = ByteBuffer.allocate(HEADER_SIZE)
                       .put(VERSION)
                       .putInt(chunkSize)
                       .put(fileId)
                       .array();
    runningHash = getHash(header, 0, header.length);
  }

  private void initializeDecryption(byte[] input) throws GeneralSecurityException {
    ByteBuffer encodedHeader = ByteBuffer.wrap(input, 0, HEADER_SIZE);
    byte version = encodedHeader.get();
    if (version != VERSION) {
      throw new GeneralSecurityException("Unsupported encryption version: " + version);
    }

    chunkSize = encodedHeader.getInt();
    if (chunkSize <= 0 || chunkSize > MAX_CHUNK_SIZE) {
      throw new GeneralSecurityException("Invalid chunk size: " + chunkSize);
    }

    fileId = new byte[FILE_ID_SIZE];
    encodedHeader.get(fileId);
    header = Arrays.copyOfRange(input, 0, HEADER_SIZE);
    runningHash = getHash(header, 0, header.length);
  }

  private byte[] encryptChunk(byte[] plaintext,
                              int offset,
                              int length,
                              boolean finalChunk)
    throws GeneralSecurityException
  {
    byte[] iv = getRandomBytes(IV_SIZE);
    Cipher encryptingCipher = getCipher(Cipher.ENCRYPT_MODE, iv);
    encryptingCipher.updateAAD(getAssociatedData(finalChunk));
    byte[] ciphertext = encryptingCipher.doFinal(plaintext, offset, length);

    advanceState(ciphertext, 0, ciphertext.length);
    return ByteBuffer.allocate(iv.length + ciphertext.length)
                     .put(iv)
                     .put(ciphertext)
                     .array();
  }

  private byte[] decryptChunk(byte[] encrypted,
                              int offset,
                              int length,
                              boolean finalChunk)
    throws GeneralSecurityException
  {
    byte[] iv = Arrays.copyOfRange(encrypted, offset, offset + IV_SIZE);
    Cipher decryptingCipher = getCipher(Cipher.DECRYPT_MODE, iv);
    decryptingCipher.updateAAD(getAssociatedData(finalChunk));

    int ciphertextOffset = offset + IV_SIZE;
    int ciphertextLength = length - IV_SIZE;
    byte[] plaintext = decryptingCipher.doFinal(
        encrypted,
        ciphertextOffset,
        ciphertextLength);

    advanceState(encrypted, ciphertextOffset, ciphertextLength);
    return plaintext;
  }

  private Cipher getCipher(int operation,
                           byte[] iv)
    throws GeneralSecurityException
  {
    cipher.init(operation, key, new GCMParameterSpec(TAG_BITS, iv));
    return cipher;
  }

  private byte[] getAssociatedData(boolean finalChunk) {
    return ByteBuffer.allocate(FILE_ID_SIZE + Integer.BYTES + 1 + HASH_SIZE)
                     .put(fileId)
                     .putInt(sequenceNumber)
                     .put(finalChunk ? (byte) 1 : (byte) 0)
                     .put(runningHash)
                     .array();
  }

  private void advanceState(byte[] ciphertext,
                            int offset,
                            int length)
  {
    runningHash = getHash(runningHash, ciphertext, offset, length);
    sequenceNumber++;
  }

  private byte[] getRandomBytes(int length) {
    byte[] value = new byte[length];
    random.nextBytes(value);
    return value;
  }

  private byte[] getHash(byte[] input,
                         int offset,
                         int length)
  {
    messageDigest.update(input, offset, length);
    return messageDigest.digest();
  }

  private byte[] getHash(byte[] first,
                         byte[] second,
                         int offset,
                         int length)
  {
    messageDigest.update(first);
    messageDigest.update(second, offset, length);
    return messageDigest.digest();
  }

  private void retainRemaining(byte[] pending,
                               int position)
  {
    buffer.reset();
    if (position < pending.length) {
      buffer.write(pending, position, pending.length - position);
    }
  }

  private void requireInitialized() {
    if (fileId == null) {
      throw new IllegalStateException("Chunked cipher is not initialized");
    }
  }

  private void requireMode(Mode expected) {
    if (mode != expected) {
      throw new IllegalStateException("Chunked cipher mode is invalid for this operation");
    }
  }

  private void requireActive() throws GeneralSecurityException {
    if (finalized) {
      throw new GeneralSecurityException("Chunked cipher is finalized");
    }
  }
}
