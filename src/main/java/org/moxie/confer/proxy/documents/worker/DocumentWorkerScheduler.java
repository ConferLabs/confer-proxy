package org.moxie.confer.proxy.documents.worker;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.moxie.confer.proxy.config.Config;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

@ApplicationScoped
public class DocumentWorkerScheduler {

  private final DocumentWorkerGateway gateway;
  private final Semaphore             connections;
  private final Duration              acquireTimeout;
  private final int                   maxConnections;

  @Inject
  public DocumentWorkerScheduler(Config config,
                                 DocumentWorkerGateway gateway)
  {
    this(gateway,
        config.getDocumentWorkerMaxConnections(),
        Duration.ofSeconds(config.getDocumentWorkerAcquireTimeoutSeconds()));
  }

  DocumentWorkerScheduler(DocumentWorkerGateway gateway,
                          int maxConnections,
                          Duration acquireTimeout)
  {
    if (maxConnections < 1) {
      throw new IllegalArgumentException("Document worker max connections must be positive");
    }
    if (acquireTimeout.isZero() || acquireTimeout.isNegative()) {
      throw new IllegalArgumentException("Document worker acquire timeout must be positive");
    }

    this.gateway        = gateway;
    this.connections    = new Semaphore(maxConnections, true);
    this.acquireTimeout = acquireTimeout;
    this.maxConnections = maxConnections;
  }

  public DocumentWorkerLease acquire() throws IOException {
    boolean                  permitAcquired = false;
    boolean                  transferred    = false;
    DocumentWorkerConnection connection     = null;

    try {
      try {
        permitAcquired = connections.tryAcquire(
            acquireTimeout.toMillis(),
            TimeUnit.MILLISECONDS);
      } catch (InterruptedException error) {
        throw new IOException("Interrupted while waiting for a document worker", error);
      }

      if (!permitAcquired) {
        throw new DocumentWorkerTimeoutException();
      }

      connection = gateway.connect();
      if (connection == null) {
        throw new IOException("Document worker gateway returned no connection");
      }

      DocumentWorkerLease worker = new DocumentWorkerLease(connection, this);
      transferred = true;
      return worker;
    } finally {
      if (!transferred && permitAcquired) {
        try {
          if (connection != null) {
            connection.close();
          }
        } finally {
          connections.release();
        }
      }
    }
  }

  public DocumentExtraction extract(DocumentExtractionOperation operation) throws IOException {
    DocumentWorkerLease worker = null;

    try {
      worker = acquire();
      DocumentExtraction extraction = worker.extract(operation);
      worker = null;
      return extraction;
    } finally {
      if (worker != null) {
        worker.close();
      }
    }
  }

  void release() {
    connections.release();
  }

  int activeConnections() {
    return maxConnections - connections.availablePermits();
  }

  int waitingRequests() {
    return connections.getQueueLength();
  }
}
