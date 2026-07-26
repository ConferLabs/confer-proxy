package org.moxie.confer.proxy.documents.worker;

import java.io.IOException;

@FunctionalInterface
public interface DocumentExtractionOperation {

  DocumentExtractionResponse execute(DocumentWorkerConnection connection) throws IOException;
}
