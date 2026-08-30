package org.moxie.confer.proxy.documents;

import org.moxie.confer.proxy.storage.InvalidObjectStorageKeyException;
import org.moxie.confer.proxy.storage.ObjectStorageKey;

public class DocumentObjectKeys {

  private static final String TEXT_SUFFIX     = ".txt";
  private static final String ARTIFACT_SUFFIX = ".artifact";

  private final ObjectStorageKey source;
  private final ObjectStorageKey text;
  private final ObjectStorageKey artifact;

  public DocumentObjectKeys(String source) throws InvalidObjectStorageKeyException {
    if (source == null
        || source.endsWith(TEXT_SUFFIX)
        || source.endsWith(ARTIFACT_SUFFIX))
    {
      throw new InvalidObjectStorageKeyException("Document source object key is invalid");
    }

    this.source   = new ObjectStorageKey(source);
    this.text     = new ObjectStorageKey(source + TEXT_SUFFIX);
    this.artifact = new ObjectStorageKey(source + ARTIFACT_SUFFIX);
  }

  public String source() {
    return source.value();
  }

  public String text() {
    return text.value();
  }

  public String artifact() {
    return artifact.value();
  }
}
