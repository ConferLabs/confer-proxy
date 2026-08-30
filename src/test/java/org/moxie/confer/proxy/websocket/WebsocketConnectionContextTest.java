package org.moxie.confer.proxy.websocket;

import org.junit.jupiter.api.Test;
import org.moxie.confer.proxy.workers.WorkerClient;
import org.moxie.confer.proxy.workers.WorkerWorkspace;

import java.io.IOException;
import java.io.OutputStream;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class WebsocketConnectionContextTest {

  private static final String ATTACHMENT_PREFIX = "generated-attachments";

  @Test
  void reusesOneWorkspaceUntilTheConnectionCloses() {
    WebsocketConnectionContext context   = context();
    WorkerWorkspace             workspace = mock(WorkerWorkspace.class);
    WorkerClient                client    = client(workspace);

    assertSame(workspace, context.getWorkerWorkspace(client));
    assertSame(workspace, context.getWorkerWorkspace(client));
    verify(client).getWorkspace(ATTACHMENT_PREFIX);

    verify(workspace, never()).close();
    context.close();
    context.close();
    verify(workspace, times(1)).close();
  }

  @Test
  void createsAWorkspaceAfterTheOriginalSubscribedTokenExpires() {
    WebsocketConnectionContext context = new WebsocketConnectionContext(
        ATTACHMENT_PREFIX,
        Instant.EPOCH,
        true);
    WorkerWorkspace workspace = mock(WorkerWorkspace.class);
    WorkerClient client = client(workspace);

    assertSame(workspace, context.getWorkerWorkspace(client));

    verify(client).getWorkspace(ATTACHMENT_PREFIX);
    context.close();
  }

  @Test
  void closesActiveStreams() throws Exception {
    WebsocketConnectionContext context = context();
    OutputStream               stream  = mock(OutputStream.class);
    context.getStreams().createStream(7, stream);

    context.close();

    verify(stream).close();
  }

  @Test
  void closeMakesTheStreamRegistryTerminal() {
    WebsocketConnectionContext context = context();
    OutputStream               stream  = mock(OutputStream.class);
    context.close();

    assertThrows(
        IOException.class,
        () -> context.getStreams().createStream(7, stream));
    assertThrows(
        IOException.class,
        () -> context.getStreams().handleChunk(7, new byte[] {1}, 0, true));
  }

  @Test
  void neverCreatesAWorkspaceAfterClose() {
    WebsocketConnectionContext context   = context();
    WorkerClient                client    = mock(WorkerClient.class);
    context.close();

    assertThrows(
        IllegalStateException.class,
        () -> context.getWorkerWorkspace(client));
    verifyNoInteractions(client);
  }

  @Test
  void concurrentRequestsCreateOneWorkspace() throws Exception {
    WebsocketConnectionContext context   = context();
    WorkerWorkspace             workspace = mock(WorkerWorkspace.class);
    WorkerClient                client    = mock(WorkerClient.class);
    AtomicReference<WorkerWorkspace> first  = new AtomicReference<>();
    AtomicReference<WorkerWorkspace> second = new AtomicReference<>();
    CountDownLatch                  start   = new CountDownLatch(1);
    when(client.getWorkspace(ATTACHMENT_PREFIX))
        .thenReturn(workspace);
    Runnable getWorkspace = () -> {
      try {
        assertTrue(start.await(1, TimeUnit.SECONDS));
        WorkerWorkspace result = context.getWorkerWorkspace(client);
        if (!first.compareAndSet(null, result)) {
          second.set(result);
        }
      } catch (InterruptedException error) {
        throw new AssertionError(error);
      }
    };
    Thread firstRequest = Thread.ofVirtual().start(getWorkspace);
    Thread secondRequest = Thread.ofVirtual().start(getWorkspace);

    start.countDown();
    firstRequest.join(TimeUnit.SECONDS.toMillis(1));
    secondRequest.join(TimeUnit.SECONDS.toMillis(1));

    assertSame(workspace, first.get());
    assertSame(workspace, second.get());
    verify(client).getWorkspace(ATTACHMENT_PREFIX);
    context.close();
  }

  private static WebsocketConnectionContext context() {
    return new WebsocketConnectionContext(
        ATTACHMENT_PREFIX,
        Instant.MAX,
        false);
  }

  private static WorkerClient client(WorkerWorkspace workspace) {
    WorkerClient client = mock(WorkerClient.class);
    when(client.getWorkspace(ATTACHMENT_PREFIX)).thenReturn(workspace);
    return client;
  }
}
