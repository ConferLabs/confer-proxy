package org.moxie.confer.proxy.documents.worker;

import java.io.IOException;
import java.io.InputStream;

@FunctionalInterface
public interface DocumentWorkerPayloadHandler {

  void handle(DocumentWorkerPayloadDescriptor descriptor,
              InputStream content)
    throws IOException;
}
