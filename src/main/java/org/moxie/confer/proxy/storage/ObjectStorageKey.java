package org.moxie.confer.proxy.storage;

import java.util.regex.Pattern;

/**
 * An opaque object-storage key constrained to the subset Confer accepts from clients.
 *
 * <p>The key is a locator, not an identity. Authorization to read its plaintext comes
 * from possession of the corresponding encryption key.</p>
 */
public class ObjectStorageKey {

  private static final int MAX_CHARS = 1_024;
  private static final Pattern VALID_CHARS = Pattern.compile("^[A-Za-z0-9._/-]+$");

  private final String value;

  public ObjectStorageKey(String value) throws InvalidObjectStorageKeyException {
    if (value == null
        || value.isBlank()
        || value.length() > MAX_CHARS
        || value.startsWith("/")
        || value.contains("..")
        || !VALID_CHARS.matcher(value).matches())
    {
      throw new InvalidObjectStorageKeyException("Object storage key is invalid");
    }
    this.value = value;
  }

  public String value() {
    return value;
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof ObjectStorageKey key && value.equals(key.value);
  }

  @Override
  public int hashCode() {
    return value.hashCode();
  }

  @Override
  public String toString() {
    return value;
  }
}
