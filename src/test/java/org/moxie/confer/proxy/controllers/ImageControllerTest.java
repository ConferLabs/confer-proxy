package org.moxie.confer.proxy.controllers;

import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.StreamingOutput;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.stubbing.Answer;
import org.moxie.confer.proxy.config.Config;
import org.moxie.confer.proxy.crypto.ImageToken;
import org.moxie.confer.proxy.images.ImageNormalizer;
import org.moxie.confer.proxy.images.InvalidNormalizedImageException;
import org.moxie.confer.proxy.images.NormalizedImage;
import org.moxie.confer.proxy.images.NormalizedImageStream;
import org.moxie.confer.proxy.images.NormalizerFrames;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ImageControllerTest {

  private static final byte[] JPEG_SIGNATURE = new byte[] { (byte) 0xff, (byte) 0xd8, (byte) 0xff };
  private static final String POOL           = "0123456789abcdef0123456789abcdef";
  private static final byte[] PIXELS         = new byte[] { 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12 };

  @Mock
  private S3Client s3;

  @Mock
  private Config config;

  @Mock
  private ImageNormalizer normalizer;

  private ImageToken            imageToken;
  private ImageController       controller;
  private NormalizedImageStream stream;
  private ByteArrayOutputStream sent;

  @BeforeEach
  void setUp() throws Exception {
    imageToken = new ImageToken();
    controller = new ImageController();
    controller.s3 = s3;
    controller.config = config;
    controller.imageToken = imageToken;
    controller.normalizer = normalizer;

    stream = streamOf(NormalizerFrames.image(2, 2), PIXELS);
    sent   = new ByteArrayOutputStream();
    lenient().when(normalizer.normalize(eq(POOL), any())).thenAnswer(sendingTo(sent, stream));
  }

  // --- Token validation ---

  @Test
  void rejectsNullToken() {
    WebApplicationException ex = assertThrows(WebApplicationException.class,
      () -> controller.getImage("key", "ek", null, POOL));
    assertEquals(401, ex.getResponse().getStatus());
    verifyNoInteractions(s3, normalizer);
  }

  @Test
  void rejectsWrongToken() {
    WebApplicationException ex = assertThrows(WebApplicationException.class,
      () -> controller.getImage("key", "ek", "wrong-token", POOL));
    assertEquals(401, ex.getResponse().getStatus());
    verifyNoInteractions(s3, normalizer);
  }

  // --- Parameter validation ---

  @Test
  void rejectsMissingKey() {
    WebApplicationException ex = assertThrows(WebApplicationException.class,
      () -> controller.getImage(null, "ek", imageToken.get(), POOL));
    assertEquals(400, ex.getResponse().getStatus());
  }

  @Test
  void rejectsBlankKey() {
    WebApplicationException ex = assertThrows(WebApplicationException.class,
      () -> controller.getImage("  ", "ek", imageToken.get(), POOL));
    assertEquals(400, ex.getResponse().getStatus());
  }

  @Test
  void rejectsMalformedObjectStorageKey() {
    WebApplicationException ex = assertThrows(WebApplicationException.class,
      () -> controller.getImage("../another/object", "ek", imageToken.get(), POOL));
    assertEquals(400, ex.getResponse().getStatus());
  }

  @Test
  void rejectsMalformedPool() {
    for (String pool : new String[] { null, "", "user-1", "A".repeat(32) }) {
      WebApplicationException ex = assertThrows(WebApplicationException.class,
        () -> controller.getImage("key", "ek", imageToken.get(), pool));
      assertEquals(400, ex.getResponse().getStatus());
    }
    verifyNoInteractions(s3, normalizer);
  }

  @Test
  void rejectsMissingEncryptionKey() {
    WebApplicationException ex = assertThrows(WebApplicationException.class,
      () -> controller.getImage("key", null, imageToken.get(), POOL));
    assertEquals(400, ex.getResponse().getStatus());
  }

  // --- Normalization ---

  @Test
  void streamsTheDecryptedImageInAndThePpmOut() throws Exception {
    byte[] plaintext = withJpegSignature("Hello, this is a test image!".getBytes());
    stubS3(encrypt(plaintext));

    Response              response = controller.getImage("attachments/test.enc", testKeyBase64(), imageToken.get(), POOL);
    ByteArrayOutputStream body     = new ByteArrayOutputStream();
    ((StreamingOutput) response.getEntity()).write(body);

    NormalizedImage image = NormalizerFrames.image(2, 2);
    ByteArrayOutputStream expected = new ByteArrayOutputStream();
    expected.write(image.ppmHeader());
    expected.write(PIXELS);

    assertEquals(200, response.getStatus());
    assertEquals(NormalizedImage.MEDIA_TYPE, response.getMediaType().toString());
    assertEquals(String.valueOf(image.ppmLength()), response.getHeaderString(HttpHeaders.CONTENT_LENGTH));
    assertArrayEquals(expected.toByteArray(), body.toByteArray());
    assertArrayEquals(plaintext, sent.toByteArray(), "the normalizer receives the decrypted bytes, header included");
    verify(stream).close();
  }

  @Test
  void endsTheResponseAndReleasesTheStreamWhenStreamingFails() throws Exception {
    stubS3(encrypt(withJpegSignature(new byte[64])));
    doThrow(new IOException("Normalized image is truncated")).when(stream).writeTo(any());

    Response response = controller.getImage("attachments/test.enc", testKeyBase64(), imageToken.get(), POOL);

    assertThrows(IOException.class, () -> ((StreamingOutput) response.getEntity()).write(new ByteArrayOutputStream()));
    verify(stream).close();
  }

  @Test
  void normalizesTheImageAgainOnEveryRequest() throws Exception {
    stubS3(encrypt(withJpegSignature(new byte[64])));

    controller.getImage("attachments/test.enc", testKeyBase64(), imageToken.get(), POOL);
    controller.getImage("attachments/test.enc", testKeyBase64(), imageToken.get(), POOL);

    verify(s3, times(2)).getObject(any(GetObjectRequest.class));
    verify(normalizer, times(2)).normalize(eq(POOL), any());
  }

  @Test
  void rejectsUnsupportedImageFormatsBeforeTheNormalizer() throws Exception {
    stubS3(encrypt("%!PS-Adobe-3.0 EPSF-3.0\n%%BoundingBox: 0 0 1 1\n".getBytes()));

    WebApplicationException error = assertThrows(WebApplicationException.class,
      () -> controller.getImage("attachments/test.enc", testKeyBase64(), imageToken.get(), POOL));

    assertEquals(415, error.getResponse().getStatus());
    verifyNoInteractions(normalizer);
  }

  @Test
  void rejectsImagesTheNormalizerCannotConvert() throws Exception {
    stubS3(encrypt(withJpegSignature(new byte[64])));
    when(normalizer.normalize(eq(POOL), any())).thenThrow(new InvalidNormalizedImageException("Normalized image has no header"));

    WebApplicationException error = assertThrows(WebApplicationException.class,
      () -> controller.getImage("attachments/test.enc", testKeyBase64(), imageToken.get(), POOL));

    assertEquals(422, error.getResponse().getStatus());
  }

  @Test
  void failsWhenTheNormalizerIsUnavailable() throws Exception {
    stubS3(encrypt(withJpegSignature(new byte[64])));
    when(normalizer.normalize(eq(POOL), any())).thenThrow(new IOException("Connection refused"));

    WebApplicationException error = assertThrows(WebApplicationException.class,
      () -> controller.getImage("attachments/test.enc", testKeyBase64(), imageToken.get(), POOL));

    assertEquals(500, error.getResponse().getStatus());
  }

  @Test
  void rejectsOversizedEncryptedImageBeforeDecryption() {
    when(config.getS3Bucket()).thenReturn("test-bucket");
    when(s3.getObject(any(GetObjectRequest.class)))
      .thenReturn(new ResponseInputStream<>(
          GetObjectResponse.builder().contentLength(65L * 1024 * 1024).build(),
          AbortableInputStream.create(new ByteArrayInputStream(new byte[0]))));

    WebApplicationException error = assertThrows(WebApplicationException.class,
      () -> controller.getImage("opaque-namespace/image", Base64.getEncoder().encodeToString(new byte[32]), imageToken.get(), POOL));

    assertEquals(500, error.getResponse().getStatus());
    verifyNoInteractions(normalizer);
  }

  // --- Decryption failures ---

  @Test
  void failsWhenCorruptedWhileStreamingToTheNormalizer() throws Exception {
    int    chunkSize = 64 * 1024;
    byte[] plaintext = new byte[chunkSize * 2];
    java.util.Arrays.fill(plaintext, (byte) 'A');
    System.arraycopy(JPEG_SIGNATURE, 0, plaintext, 0, JPEG_SIGNATURE.length);

    byte[] encrypted = encrypt(plaintext, chunkSize);

    // Corrupt a byte in the second chunk's ciphertext region.
    // Header is 21 bytes; first encrypted chunk is IV(12) + chunkSize + tag(16).
    int firstEncChunkSize = 12 + chunkSize + 16;
    int secondChunkCiphertextOffset = 21 + firstEncChunkSize + 12; // skip header + chunk1 + chunk2 IV
    encrypted[secondChunkCiphertextOffset] ^= 0xFF;

    stubS3(encrypted);

    WebApplicationException error = assertThrows(WebApplicationException.class,
      () -> controller.getImage("attachments/test.enc", testKeyBase64(), imageToken.get(), POOL));

    assertEquals(500, error.getResponse().getStatus());
  }

  @Test
  void failsWhenTruncated() throws Exception {
    int    chunkSize = 64;
    byte[] plaintext = new byte[chunkSize * 2];
    java.util.Arrays.fill(plaintext, (byte) 'B');
    System.arraycopy(JPEG_SIGNATURE, 0, plaintext, 0, JPEG_SIGNATURE.length);

    byte[] encrypted = encrypt(plaintext, chunkSize);

    // Truncate: cut off the last 20 bytes (removes part of the final chunk)
    stubS3(java.util.Arrays.copyOf(encrypted, encrypted.length - 20));

    WebApplicationException error = assertThrows(WebApplicationException.class,
      () -> controller.getImage("attachments/test.enc", testKeyBase64(), imageToken.get(), POOL));

    assertEquals(500, error.getResponse().getStatus());
  }

  @Test
  void failsWhenWrongKey() throws Exception {
    stubS3(encrypt("Secret content".getBytes()));

    WebApplicationException error = assertThrows(WebApplicationException.class,
      () -> controller.getImage("attachments/test.enc", otherKeyBase64(), imageToken.get(), POOL));

    assertEquals(500, error.getResponse().getStatus());
    verifyNoInteractions(normalizer);
  }

  // --- Helpers ---

  private static SecretKey testKey;

  /** Like the real normalizer: reads the whole image (surfacing decryption failures), then answers. */
  private static Answer<NormalizedImageStream> sendingTo(OutputStream sent, NormalizedImageStream stream) {
    return invocation -> {
      ((InputStream) invocation.getArgument(1)).transferTo(sent);
      return stream;
    };
  }

  private static NormalizedImageStream streamOf(NormalizedImage image, byte[] pixels) throws IOException {
    NormalizedImageStream stream = mock(NormalizedImageStream.class);
    lenient().when(stream.image()).thenReturn(image);
    lenient().doAnswer(invocation -> {
      OutputStream output = invocation.getArgument(0);
      output.write(image.ppmHeader());
      output.write(pixels);
      return null;
    }).when(stream).writeTo(any());
    return stream;
  }
  private static SecretKey otherKey;

  private void stubS3(byte[] encrypted) {
    when(config.getS3Bucket()).thenReturn("test-bucket");
    when(s3.getObject(any(GetObjectRequest.class)))
      .thenAnswer(invocation -> new ResponseInputStream<>(
          GetObjectResponse.builder().contentLength((long) encrypted.length).build(),
          AbortableInputStream.create(new ByteArrayInputStream(encrypted))));
  }

  private static SecretKey testKey() throws NoSuchAlgorithmException {
    if (testKey == null) {
      testKey = generateKey();
    }
    return testKey;
  }

  private static String testKeyBase64() throws NoSuchAlgorithmException {
    return Base64.getEncoder().encodeToString(testKey().getEncoded());
  }

  private static String otherKeyBase64() throws NoSuchAlgorithmException {
    if (otherKey == null) {
      otherKey = generateKey();
    }
    return Base64.getEncoder().encodeToString(otherKey.getEncoded());
  }

  private static SecretKey generateKey() throws NoSuchAlgorithmException {
    KeyGenerator gen = KeyGenerator.getInstance("AES");
    gen.init(256);
    return gen.generateKey();
  }

  private static byte[] withJpegSignature(byte[] body) {
    byte[] image = java.util.Arrays.copyOf(JPEG_SIGNATURE, JPEG_SIGNATURE.length + body.length);
    System.arraycopy(body, 0, image, JPEG_SIGNATURE.length, body.length);
    return image;
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
