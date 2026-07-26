package org.moxie.confer.proxy.tools;

import org.moxie.confer.proxy.documents.DocumentToolSession;

import java.util.Objects;

public final class ToolExecutionContext {

  private final DocumentToolSession documentSession;

  public ToolExecutionContext(DocumentToolSession documentSession) {
    this.documentSession = Objects.requireNonNull(documentSession, "documentSession");
  }

  public DocumentToolSession documentSession() {
    return documentSession;
  }
}
