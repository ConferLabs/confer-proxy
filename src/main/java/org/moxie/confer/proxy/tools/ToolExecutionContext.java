package org.moxie.confer.proxy.tools;

import org.moxie.confer.proxy.documents.DocumentToolSession;
import org.moxie.confer.proxy.workers.WorkerWorkspace;

import java.util.Objects;

public class ToolExecutionContext {

  private final DocumentToolSession documentSession;
  private final WorkerWorkspace     workerWorkspace;

  public ToolExecutionContext(DocumentToolSession documentSession,
                              WorkerWorkspace     workerWorkspace)
  {
    this.documentSession = Objects.requireNonNull(documentSession, "documentSession");
    this.workerWorkspace = Objects.requireNonNull(workerWorkspace, "workerWorkspace");
  }

  public DocumentToolSession getDocumentSession() {
    return documentSession;
  }

  public WorkerWorkspace getWorkerWorkspace() {
    return workerWorkspace;
  }
}
