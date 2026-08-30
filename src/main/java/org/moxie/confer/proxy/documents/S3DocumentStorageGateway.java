package org.moxie.confer.proxy.documents;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.moxie.confer.proxy.config.Config;
import org.moxie.confer.proxy.crypto.ChunkedCipherInputStream;
import org.moxie.confer.proxy.storage.InvalidObjectStorageKeyException;
import org.moxie.confer.proxy.storage.ObjectStorageKey;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.InputStream;
import java.util.Base64;
import java.util.Objects;

@ApplicationScoped
public class S3DocumentStorageGateway implements DocumentStorageGateway, DocumentStorageWriter {

  private static final long   MAX_ENCRYPTED_BYTES = 320L * 1024 * 1024;
  private static final String PENDING_TAGGING     = "status=pending";

  private final S3Client      s3;
  private final S3AsyncClient asyncS3;
  private final String        bucket;

  @Inject
  public S3DocumentStorageGateway(S3Client s3,
                                  S3AsyncClient asyncS3,
                                  Config config)
  {
    this.s3      = Objects.requireNonNull(s3, "s3");
    this.asyncS3 = Objects.requireNonNull(asyncS3, "asyncS3");
    this.bucket  = config.getS3Bucket();
  }

  @Override
  public DecryptedDocument open(String objectKey, String encryptionKey) throws IOException {
    validateObjectKey(objectKey);

    GetObjectRequest request = GetObjectRequest.builder()
                                               .bucket(bucket)
                                               .key(objectKey)
                                               .build();

    try {
      return decrypt(s3.getObject(request), encryptionKey);
    } catch (SdkException error) {
      if (error instanceof S3Exception s3Error && s3Error.statusCode() == 404) {
        throw new DocumentNotFoundException();
      }
      throw new IOException("Document storage is unavailable", error);
    }
  }

  @Override
  public long store(String objectKey,
                    String encryptionKey,
                    InputStream content,
                    long maximumBytes)
    throws IOException
  {
    if (maximumBytes < 0) {
      throw new IOException("Plaintext size limit is invalid");
    }
    validateObjectKey(objectKey);
    Objects.requireNonNull(content, "content");
    SecretKey key = encryptionKey(encryptionKey);

    PutObjectRequest request = PutObjectRequest.builder()
                                               .bucket(bucket)
                                               .key(objectKey)
                                               .contentType("application/octet-stream")
                                               .tagging(PENDING_TAGGING)
                                               .build();

    try (S3DocumentUpload upload = new S3DocumentUpload(asyncS3, request)) {
      return upload.write(content, key, maximumBytes);
    }
  }

  private static void validateObjectKey(String objectKey) throws IOException {
    try {
      new ObjectStorageKey(objectKey);
    } catch (InvalidObjectStorageKeyException error) {
      throw new IOException("Document object key is invalid");
    }
  }

  private static DecryptedDocument decrypt(ResponseInputStream<GetObjectResponse> encrypted,
                                           String encryptionKey)
    throws IOException
  {
    try {
      long encryptedLength = encrypted.response().contentLength();

      if (encryptedLength <= 0 || encryptedLength > MAX_ENCRYPTED_BYTES) {
        throw new IOException("Encrypted document size is invalid");
      }

      ChunkedCipherInputStream plaintext = new ChunkedCipherInputStream(encrypted, encryptionKey);
      return new DecryptedDocument(plaintext, plaintext.plaintextLength(encryptedLength));
    } catch (IOException error) {
      encrypted.abort();
      throw new IOException("Encrypted document is invalid", error);
    }
  }

  private static SecretKey encryptionKey(String value) throws IOException {
    if (value == null) {
      throw new IOException("Document encryption key is invalid");
    }

    try {
      byte[] decoded = Base64.getDecoder().decode(value);

      if (decoded.length != 32) {
        throw new IOException("Document encryption key is invalid");
      }

      return new SecretKeySpec(decoded, "AES");
    } catch (IllegalArgumentException error) {
      throw new IOException("Document encryption key is invalid", error);
    }
  }
}
