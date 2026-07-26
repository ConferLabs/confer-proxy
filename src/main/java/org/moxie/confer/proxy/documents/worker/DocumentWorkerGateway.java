package org.moxie.confer.proxy.documents.worker;

import java.io.IOException;

public interface DocumentWorkerGateway {

  DocumentWorkerConnection connect() throws IOException;
}
