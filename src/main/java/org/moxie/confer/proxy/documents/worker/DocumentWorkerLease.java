package org.moxie.confer.proxy.documents.worker;

import java.io.IOException;
import java.util.Objects;

public class DocumentWorkerLease implements AutoCloseable {

  private final DocumentWorkerConnection connection;
  private final DocumentWorkerScheduler  scheduler;

  private boolean closed;

  DocumentWorkerLease(DocumentWorkerConnection connection,
                      DocumentWorkerScheduler scheduler)
  {
    this.connection = Objects.requireNonNull(connection, "connection");
    this.scheduler  = Objects.requireNonNull(scheduler, "scheduler");
  }

  public DocumentWorkerConnection connection() {
    return connection;
  }

  DocumentExtraction extract(DocumentExtractionOperation operation) throws IOException {
    return new DocumentExtraction(this, operation.execute(connection));
  }

  @Override
  public synchronized void close() {
    if (closed) {
      return;
    }

    closed = true;
    try {
      connection.close();
    } finally {
      scheduler.release();
    }
  }
}
