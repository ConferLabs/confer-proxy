package org.moxie.confer.proxy.images;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.moxie.confer.proxy.config.Config;
import org.moxie.confer.proxy.crypto.ChunkedCipherOutputStream;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.UUID;

@ApplicationScoped
public class S3TemporaryImageStorage implements TemporaryImageStorage {

  static final String OBJECT_PREFIX = "temporary-images/";

  private static final byte[] PNG_SIGNATURE = new byte[] {
      (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a
  };
  private static final int MAX_IMAGE_BYTES = 16 * 1024 * 1024;

  private final S3Client     s3;
  private final Config       config;
  private final SecureRandom random = new SecureRandom();

  @Inject
  public S3TemporaryImageStorage(S3Client s3,
                                 Config config)
  {
    this.s3 = s3;
    this.config = config;
  }

  @Override
  public ImageReference storePng(byte[] png) throws IOException {
    validatePng(png);
    byte[] rawKey = new byte[32];
    random.nextBytes(rawKey);
    String objectKey = OBJECT_PREFIX + UUID.randomUUID();

    try {
      ByteArrayOutputStream encrypted = new ByteArrayOutputStream();
      try (ChunkedCipherOutputStream cipher = new ChunkedCipherOutputStream(
          encrypted,
          new SecretKeySpec(rawKey, "AES")))
      {
        cipher.write(png);
      }
      PutObjectRequest request = PutObjectRequest.builder()
                                                 .bucket(config.getS3Bucket())
                                                 .key(objectKey)
                                                 .contentType("application/octet-stream")
                                                 .build();
      s3.putObject(request, RequestBody.fromBytes(encrypted.toByteArray()));
      return new ImageReference(
          objectKey,
          Base64.getEncoder().encodeToString(rawKey),
          "image/png");
    } catch (SdkException | InvalidImageReferenceException error) {
      throw new IOException("Temporary image storage failed", error);
    } finally {
      Arrays.fill(rawKey, (byte) 0);
    }
  }

  private void validatePng(byte[] png) throws IOException {
    if (png == null || png.length < PNG_SIGNATURE.length || png.length > MAX_IMAGE_BYTES) {
      throw new IOException("Rendered document image size is invalid");
    }
    for (int index = 0; index < PNG_SIGNATURE.length; index++) {
      if (png[index] != PNG_SIGNATURE[index]) {
        throw new IOException("Rendered document image format is invalid");
      }
    }
  }
}
