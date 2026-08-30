package org.moxie.confer.proxy.workers;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.moxie.confer.proxy.attachments.AttachmentPublisher;
import org.moxie.confer.proxy.documents.DecryptedDocument;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class WorkerWorkspaceTest {

  @Test
  void executesCommandsWithoutInputs() throws Exception {
    WorkerConnection connection = mock(WorkerConnection.class);
    WorkerInputSource source = mock(WorkerInputSource.class);
    WorkerCommandResult expected = new WorkerCommandResult(0, "done\n", false);
    when(connection.execute("printf done")).thenReturn(expected);

    WorkerCommandResult result = workspace(connection, source).execute(
        "printf done",
        List.of());

    assertSame(expected, result);
    verify(connection).execute("printf done");
    verifyNoInteractions(source);
  }

  @Test
  void stagesEveryInputSequentiallyBeforeExecutingTheCommand() throws Exception {
    WorkerConnection connection = mock(WorkerConnection.class);
    WorkerInputSource source = mock(WorkerInputSource.class);
    DecryptedDocument first = content(new byte[]{1, 2, 3});
    DecryptedDocument second = content(new byte[]{4, 5});
    when(source.open("first")).thenReturn(first);
    when(source.open("second")).thenReturn(second);
    when(connection.execute("combine inputs/first.docx inputs/second.pdf"))
        .thenReturn(new WorkerCommandResult(0, "", false));
    List<byte[]> uploaded = new ArrayList<>();
    doAnswer(invocation -> {
      InputStream input = invocation.getArgument(1);
      uploaded.add(input.readAllBytes());
      return null;
    }).when(connection).writeFile(anyString(), any(InputStream.class), anyLong());

    workspace(connection, source).execute(
        "combine inputs/first.docx inputs/second.pdf",
        List.of(
            new WorkerWorkspace.Input("first", "inputs/first.docx"),
            new WorkerWorkspace.Input("second", "inputs/second.pdf")));

    assertEquals(2, uploaded.size());
    assertArrayEquals(new byte[]{1, 2, 3}, uploaded.get(0));
    assertArrayEquals(new byte[]{4, 5}, uploaded.get(1));
    InOrder order = inOrder(source, connection, first, second);
    order.verify(source).open("first");
    order.verify(connection).writeFile(
        eq("inputs/first.docx"),
        any(InputStream.class),
        eq(3L));
    order.verify(first).close();
    order.verify(source).open("second");
    order.verify(connection).writeFile(
        eq("inputs/second.pdf"),
        any(InputStream.class),
        eq(2L));
    order.verify(second).close();
    order.verify(connection).execute(
        "combine inputs/first.docx inputs/second.pdf");
  }

  @Test
  void restagesAPathWhenItsAttachmentChanges() throws Exception {
    WorkerConnection connection = mock(WorkerConnection.class);
    WorkerInputSource source = mock(WorkerInputSource.class);
    DecryptedDocument existing = content(new byte[]{1});
    DecryptedDocument replacement = content(new byte[]{2, 3});
    when(source.open("existing")).thenReturn(existing);
    when(source.open("replacement")).thenReturn(replacement);
    TestWorkspace workspace = workspace(connection, source);
    workspace.execute(
        "first command",
        List.of(new WorkerWorkspace.Input("existing", "inputs/existing.docx")));

    workspace.execute(
        "second command",
        List.of(new WorkerWorkspace.Input(
            "replacement",
            "inputs/existing.docx")));

    verify(source).open("replacement");
    verify(connection).writeFile(
        eq("inputs/existing.docx"),
        any(InputStream.class),
        eq(2L));
    verify(connection).execute("second command");
  }

  @Test
  void treatsEquivalentPathsAsTheSameBinding() throws Exception {
    WorkerConnection connection = mock(WorkerConnection.class);
    WorkerInputSource source = mock(WorkerInputSource.class);
    DecryptedDocument firstCopy = content(new byte[]{1});
    DecryptedDocument secondCopy = content(new byte[]{1});
    DecryptedDocument replacement = content(new byte[]{2});
    when(source.open("first"))
        .thenReturn(firstCopy, secondCopy);
    when(source.open("second")).thenReturn(replacement);
    TestWorkspace workspace = workspace(connection, source);

    workspace.execute(
        "first",
        List.of(new WorkerWorkspace.Input("first", "report.docx")));
    workspace.execute(
        "second",
        List.of(new WorkerWorkspace.Input("second", "./report.docx")));
    workspace.execute(
        "third",
        List.of(new WorkerWorkspace.Input("first", "report.docx")));

    verify(source, times(2)).open("first");
    verify(source).open("second");
    verify(connection, times(3)).writeFile(
        eq("report.docx"),
        any(InputStream.class),
        eq(1L));
  }

  @Test
  void repeatedBindingsAreIdempotentWithinAndAcrossCommands() throws Exception {
    WorkerConnection connection = mock(WorkerConnection.class);
    WorkerInputSource source = mock(WorkerInputSource.class);
    DecryptedDocument content = content(new byte[]{1});
    when(source.open("source")).thenReturn(content);
    WorkerWorkspace.Input binding = new WorkerWorkspace.Input("source", "inputs/source.xlsx");
    TestWorkspace workspace = workspace(connection, source);

    workspace.execute("first", List.of(binding, binding));
    workspace.execute("second", List.of(binding));

    verify(source, times(1)).open("source");
    verify(connection, times(1)).writeFile(
        eq("inputs/source.xlsx"),
        any(InputStream.class),
        eq(1L));
    verify(connection).execute("first");
    verify(connection).execute("second");
  }

  @Test
  void restagesBindingsAfterTheWorkerIsReplaced() throws Exception {
    WorkerConnection first = mock(WorkerConnection.class);
    WorkerConnection replacement = mock(WorkerConnection.class);
    WorkerClient.Session session = mock(WorkerClient.Session.class);
    WorkerInputSource source = mock(WorkerInputSource.class);
    DecryptedDocument firstCopy = content(new byte[]{1});
    DecryptedDocument replacementCopy = content(new byte[]{1});
    when(session.getConnection()).thenReturn(first, replacement);
    when(source.open("source")).thenReturn(firstCopy, replacementCopy);
    WorkerWorkspace workspace = new WorkerWorkspace(
        session,
        mock(AttachmentPublisher.class),
        "generated-attachments");
    WorkerWorkspace.Input binding = new WorkerWorkspace.Input(
        "source",
        "inputs/source.pdf");

    workspace.execute("first", List.of(binding), source);
    workspace.execute("second", List.of(binding), source);

    verify(source, times(2)).open("source");
    verify(first).writeFile(
        eq("inputs/source.pdf"),
        any(InputStream.class),
        eq(1L));
    verify(replacement).writeFile(
        eq("inputs/source.pdf"),
        any(InputStream.class),
        eq(1L));
    verify(first).execute("first");
    verify(replacement).execute("second");
  }

  @Test
  void neverReplaysAFailedCommandOnAReplacementWorker() throws Exception {
    WorkerConnection first = mock(WorkerConnection.class);
    WorkerConnection replacement = mock(WorkerConnection.class);
    WorkerClient.Session session = mock(WorkerClient.Session.class);
    WorkerInputSource source = mock(WorkerInputSource.class);
    WorkerException disconnected = new WorkerException("connection lost");
    WorkerCommandResult completed = new WorkerCommandResult(0, "done\n", false);
    when(session.getConnection()).thenReturn(first, replacement);
    when(first.execute("transform")).thenThrow(disconnected);
    when(replacement.execute("continue")).thenReturn(completed);
    WorkerWorkspace workspace = new WorkerWorkspace(
        session,
        mock(AttachmentPublisher.class),
        "generated-attachments");

    assertSame(disconnected, assertThrows(
        WorkerException.class,
        () -> workspace.execute("transform", List.of(), source)));
    assertSame(completed, workspace.execute("continue", List.of(), source));

    verify(first, times(1)).execute("transform");
    verify(replacement, never()).execute("transform");
    verify(replacement, times(1)).execute("continue");
  }

  @Test
  void oneAttachmentCanBeBoundToMultiplePaths() throws Exception {
    WorkerConnection connection = mock(WorkerConnection.class);
    WorkerInputSource source = mock(WorkerInputSource.class);
    DecryptedDocument firstCopy = content(new byte[]{1});
    DecryptedDocument secondCopy = content(new byte[]{1});
    when(source.open("source"))
        .thenReturn(firstCopy, secondCopy);

    workspace(connection, source).execute(
        "compare inputs/one.xlsx copies/two.xlsx",
        List.of(
            new WorkerWorkspace.Input("source", "inputs/one.xlsx"),
            new WorkerWorkspace.Input("source", "copies/two.xlsx")));

    verify(source, times(2)).open("source");
    verify(connection).writeFile(
        eq("inputs/one.xlsx"),
        any(InputStream.class),
        eq(1L));
    verify(connection).writeFile(
        eq("copies/two.xlsx"),
        any(InputStream.class),
        eq(1L));
  }

  @Test
  void stagesChangedBindingsInOrder() throws Exception {
    WorkerConnection connection = mock(WorkerConnection.class);
    WorkerInputSource source = mock(WorkerInputSource.class);
    DecryptedDocument first = content(new byte[]{1});
    DecryptedDocument second = content(new byte[]{2});
    when(source.open("first")).thenReturn(first);
    when(source.open("second")).thenReturn(second);

    workspace(connection, source).execute(
        "use input.docx",
        List.of(
            new WorkerWorkspace.Input("first", "input.docx"),
            new WorkerWorkspace.Input("second", "input.docx")));

    InOrder order = inOrder(source, connection);
    order.verify(source).open("first");
    order.verify(connection).writeFile(
        eq("input.docx"),
        any(InputStream.class),
        eq(1L));
    order.verify(source).open("second");
    order.verify(connection).writeFile(
        eq("input.docx"),
        any(InputStream.class),
        eq(1L));
    order.verify(connection).execute("use input.docx");
  }

  @Test
  void remembersCompletedTransfersWhenALaterTransferFails() throws Exception {
    WorkerConnection connection = mock(WorkerConnection.class);
    WorkerInputSource source = mock(WorkerInputSource.class);
    DecryptedDocument firstContent = content(new byte[]{1});
    DecryptedDocument failedSecondContent = content(new byte[]{2});
    DecryptedDocument retriedSecondContent = content(new byte[]{2});
    when(source.open("first"))
        .thenReturn(firstContent);
    when(source.open("second"))
        .thenReturn(failedSecondContent, retriedSecondContent);
    doThrow(new WorkerException("temporary write failure"))
        .doNothing()
        .when(connection)
        .writeFile(eq("inputs/second.docx"), any(InputStream.class), eq(1L));
    TestWorkspace workspace = workspace(connection, source);
    List<WorkerWorkspace.Input> inputs = List.of(
        new WorkerWorkspace.Input("first", "inputs/first.docx"),
        new WorkerWorkspace.Input("second", "inputs/second.docx"));

    assertThrows(
        WorkerException.class,
        () -> workspace.execute("transform", inputs));
    workspace.execute("transform", inputs);

    verify(source, times(1)).open("first");
    verify(connection, times(1)).writeFile(
        eq("inputs/first.docx"),
        any(InputStream.class),
        eq(1L));
    verify(source, times(2)).open("second");
    verify(connection, times(2)).writeFile(
        eq("inputs/second.docx"),
        any(InputStream.class),
        eq(1L));
    verify(connection, times(1)).execute("transform");
  }

  @Test
  void returnsAGenericFailureWhenOpeningFails()
      throws Exception
  {
    WorkerConnection connection = mock(WorkerConnection.class);
    WorkerInputSource source = mock(WorkerInputSource.class);
    when(source.open("source")).thenThrow(new IOException("storage secret"));
    TestWorkspace workspace = workspace(connection, source);

    WorkerException error = assertThrows(
        WorkerException.class,
        () -> workspace.execute(
            "should not run",
            List.of(new WorkerWorkspace.Input("source", "source.docx"))));

    assertEquals(
        "An attachment could not be copied into the worker workspace",
        error.getMessage());
    assertFalse(error.getMessage().contains("storage secret"));
    verify(connection, never()).writeFile(anyString(), any(InputStream.class), anyLong());
    verify(connection, never()).execute(anyString());
  }

  @Test
  void closesInputContentWhenWritingFails() throws Exception {
    WorkerConnection connection = mock(WorkerConnection.class);
    WorkerInputSource source = mock(WorkerInputSource.class);
    DecryptedDocument content = content(new byte[]{1});
    when(source.open("source")).thenReturn(content);
    doThrow(new WorkerException("write failed"))
        .when(connection)
        .writeFile(eq("source.docx"), any(InputStream.class), eq(1L));

    assertThrows(
        WorkerException.class,
        () -> workspace(connection, source).execute(
            "should not run",
            List.of(new WorkerWorkspace.Input("source", "source.docx"))));

    verify(content).close();
    verify(connection, never()).execute(anyString());
  }

  @Test
  void remembersACompletedTransferWhenClosingItsSourceFails() throws Exception {
    WorkerConnection connection = mock(WorkerConnection.class);
    WorkerInputSource source = mock(WorkerInputSource.class);
    DecryptedDocument content = content(new byte[]{1});
    when(source.open("source")).thenReturn(content);
    doThrow(new IOException("storage close secret")).when(content).close();
    TestWorkspace workspace = workspace(connection, source);
    WorkerWorkspace.Input binding = new WorkerWorkspace.Input("source", "source.docx");

    WorkerException failure = assertThrows(
        WorkerException.class,
        () -> workspace.execute("first", List.of(binding)));
    workspace.execute("second", List.of(binding));

    assertFalse(failure.getMessage().contains("storage close secret"));
    verify(source, times(1)).open("source");
    verify(connection, times(1)).writeFile(
        eq("source.docx"),
        any(InputStream.class),
        eq(1L));
    verify(connection, never()).execute("first");
    verify(connection).execute("second");
  }

  @Test
  void concurrentOperationsAreSerialized() throws Exception {
    WorkerConnection connection = mock(WorkerConnection.class);
    WorkerInputSource source = mock(WorkerInputSource.class);
    CountDownLatch firstStarted = new CountDownLatch(1);
    CountDownLatch releaseFirst = new CountDownLatch(1);
    CountDownLatch secondStarted = new CountDownLatch(1);
    CountDownLatch secondReachedConnection = new CountDownLatch(1);
    AtomicInteger calls = new AtomicInteger();
    doAnswer(invocation -> {
      if (calls.getAndIncrement() == 0) {
        firstStarted.countDown();
        assertTrue(releaseFirst.await(1, TimeUnit.SECONDS));
      } else {
        secondReachedConnection.countDown();
      }
      return new WorkerCommandResult(0, "", false);
    }).when(connection).execute(anyString());
    TestWorkspace workspace = workspace(connection, source);
    AtomicReference<Throwable> failure = new AtomicReference<>();
    Thread first = Thread.ofPlatform().start(() -> {
      try {
        workspace.execute("first", List.of());
      } catch (Throwable error) {
        failure.compareAndSet(null, error);
      }
    });
    assertTrue(firstStarted.await(1, TimeUnit.SECONDS));
    Thread second = Thread.ofPlatform().start(() -> execute(
        workspace,
        "second",
        secondStarted,
        failure));
    assertTrue(secondStarted.await(1, TimeUnit.SECONDS));
    awaitBlocked(second);

    try {
      assertEquals(1, secondReachedConnection.getCount());
    } finally {
      releaseFirst.countDown();
    }
    first.join(TimeUnit.SECONDS.toMillis(1));
    second.join(TimeUnit.SECONDS.toMillis(1));

    assertFalse(first.isAlive());
    assertFalse(second.isAlive());
    assertNull(failure.get());
    verify(connection).execute("first");
    verify(connection).execute("second");
  }

  @Test
  void closeCancelsActiveAndQueuedOperations() throws Exception {
    WorkerConnection connection = mock(WorkerConnection.class);
    WorkerClient.Session session = mock(WorkerClient.Session.class);
    WorkerInputSource source = mock(WorkerInputSource.class);
    WorkerWorkspace workspace = new WorkerWorkspace(
        session,
        mock(AttachmentPublisher.class),
        "generated-attachments");
    CountDownLatch active = new CountDownLatch(1);
    CountDownLatch closed = new CountDownLatch(1);
    CountDownLatch queued = new CountDownLatch(1);
    AtomicReference<Throwable> activeFailure = new AtomicReference<>();
    AtomicReference<Throwable> queuedFailure = new AtomicReference<>();
    when(session.getConnection()).thenReturn(connection);
    doAnswer(invocation -> {
      active.countDown();
      assertTrue(closed.await(1, TimeUnit.SECONDS));
      throw new WorkerException("connection closed");
    }).when(connection).execute("active");
    doAnswer(invocation -> {
      closed.countDown();
      return null;
    }).when(session).close();
    Thread running = Thread.ofPlatform().start(() -> {
      try {
        workspace.execute("active", List.of(), source);
      } catch (Throwable error) {
        activeFailure.set(error);
      }
    });
    assertTrue(active.await(1, TimeUnit.SECONDS));
    Thread waiting = Thread.ofPlatform().start(() -> {
      queued.countDown();
      try {
        workspace.execute("queued", List.of(), source);
      } catch (Throwable error) {
        queuedFailure.set(error);
      }
    });
    assertTrue(queued.await(1, TimeUnit.SECONDS));
    awaitBlocked(waiting);

    Thread closing = Thread.ofVirtual().start(workspace::close);
    closing.join(TimeUnit.SECONDS.toMillis(1));
    running.join(TimeUnit.SECONDS.toMillis(1));
    waiting.join(TimeUnit.SECONDS.toMillis(1));

    assertFalse(closing.isAlive());
    assertFalse(running.isAlive());
    assertFalse(waiting.isAlive());
    assertTrue(activeFailure.get() instanceof WorkerException);
    assertTrue(queuedFailure.get() instanceof WorkerException);
    assertEquals("Worker workspace is closed", queuedFailure.get().getMessage());
    verify(connection).execute("active");
    verify(connection, never()).execute("queued");
    verify(session).close();
  }

  private static void execute(TestWorkspace              workspace,
                              String                     command,
                              CountDownLatch             started,
                              AtomicReference<Throwable> failure)
  {
    started.countDown();
    try {
      workspace.execute(command, List.of());
    } catch (Throwable error) {
      failure.compareAndSet(null, error);
    }
  }

  private static void awaitBlocked(Thread thread) {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
    while (thread.getState() != Thread.State.BLOCKED
        && System.nanoTime() < deadline) {
      Thread.onSpinWait();
    }
    assertEquals(Thread.State.BLOCKED, thread.getState());
  }

  private static TestWorkspace workspace(WorkerConnection connection,
                                         WorkerInputSource source)
      throws WorkerException
  {
    WorkerClient.Session session = mock(WorkerClient.Session.class);
    when(session.getConnection()).thenReturn(connection);
    WorkerWorkspace workspace = new WorkerWorkspace(
        session,
        mock(AttachmentPublisher.class),
        "generated-attachments");
    return new TestWorkspace(workspace, source);
  }

  private record TestWorkspace(WorkerWorkspace  workspace,
                               WorkerInputSource source)
  {
    private WorkerCommandResult execute(String                      command,
                                        List<WorkerWorkspace.Input> inputs)
        throws WorkerException
    {
      return workspace.execute(command, inputs, source);
    }

  }

  private static DecryptedDocument content(byte[] bytes) throws Exception {
    DecryptedDocument content = mock(DecryptedDocument.class);
    when(content.content()).thenReturn(new ByteArrayInputStream(bytes));
    when(content.length()).thenReturn((long) bytes.length);
    return content;
  }

}
