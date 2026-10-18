package org.moxie.confer.proxy.documents.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.moxie.confer.proxy.documents.DocumentStorageGateway;
import org.moxie.confer.proxy.workers.WorkerException;
import org.moxie.confer.proxy.workers.WorkerProcess;
import org.moxie.confer.proxy.workers.WorkerWorkspace;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RemoteDocumentWorkerGatewayTest {

  private static final String COMMAND = "/usr/local/libexec/confer-document-session";

  @Test
  void runsTheDocumentSessionCommandOnTheConnectionWorker() throws Exception {
    WorkerWorkspace workspace = mock(WorkerWorkspace.class);
    WorkerProcess   process   = process();
    when(workspace.startProcess(COMMAND)).thenReturn(process);

    try (DocumentWorkerConnection ignored = gateway(workspace).connect()) {
      verify(workspace).startProcess(COMMAND);
    }
  }

  @Test
  void closingTheConnectionEndsTheProcess() throws Exception {
    WorkerWorkspace workspace = mock(WorkerWorkspace.class);
    WorkerProcess   process   = process();
    when(workspace.startProcess(COMMAND)).thenReturn(process);

    gateway(workspace).connect().close();

    verify(process).close();
  }

  @Test
  void reportsAWorkerThatCannotStartAProcess() throws Exception {
    WorkerWorkspace workspace = mock(WorkerWorkspace.class);
    WorkerException refused   = new WorkerException("refused");
    when(workspace.startProcess(COMMAND)).thenThrow(refused);

    IOException error = assertThrows(IOException.class, () -> gateway(workspace).connect());

    assertSame(refused, error.getCause());
  }

  private static RemoteDocumentWorkerGateway gateway(WorkerWorkspace workspace) {
    return new RemoteDocumentWorkerGateway(
        workspace,
        mock(DocumentStorageGateway.class),
        new ObjectMapper(),
        null);
  }

  private static WorkerProcess process() {
    WorkerProcess process = mock(WorkerProcess.class);
    when(process.getInputStream()).thenReturn(new ByteArrayInputStream(new byte[0]));
    when(process.getOutputStream()).thenReturn(OutputStream.nullOutputStream());
    return process;
  }
}
