package org.moxie.confer.proxy.controllers;

import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.StreamingOutput;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.moxie.confer.proxy.config.Config;
import org.moxie.confer.proxy.crypto.ChunkedCipher;
import org.moxie.confer.proxy.crypto.ImageToken;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.security.GeneralSecurityException;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ImageControllerTest {

  @Mock
  private S3Client s3;

  @Mock
  private Config config;

  private ImageToken imageToken;
  private ImageController controller;

  @BeforeEach
  void setUp() {
    imageToken = new ImageToken();
    controller = new ImageController();
    controller.s3 = s3;
    controller.config = config;
    controller.imageToken = imageToken;
  }

  // --- Token validation ---

  @Test
  void rejectsNullToken() {
    WebApplicationException ex = assertThrows(WebApplicationException.class,
      () -> controller.getImage("key", "ek", null, null));
    assertEquals(401, ex.getResponse().getStatus());
  }

  @Test
  void rejectsWrongToken() {
    WebApplicationException ex = assertThrows(WebApplicationException.class,
      () -> controller.getImage("key", "ek", "wrong-token", null));
    assertEquals(401, ex.getResponse().getStatus());
  }

  @Test
  void acceptsValidToken() {
    // Valid token should not throw — response is built lazily, S3 isn't hit until streaming
    Response response = controller.getImage("key", "ek", imageToken.get(), null);
    assertEquals(200, response.getStatus());
  }

  // --- Parameter validation ---

  @Test
  void rejectsMissingKey() {
    WebApplicationException ex = assertThrows(WebApplicationException.class,
      () -> controller.getImage(null, "ek", imageToken.get(), null));
    assertEquals(400, ex.getResponse().getStatus());
  }

  @Test
  void rejectsBlankKey() {
    WebApplicationException ex = assertThrows(WebApplicationException.class,
      () -> controller.getImage("  ", "ek", imageToken.get(), null));
    assertEquals(400, ex.getResponse().getStatus());
  }

  @Test
  void rejectsMalformedObjectStorageKey() {
    WebApplicationException ex = assertThrows(WebApplicationException.class,
      () -> controller.getImage("../another/object", "ek", imageToken.get(), null));
    assertEquals(400, ex.getResponse().getStatus());
  }

  @Test
  void rejectsMissingEncryptionKey() {
    WebApplicationException ex = assertThrows(WebApplicationException.class,
      () -> controller.getImage("key", null, imageToken.get(), null));
    assertEquals(400, ex.getResponse().getStatus());
  }

  // --- Content type ---

  @Test
  void returnsSpecifiedMediaType() {
    Response response = controller.getImage("key", "ek", imageToken.get(), "image/png");
    assertEquals("image/png", response.getMediaType().toString());
  }

  @Test
  void defaultsToOctetStream() {
    Response response = controller.getImage("key", "ek", imageToken.get(), null);
    assertEquals("application/octet-stream", response.getMediaType().toString());
  }

  // --- Streaming decrypt ---

  @Test
  void decryptsAndStreamsS3Object() throws Exception {
    byte[] plaintext = "Hello, this is a test image!".getBytes();
    byte[] encrypted = encrypt(plaintext);
    String base64Key = Base64.getEncoder().encodeToString(testKey().getEncoded());

    when(config.getS3Bucket()).thenReturn("test-bucket");
    when(s3.getObject(any(GetObjectRequest.class)))
      .thenReturn(new ResponseInputStream<>(GetObjectResponse.builder().contentLength((long) encrypted.length).build(),
        AbortableInputStream.create(new ByteArrayInputStream(encrypted))));

    Response response = controller.getImage("attachments/test.enc", base64Key, imageToken.get(), "image/jpeg");
    assertEquals(200, response.getStatus());

    StreamingOutput stream = (StreamingOutput) response.getEntity();
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    stream.write(out);

    assertArrayEquals(plaintext, out.toByteArray());
  }

  @Test
  void rejectsOversizedEncryptedImageBeforeDecryption() {
    when(config.getS3Bucket()).thenReturn("test-bucket");
    when(s3.getObject(any(GetObjectRequest.class)))
      .thenReturn(new ResponseInputStream<>(
          GetObjectResponse.builder().contentLength(65L * 1024 * 1024).build(),
          AbortableInputStream.create(new ByteArrayInputStream(new byte[0]))));

    Response response = controller.getImage(
        "opaque-namespace/image",
        Base64.getEncoder().encodeToString(new byte[32]),
        imageToken.get(),
        "image/jpeg");
    StreamingOutput stream = (StreamingOutput) response.getEntity();

    WebApplicationException error = assertThrows(
        WebApplicationException.class,
        () -> stream.write(new ByteArrayOutputStream()));

    assertEquals(500, error.getResponse().getStatus());
  }

  // --- Corruption mid-stream ---

  @Test
  void throwsWhenCorruptedMidStream() throws Exception {
    // Use the default chunk size (64 KB) so each encrypted chunk exceeds
    // the 64 KB read buffer in ImageController. The first chunk decrypts
    // successfully and its plaintext is written to the output. The second
    // chunk is corrupted, so the exception fires after data is already
    // on the wire — exactly the scenario we're worried about.
    int chunkSize = 64 * 1024;
    byte[] plaintext = new byte[chunkSize * 2];
    java.util.Arrays.fill(plaintext, (byte) 'A');

    byte[] encrypted = encrypt(plaintext, chunkSize);

    // Corrupt a byte in the second chunk's ciphertext region.
    // Header is 21 bytes; first encrypted chunk is IV(12) + chunkSize + tag(16).
    int firstEncChunkSize = 12 + chunkSize + 16;
    int secondChunkCiphertextOffset = 21 + firstEncChunkSize + 12; // skip header + chunk1 + chunk2 IV
    encrypted[secondChunkCiphertextOffset] ^= 0xFF;

    String base64Key = Base64.getEncoder().encodeToString(testKey().getEncoded());

    when(config.getS3Bucket()).thenReturn("test-bucket");
    when(s3.getObject(any(GetObjectRequest.class)))
      .thenReturn(new ResponseInputStream<>(GetObjectResponse.builder().contentLength((long) encrypted.length).build(),
        AbortableInputStream.create(new ByteArrayInputStream(encrypted))));

    Response response = controller.getImage("attachments/test.enc", base64Key, imageToken.get(), null);
    StreamingOutput stream = (StreamingOutput) response.getEntity();
    ByteArrayOutputStream out = new ByteArrayOutputStream();

    // The StreamingOutput should throw — the container would then reset the connection.
    WebApplicationException ex = assertThrows(WebApplicationException.class, () -> stream.write(out));
    assertEquals(500, ex.getResponse().getStatus());

    // Some data was already written before corruption was detected
    assertTrue(out.size() > 0, "Some bytes should have been written before the error");
  }

  @Test
  void throwsWhenTruncated() throws Exception {
    int chunkSize = 64;
    byte[] plaintext = new byte[chunkSize * 2];
    java.util.Arrays.fill(plaintext, (byte) 'B');

    byte[] encrypted = encrypt(plaintext, chunkSize);

    // Truncate: cut off the last 20 bytes (removes part of the final chunk)
    byte[] truncated = java.util.Arrays.copyOf(encrypted, encrypted.length - 20);

    String base64Key = Base64.getEncoder().encodeToString(testKey().getEncoded());

    when(config.getS3Bucket()).thenReturn("test-bucket");
    when(s3.getObject(any(GetObjectRequest.class)))
      .thenReturn(new ResponseInputStream<>(GetObjectResponse.builder().contentLength((long) truncated.length).build(),
        AbortableInputStream.create(new ByteArrayInputStream(truncated))));

    Response response = controller.getImage("attachments/test.enc", base64Key, imageToken.get(), null);
    StreamingOutput stream = (StreamingOutput) response.getEntity();
    ByteArrayOutputStream out = new ByteArrayOutputStream();

    assertThrows(WebApplicationException.class, () -> stream.write(out));
  }

  @Test
  void throwsWhenWrongKey() throws Exception {
    byte[] plaintext = "Secret content".getBytes();
    byte[] encrypted = encrypt(plaintext);

    // Generate a different key
    KeyGenerator gen = KeyGenerator.getInstance("AES");
    gen.init(256);
    SecretKey wrongKey = gen.generateKey();
    String wrongBase64Key = Base64.getEncoder().encodeToString(wrongKey.getEncoded());

    when(config.getS3Bucket()).thenReturn("test-bucket");
    when(s3.getObject(any(GetObjectRequest.class)))
      .thenReturn(new ResponseInputStream<>(GetObjectResponse.builder().contentLength((long) encrypted.length).build(),
        AbortableInputStream.create(new ByteArrayInputStream(encrypted))));

    Response response = controller.getImage("attachments/test.enc", wrongBase64Key, imageToken.get(), null);
    StreamingOutput stream = (StreamingOutput) response.getEntity();
    ByteArrayOutputStream out = new ByteArrayOutputStream();

    assertThrows(WebApplicationException.class, () -> stream.write(out));
  }

  // --- Helpers ---

  private static SecretKey testKey;

  private static SecretKey testKey() throws NoSuchAlgorithmException {
    if (testKey == null) {
      KeyGenerator gen = KeyGenerator.getInstance("AES");
      gen.init(256);
      testKey = gen.generateKey();
    }
    return testKey;
  }

  private static byte[] encrypt(byte[] plaintext) throws Exception {
    return encrypt(plaintext, 64 * 1024);
  }

  /**
   * Encrypts data using the ChunkedCipher format with a configurable chunk size.
   */
  private static byte[] encrypt(byte[] plaintext, int chunkSize) throws Exception {
    SecretKey key = testKey();

    ByteArrayOutputStream output = new ByteArrayOutputStream();
    java.security.SecureRandom random = new java.security.SecureRandom();

    // Header: version(1) + chunkSize(4) + fileId(16)
    byte[] fileId = new byte[16];
    random.nextBytes(fileId);

    output.write(0x01); // version
    java.nio.ByteBuffer chunkSizeBuf = java.nio.ByteBuffer.allocate(4);
    chunkSizeBuf.putInt(chunkSize);
    output.write(chunkSizeBuf.array());
    output.write(fileId);

    byte[] header = output.toByteArray();
    byte[] runningHash = sha256(header);

    // Split plaintext into chunks
    int offset = 0;
    int seq = 0;
    while (offset < plaintext.length || seq == 0) {
      int end = Math.min(offset + chunkSize, plaintext.length);
      byte[] chunk = java.util.Arrays.copyOfRange(plaintext, offset, end);
      boolean isFinal = end >= plaintext.length;

      // Build AAD: fileId(16) + seq(4) + isFinal(1) + runningHash(32)
      byte[] aad = new byte[16 + 4 + 1 + 32];
      System.arraycopy(fileId, 0, aad, 0, 16);
      java.nio.ByteBuffer.wrap(aad, 16, 4).putInt(seq);
      aad[20] = isFinal ? (byte) 1 : (byte) 0;
      System.arraycopy(runningHash, 0, aad, 21, 32);

      // Encrypt chunk
      byte[] iv = new byte[12];
      random.nextBytes(iv);

      javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, key, new javax.crypto.spec.GCMParameterSpec(128, iv));
      cipher.updateAAD(aad);
      byte[] ciphertext = cipher.doFinal(chunk);

      output.write(iv);
      output.write(ciphertext);

      // Update running hash: SHA256(prevHash || ciphertext)
      java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
      md.update(runningHash);
      md.update(ciphertext);
      runningHash = md.digest();

      seq++;
      offset = end;
      if (isFinal) break;
    }

    return output.toByteArray();
  }

  private static byte[] sha256(byte[] data) throws Exception {
    return java.security.MessageDigest.getInstance("SHA-256").digest(data);
  }
}
