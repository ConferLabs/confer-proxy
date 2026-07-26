package org.moxie.confer.proxy.crypto;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;

class ChunkedCipherTestVector {

  static final int HEADER_SIZE    = 21;
  static final int IV_SIZE        = 12;
  static final int TAG_SIZE       = 16;
  static final int CHUNK_OVERHEAD = IV_SIZE + TAG_SIZE;

  private static final byte[] KEY = new byte[32];

  private final byte[] plaintext;
  private final byte[] ciphertext;
  private final int    chunkSize;

  ChunkedCipherTestVector(byte[] plaintext,
                          int chunkSize)
    throws GeneralSecurityException
  {
    if (chunkSize <= 0) {
      throw new IllegalArgumentException("chunkSize must be positive");
    }

    this.plaintext = plaintext.clone();
    this.chunkSize = chunkSize;
    this.ciphertext = encrypt();
  }

  byte[] plaintext() {
    return plaintext.clone();
  }

  byte[] ciphertext() {
    return ciphertext.clone();
  }

  int chunkSize() {
    return chunkSize;
  }

  String encodedKey() {
    return Base64.getEncoder().encodeToString(KEY);
  }

  static byte[] header(int chunkSize) {
    byte[] fileId = new byte[16];
    for (int index = 0; index < fileId.length; index++) {
      fileId[index] = (byte) index;
    }

    return ByteBuffer.allocate(HEADER_SIZE)
                     .put((byte) 1)
                     .putInt(chunkSize)
                     .put(fileId)
                     .array();
  }

  private byte[] encrypt() throws GeneralSecurityException {
    byte[] header = header(chunkSize);
    byte[] fileId = Arrays.copyOfRange(header, 5, HEADER_SIZE);
    byte[] runningHash = MessageDigest.getInstance("SHA-256").digest(header);
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    output.writeBytes(header);

    int chunkCount = Math.max(1, (plaintext.length + chunkSize - 1) / chunkSize);
    for (int sequenceNumber = 0; sequenceNumber < chunkCount; sequenceNumber++) {
      int offset = sequenceNumber * chunkSize;
      int length = Math.min(chunkSize, plaintext.length - offset);
      boolean finalChunk = sequenceNumber == chunkCount - 1;
      byte[] iv = ByteBuffer.allocate(IV_SIZE)
                            .putInt(IV_SIZE - Integer.BYTES, sequenceNumber)
                            .array();
      byte[] associatedData = ByteBuffer.allocate(16 + Integer.BYTES + 1 + 32)
                                        .put(fileId)
                                        .putInt(sequenceNumber)
                                        .put(finalChunk ? (byte) 1 : (byte) 0)
                                        .put(runningHash)
                                        .array();

      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(
          Cipher.ENCRYPT_MODE,
          new SecretKeySpec(KEY, "AES"),
          new GCMParameterSpec(TAG_SIZE * 8, iv));
      cipher.updateAAD(associatedData);
      byte[] encryptedChunk = cipher.doFinal(plaintext, offset, length);

      output.writeBytes(iv);
      output.writeBytes(encryptedChunk);

      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      digest.update(runningHash);
      digest.update(encryptedChunk);
      runningHash = digest.digest();
    }

    return output.toByteArray();
  }
}
