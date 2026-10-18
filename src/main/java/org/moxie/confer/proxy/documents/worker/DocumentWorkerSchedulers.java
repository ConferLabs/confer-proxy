package org.moxie.confer.proxy.documents.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.validation.Validator;
import org.moxie.confer.proxy.config.Config;
import org.moxie.confer.proxy.documents.DocumentStorageGateway;
import org.moxie.confer.proxy.workers.WorkerWorkspace;

import java.time.Duration;

/**
 * Creates a connection's document worker scheduler. Document processes run on
 * the connection's own confidential worker, so each connection has its own
 * scheduler and process limit.
 */
@ApplicationScoped
public class DocumentWorkerSchedulers {

  private final DocumentStorageGateway storage;
  private final ObjectMapper           mapper;
  private final Validator              validator;
  private final Duration               acquireTimeout;

  @Inject
  public DocumentWorkerSchedulers(Config                 config,
                                  DocumentStorageGateway storage,
                                  ObjectMapper           mapper,
                                  Validator              validator)
  {
    this.storage        = storage;
    this.mapper         = mapper;
    this.validator      = validator;
    this.acquireTimeout = Duration.ofSeconds(config.getDocumentWorkerAcquireTimeoutSeconds());
  }

  public DocumentWorkerScheduler forWorkspace(WorkerWorkspace workspace) {
    return new DocumentWorkerScheduler(
        new RemoteDocumentWorkerGateway(workspace, storage, mapper, validator),
        RemoteDocumentWorkerGateway.MAX_PROCESSES,
        acquireTimeout);
  }
}
