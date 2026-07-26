package org.moxie.confer.proxy.documents;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.moxie.confer.proxy.config.Config;
import org.moxie.confer.proxy.crypto.ChunkedCipher;
import org.moxie.confer.proxy.crypto.ChunkedCipherOutputStream;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.async.AsyncRequestBody;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;

import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.moxie.confer.proxy.crypto.ChunkedCipher.Mode.DECRYPT;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class S3DocumentStorageGatewayTest {

  private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);

  @Mock
  private S3Client s3;

  @Mock
  private S3AsyncClient asyncS3;

  @Mock
  private Config config;

  private S3DocumentStorageGateway storage;

  @BeforeEach
  void setUp() {
    when(config.getS3Bucket()).thenReturn("attachments");
    storage = new S3DocumentStorageGateway(s3, asyncS3, config);
  }

  @Test
  void streamsEncryptedContentToTheExactObjectKeyWithoutAKnownLength() throws Exception {
    byte[] plaintext = new byte[2 * 1024 * 1024 + 17];
    Arrays.fill(plaintext, (byte) 7);
    AtomicReference<PutObjectRequest> request = new AtomicReference<>();
    AtomicReference<AsyncRequestBody> body = new AtomicReference<>();
    AtomicReference<byte[]> encrypted = new AtomicReference<>();
    when(asyncS3.putObject(any(PutObjectRequest.class), any(AsyncRequestBody.class)))
        .thenAnswer(invocation -> {
          request.set(invocation.getArgument(0));
          body.set(invocation.getArgument(1));
          return consume(body.get(), encrypted);
        });
    TrackingInputStream content = new TrackingInputStream(plaintext);

    storage.store("opaque/attachment.artifact", KEY, content);

    assertEquals("attachments", request.get().bucket());
    assertEquals("opaque/attachment.artifact", request.get().key());
    assertEquals("application/octet-stream", request.get().contentType());
    assertEquals("status=pending", request.get().tagging());
    assertTrue(body.get().contentLength().isEmpty());
    assertFalse(content.closed());
    assertTrue(content.maximumRequestedRead() <= 8 * 1024);
    assertArrayEquals(
        plaintext,
        new ChunkedCipher(KEY, DECRYPT).doFinal(encrypted.get()));
  }

  @Test
  void transfersTheOpenS3StreamToTheReturnedDocument() throws Exception {
    byte[] plaintext = "document".getBytes(StandardCharsets.UTF_8);
    byte[] encrypted = encrypt(plaintext);
    TrackingInputStream source = new TrackingInputStream(encrypted);
    AtomicBoolean aborted = new AtomicBoolean();
    ResponseInputStream<GetObjectResponse> response = new ResponseInputStream<>(
        GetObjectResponse.builder().contentLength((long) encrypted.length).build(),
        AbortableInputStream.create(source, () -> aborted.set(true)));
    when(s3.getObject(any(GetObjectRequest.class))).thenReturn(response);

    try (DecryptedDocument document = storage.open("opaque/attachment", KEY)) {
      assertFalse(source.closed());
      assertFalse(aborted.get());
      assertArrayEquals(plaintext, document.content().readAllBytes());
    }

    assertTrue(source.closed());
    assertFalse(aborted.get());
  }

  @Test
  void abortsAnAcquiredS3StreamWhenValidationFails() {
    TrackingInputStream source = new TrackingInputStream(new byte[] {1});
    AtomicBoolean aborted = new AtomicBoolean();
    ResponseInputStream<GetObjectResponse> response = new ResponseInputStream<>(
        GetObjectResponse.builder().contentLength(0L).build(),
        AbortableInputStream.create(source, () -> aborted.set(true)));
    when(s3.getObject(any(GetObjectRequest.class))).thenReturn(response);

    IOException error = assertThrows(
        IOException.class,
        () -> storage.open("opaque/attachment", KEY));

    assertEquals("Encrypted document is invalid", error.getMessage());
    assertEquals("Encrypted document size is invalid", error.getCause().getMessage());
    assertTrue(aborted.get());
  }

  @Test
  void translatesMissingS3Object() {
    when(s3.getObject(any(GetObjectRequest.class))).thenThrow(
        S3Exception.builder().statusCode(404).build());

    assertThrows(
        DocumentNotFoundException.class,
        () -> storage.open("opaque/attachment", KEY));
  }

  @Test
  void abortsTheUploadWhenThePlaintextStreamFails() {
    AtomicReference<CompletableFuture<PutObjectResponse>> upload = new AtomicReference<>();
    when(asyncS3.putObject(any(PutObjectRequest.class), any(AsyncRequestBody.class)))
        .thenAnswer(invocation -> {
          AtomicReference<byte[]> ignored = new AtomicReference<>();
          CompletableFuture<PutObjectResponse> future = consume(
              invocation.getArgument(1),
              ignored);
          upload.set(future);
          return future;
        });
    InputStream content = new InputStream() {
      private boolean first = true;

      @Override
      public int read(byte[] buffer, int offset, int length) throws IOException {
        if (first) {
          first = false;
          buffer[offset] = 1;
          return 1;
        }
        throw new IOException("source failed");
      }

      @Override
      public int read() throws IOException {
        byte[] value = new byte[1];
        int read = read(value, 0, 1);
        return read < 0 ? -1 : Byte.toUnsignedInt(value[0]);
      }
    };

    IOException error = assertThrows(
        IOException.class,
        () -> storage.store("opaque/attachment.artifact", KEY, content));

    assertEquals("source failed", error.getMessage());
    assertTrue(upload.get().isCancelled());
  }

  @Test
  void reportsAnAsynchronousUploadFailure() {
    when(asyncS3.putObject(any(PutObjectRequest.class), any(AsyncRequestBody.class)))
        .thenAnswer(invocation -> {
          AtomicReference<byte[]> ignored = new AtomicReference<>();
          return consume(invocation.getArgument(1), ignored)
              .thenCompose(response -> CompletableFuture.<PutObjectResponse>failedFuture(
                  new IllegalStateException("upload failed")));
        });

    IOException error = assertThrows(
        IOException.class,
        () -> storage.store(
            "opaque/attachment.artifact",
            KEY,
            new ByteArrayInputStream(new byte[] {1})));

    assertEquals("Document storage is unavailable", error.getMessage());
    assertEquals("upload failed", error.getCause().getMessage());
  }

  @Test
  void rejectsInvalidKeysBeforeStartingAnUpload() {
    assertThrows(
        IOException.class,
        () -> storage.store(
            "../attachment.artifact",
            KEY,
            new ByteArrayInputStream(new byte[] {1})));
    assertThrows(
        IOException.class,
        () -> storage.store(
            "opaque/attachment.artifact",
            "not-a-key",
            new ByteArrayInputStream(new byte[] {1})));

    verifyNoInteractions(asyncS3);
  }

  private static CompletableFuture<PutObjectResponse> consume(
      AsyncRequestBody body,
      AtomicReference<byte[]> content)
  {
    CompletableFuture<PutObjectResponse> result = new CompletableFuture<>();
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    body.subscribe(new Subscriber<>() {
      @Override
      public void onSubscribe(Subscription subscription) {
        subscription.request(Long.MAX_VALUE);
      }

      @Override
      public void onNext(ByteBuffer buffer) {
        byte[] value = new byte[buffer.remaining()];
        buffer.get(value);
        output.writeBytes(value);
      }

      @Override
      public void onError(Throwable error) {
        result.completeExceptionally(error);
      }

      @Override
      public void onComplete() {
        content.set(output.toByteArray());
        result.complete(PutObjectResponse.builder().build());
      }
    });
    return result;
  }

  private static byte[] encrypt(byte[] plaintext) throws IOException {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    try (ChunkedCipherOutputStream encrypted = new ChunkedCipherOutputStream(
        output,
        new SecretKeySpec(new byte[32], "AES")))
    {
      encrypted.write(plaintext);
    }
    return output.toByteArray();
  }

  private static final class TrackingInputStream extends ByteArrayInputStream {

    private final AtomicBoolean closed = new AtomicBoolean();

    private int maximumRequestedRead;

    private TrackingInputStream(byte[] content) {
      super(content);
    }

    @Override
    public synchronized int read(byte[] buffer, int offset, int length) {
      maximumRequestedRead = Math.max(maximumRequestedRead, length);
      return super.read(buffer, offset, length);
    }

    @Override
    public void close() throws IOException {
      closed.set(true);
      super.close();
    }

    private boolean closed() {
      return closed.get();
    }

    private int maximumRequestedRead() {
      return maximumRequestedRead;
    }
  }
}
