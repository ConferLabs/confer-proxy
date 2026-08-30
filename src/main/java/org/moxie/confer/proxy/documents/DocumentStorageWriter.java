package org.moxie.confer.proxy.documents;

import java.io.IOException;
import java.io.InputStream;

public interface DocumentStorageWriter {

  /**
   * Encrypts and stores all bytes from {@code content} before returning.
   * The caller retains ownership of the input stream.
   */
  default long store(String objectKey,
                     String encryptionKey,
                     InputStream content)
      throws IOException
  {
    return store(objectKey, encryptionKey, content, Long.MAX_VALUE);
  }

  long store(String objectKey,
             String encryptionKey,
             InputStream content,
             long maximumBytes)
      throws IOException;
}
