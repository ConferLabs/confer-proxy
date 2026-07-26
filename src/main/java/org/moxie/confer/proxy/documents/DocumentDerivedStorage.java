package org.moxie.confer.proxy.documents;

import java.io.IOException;
import java.io.InputStream;

public interface DocumentDerivedStorage {

  /**
   * Encrypts and stores all bytes from {@code content} before returning.
   * The caller retains ownership of the input stream.
   */
  void store(String objectKey,
             String encryptionKey,
             InputStream content)
    throws IOException;
}
