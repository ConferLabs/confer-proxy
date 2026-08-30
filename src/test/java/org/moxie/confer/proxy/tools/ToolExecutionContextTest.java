package org.moxie.confer.proxy.tools;

import org.junit.jupiter.api.Test;
import org.moxie.confer.proxy.documents.DocumentToolSession;
import org.moxie.confer.proxy.workers.WorkerWorkspace;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

class ToolExecutionContextTest {

  @Test
  void requiresDocumentsAndWorkspace() {
    DocumentToolSession documents = mock(DocumentToolSession.class);
    WorkerWorkspace workspace = mock(WorkerWorkspace.class);

    assertThrows(
        NullPointerException.class,
        () -> new ToolExecutionContext(null, workspace));
    assertThrows(
        NullPointerException.class,
        () -> new ToolExecutionContext(documents, null));
  }

  @Test
  void exposesTheRequestDependencies() {
    DocumentToolSession documents = mock(DocumentToolSession.class);
    WorkerWorkspace workspace = mock(WorkerWorkspace.class);
    ToolExecutionContext context = new ToolExecutionContext(
        documents,
        workspace);

    assertSame(documents, context.getDocumentSession());
    assertSame(workspace, context.getWorkerWorkspace());
  }
}
