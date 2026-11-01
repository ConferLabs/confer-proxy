package org.moxie.confer.proxy.controllers;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.StreamingOutput;
import org.moxie.confer.proxy.config.Config;
import org.moxie.confer.proxy.crypto.ChunkedCipherInputStream;
import org.moxie.confer.proxy.crypto.ImageToken;
import org.moxie.confer.proxy.images.ImageNormalizer;
import org.moxie.confer.proxy.images.ImagePoolKey;
import org.moxie.confer.proxy.images.ImageSignature;
import org.moxie.confer.proxy.images.InvalidNormalizedImageException;
import org.moxie.confer.proxy.images.NormalizedImage;
import org.moxie.confer.proxy.images.NormalizedImageStream;
import org.moxie.confer.proxy.storage.InvalidObjectStorageKeyException;
import org.moxie.confer.proxy.storage.ObjectStorageKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;

/**
 * Serves an attached image to vLLM, which fetches it over loopback from the URL the message
 * presenter builds. vLLM never receives the uploaded bytes: they stream from S3 through
 * decryption into the requesting connection's sandboxed normalizer, and its pixels stream back
 * out to vLLM behind a PPM header the proxy writes. The pool names that connection's normalizer
 * (see {@link ImagePoolKey}).
 *
 * URL format:
 *   /v1/images?key={s3ObjectKey}&ek={base64EncryptionKey}&token={imageToken}&pool={poolKey}
 */
@ApplicationScoped
@Path("/v1/images")
public class ImageController {

  private static final Logger log = LoggerFactory.getLogger(ImageController.class);
  private static final long MAX_ENCRYPTED_IMAGE_BYTES = 64L * 1024 * 1024;

  @Inject
  S3Client s3;

  @Inject
  Config config;

  @Inject
  ImageToken imageToken;

  @Inject
  ImageNormalizer normalizer;

  @GET
  public Response getImage(@QueryParam("key")   String objectKey,
                           @QueryParam("ek")    String encryptionKey,
                           @QueryParam("token") String token,
                           @QueryParam("pool")  String pool)
  {
    if (token == null || !imageToken.isValid(token)) {
      throw new WebApplicationException(Response.Status.UNAUTHORIZED);
    }

    if (objectKey == null || objectKey.isBlank()) {
      throw new WebApplicationException("Missing 'key' parameter", 400);
    }
    try {
      new ObjectStorageKey(objectKey);
    } catch (InvalidObjectStorageKeyException error) {
      throw new WebApplicationException("Invalid 'key' parameter", 400);
    }

    if (encryptionKey == null || encryptionKey.isBlank()) {
      throw new WebApplicationException("Missing 'ek' parameter", 400);
    }

    if (!ImagePoolKey.isWellFormed(pool)) {
      throw new WebApplicationException("Invalid 'pool' parameter", 400);
    }

    NormalizedImageStream normalized;
    try {
      normalized = normalize(pool, objectKey, encryptionKey);
    } catch (InvalidNormalizedImageException error) {
      log.warn("Rejected an image the normalizer could not convert");
      throw new WebApplicationException("Unsupported image", 422);
    } catch (IOException error) {
      log.error("Image decryption or normalization failed: {}", error.toString());
      throw new WebApplicationException("Image unavailable", 500);
    }

    // Throwing after the first byte resets the connection, so vLLM never decodes a short image.
    StreamingOutput body = output -> {
      try (normalized) {
        normalized.writeTo(output);
      } catch (IOException error) {
        log.error("Streaming a normalized image failed: {}", error.toString());
        throw error;
      }
    };

    return Response.ok(body, NormalizedImage.MEDIA_TYPE)
                   .header(HttpHeaders.CONTENT_LENGTH, normalized.image().ppmLength())
                   .build();
  }

  /**
   * Opens the object before taking a slot on the pool's normalizer connection, so the S3 round
   * trip does not hold one; images outside the allowlist never reach a normalizer.
   */
  private NormalizedImageStream normalize(String pool,
                                          String objectKey,
                                          String encryptionKey)
      throws IOException, InvalidNormalizedImageException
  {
    GetObjectRequest request = GetObjectRequest.builder()
                                               .bucket(config.getS3Bucket())
                                               .key(objectKey)
                                               .build();

    try (ResponseInputStream<GetObjectResponse> encrypted = s3.getObject(request)) {
      long encryptedLength = encrypted.response().contentLength();
      if (encryptedLength <= 0 || encryptedLength > MAX_ENCRYPTED_IMAGE_BYTES) {
        throw new IOException("Encrypted image size is invalid");
      }

      try (InputStream plaintext = new ChunkedCipherInputStream(encrypted, encryptionKey)) {
        byte[] header = plaintext.readNBytes(ImageSignature.HEADER_LENGTH);
        if (!ImageSignature.isAllowed(header)) {
          log.warn("Rejected image with an unsupported format");
          throw new WebApplicationException("Unsupported image format", Response.Status.UNSUPPORTED_MEDIA_TYPE);
        }

        return normalizer.normalize(pool, new SequenceInputStream(new ByteArrayInputStream(header), plaintext));
      }
    }
  }
}
