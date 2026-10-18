package org.moxie.confer.proxy.documents.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.moxie.confer.proxy.config.Config;
import org.moxie.confer.proxy.documents.DocumentStorageGateway;
import org.moxie.confer.proxy.workers.WorkerProcess;
import org.moxie.confer.proxy.workers.WorkerWorkspace;

import java.io.ByteArrayInputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DocumentWorkerSchedulersTest {

  @Test
  void limitsEachConnectionToTheWorkerProcessChannels() throws Exception {
    DocumentWorkerSchedulers schedulers = schedulers();
    DocumentWorkerScheduler  first      = schedulers.forWorkspace(workspace());
    DocumentWorkerScheduler  second     = schedulers.forWorkspace(workspace());
    List<DocumentWorkerLease> leases    = new ArrayList<>();

    try {
      for (int index = 0; index < RemoteDocumentWorkerGateway.MAX_PROCESSES; index++) {
        leases.add(first.acquire());
      }
      assertEquals(8, first.activeConnections());
      assertThrows(DocumentWorkerTimeoutException.class, first::acquire);

      leases.add(second.acquire());
      assertEquals(1, second.activeConnections());
    } finally {
      leases.forEach(DocumentWorkerLease::close);
    }
  }

  private static DocumentWorkerSchedulers schedulers() {
    Config config = mock(Config.class);
    when(config.getDocumentWorkerAcquireTimeoutSeconds()).thenReturn(1L);
    return new DocumentWorkerSchedulers(
        config,
        mock(DocumentStorageGateway.class),
        new ObjectMapper(),
        null);
  }

  private static WorkerWorkspace workspace() throws Exception {
    WorkerWorkspace workspace = mock(WorkerWorkspace.class);
    when(workspace.startProcess(anyString())).thenAnswer(invocation -> {
      WorkerProcess process = mock(WorkerProcess.class);
      when(process.getInputStream()).thenReturn(new ByteArrayInputStream(new byte[0]));
      when(process.getOutputStream()).thenReturn(OutputStream.nullOutputStream());
      return process;
    });
    return workspace;
  }
}
