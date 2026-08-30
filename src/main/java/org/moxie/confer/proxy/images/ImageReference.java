package org.moxie.confer.proxy.images;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.moxie.confer.proxy.storage.InvalidObjectStorageKeyException;
import org.moxie.confer.proxy.storage.ObjectStorageKey;

import java.util.Base64;
import java.util.Objects;
import java.util.regex.Pattern;

public class ImageReference {

  private static final Pattern IMAGE_MEDIA_TYPE = Pattern.compile("^image/[A-Za-z0-9.+-]+$");

  private final String s3Key;
  private final String encryptionKey;
  private final String mediaType;

  @JsonCreator
  public ImageReference(@JsonProperty("s3Key") String s3Key,
                        @JsonProperty("encryptionKey") String encryptionKey,
                        @JsonProperty("mediaType") String mediaType)
    throws InvalidImageReferenceException
  {
    try {
      new ObjectStorageKey(s3Key);

      if (encryptionKey == null || mediaType == null || !IMAGE_MEDIA_TYPE.matcher(mediaType).matches()) {
        throw new IllegalArgumentException("Invalid encrypted image capability");
      }

      byte[] decodedKey = Base64.getDecoder().decode(encryptionKey);

      if (decodedKey.length != 32) {
        throw new IllegalArgumentException("Invalid encrypted image capability");
      }

      this.s3Key         = s3Key;
      this.encryptionKey = encryptionKey;
      this.mediaType     = mediaType;
    } catch (InvalidObjectStorageKeyException | IllegalArgumentException error) {
      throw new InvalidImageReferenceException("Image reference is invalid", error);
    }
  }

  @JsonProperty("s3Key")
  public String s3Key() {
    return s3Key;
  }

  @JsonProperty("encryptionKey")
  public String encryptionKey() {
    return encryptionKey;
  }

  @JsonProperty("mediaType")
  public String mediaType() {
    return mediaType;
  }

  @Override
  public boolean equals(Object other) {
    if (!(other instanceof ImageReference reference)) {
      return false;
    }
    return s3Key.equals(reference.s3Key)
        && encryptionKey.equals(reference.encryptionKey)
        && mediaType.equals(reference.mediaType);
  }

  @Override
  public int hashCode() {
    return Objects.hash(s3Key, encryptionKey, mediaType);
  }
}
