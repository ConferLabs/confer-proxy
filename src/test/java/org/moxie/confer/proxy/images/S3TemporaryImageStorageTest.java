package org.moxie.confer.proxy.images;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.moxie.confer.proxy.config.Config;
import org.moxie.confer.proxy.crypto.ChunkedCipher;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.IOException;
import java.io.InputStream;

import static org.moxie.confer.proxy.crypto.ChunkedCipher.Mode.DECRYPT;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class S3TemporaryImageStorageTest {

  private static final byte[] PNG = new byte[] {
      (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a
  };

  @Mock
  private S3Client s3;

  @Mock
  private Config config;

  private S3TemporaryImageStorage storage;

  @BeforeEach
  void setUp() {
    storage = new S3TemporaryImageStorage(s3, config);
  }

  @Test
  void encryptsPngBeforeWritingItToTheTemporaryPrefix() throws Exception {
    when(config.getS3Bucket()).thenReturn("attachments");

    ImageReference reference = storage.storePng(PNG);
    ArgumentCaptor<PutObjectRequest> request = ArgumentCaptor.forClass(PutObjectRequest.class);
    ArgumentCaptor<RequestBody> body = ArgumentCaptor.forClass(RequestBody.class);
    verify(s3).putObject(request.capture(), body.capture());
    byte[] encrypted;
    try (InputStream input = body.getValue().contentStreamProvider().newStream()) {
      encrypted = input.readAllBytes();
    }

    assertEquals("attachments", request.getValue().bucket());
    assertEquals(reference.s3Key(), request.getValue().key());
    assertTrue(reference.s3Key().startsWith(S3TemporaryImageStorage.OBJECT_PREFIX));
    assertEquals("image/png", reference.mediaType());
    assertArrayEquals(
        PNG,
        new ChunkedCipher(reference.encryptionKey(), DECRYPT).doFinal(encrypted));
  }

  @Test
  void rejectsNonPngContentBeforeWritingToS3() {
    assertThrows(
        IOException.class,
        () -> storage.storePng(new byte[] {1, 2, 3}));
  }
}
