package org.moxie.confer.proxy.documents;

import java.io.IOException;
import java.io.InputStream;

public class DecryptedDocument implements AutoCloseable {

  private final InputStream content;
  private final long        length;

  public DecryptedDocument(InputStream content, long length) {
    this.content = content;
    this.length = length;
  }

  public InputStream content() {
    return content;
  }

  public long length() {
    return length;
  }

  @Override
  public void close() throws IOException {
    content.close();
  }
}
