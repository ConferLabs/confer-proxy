package org.moxie.confer.proxy.controllers;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.StreamingOutput;
import org.moxie.confer.proxy.config.Config;
import org.moxie.confer.proxy.crypto.ChunkedCipherInputStream;
import org.moxie.confer.proxy.crypto.ImageToken;
import org.moxie.confer.proxy.storage.InvalidObjectStorageKeyException;
import org.moxie.confer.proxy.storage.ObjectStorageKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;

import java.io.IOException;
import java.io.InputStream;

/**
 * URL format:
 *   /v1/images?key={s3ObjectKey}&ek={base64EncryptionKey}&token={imageToken}
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

  @GET
  public Response getImage(@QueryParam("key")   String objectKey,
                           @QueryParam("ek")    String encryptionKey,
                           @QueryParam("token") String token,
                           @QueryParam("type")  String mediaType)
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

    String contentType = mediaType != null && !mediaType.isBlank() ? mediaType : "application/octet-stream";

    StreamingOutput stream = output -> {
      GetObjectRequest request = GetObjectRequest.builder()
                                                 .bucket(config.getS3Bucket())
                                                 .key(objectKey)
                                                 .build();

      try (ResponseInputStream<GetObjectResponse> encrypted = s3.getObject(request)) {
        long encryptedLength = encrypted.response().contentLength();
        if (encryptedLength <= 0 || encryptedLength > MAX_ENCRYPTED_IMAGE_BYTES) {
          throw new IOException("Encrypted image size is invalid");
        }
        try (InputStream plaintext = new ChunkedCipherInputStream(
            encrypted,
            encryptionKey))
        {
          plaintext.transferTo(output);
          output.flush();
        }
      } catch (IOException e) {
        log.error("Image decryption failed");
        throw new WebApplicationException("Decryption failed", 500);
      }
    };

    return Response.ok(stream, contentType).build();
  }
}
