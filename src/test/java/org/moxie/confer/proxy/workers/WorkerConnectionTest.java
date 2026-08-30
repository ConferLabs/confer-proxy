package org.moxie.confer.proxy.workers;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.SftpATTRS;
import com.jcraft.jsch.SftpException;
import jakarta.websocket.EndpointConfig;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.moxie.confer.proxy.attachments.AttachmentPublisher;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkerConnectionTest {

  private static final String WORKSPACE = "/var/lib/confer/workspace";
  private static final String FIXED_COMMAND =
      "cd '" + WORKSPACE + "' && "
      + "exec /bin/bash --noprofile --norc -s";

  @Test
  void sendsTheCommandOnlyThroughStandardInput() throws Exception {
    Fixture fixture = fixture();
    ChannelExec execution = execution(0, "complete\n");
    when(fixture.session().openChannel("exec")).thenReturn(execution);

    WorkerCommandResult result = fixture.connection().execute(
        "python3 generate.py\n");

    ArgumentCaptor<String> fixedCommand = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<InputStream> input = ArgumentCaptor.forClass(InputStream.class);
    ArgumentCaptor<OutputStream> standardOutput =
        ArgumentCaptor.forClass(OutputStream.class);
    ArgumentCaptor<OutputStream> standardError =
        ArgumentCaptor.forClass(OutputStream.class);
    verify(execution).setCommand(fixedCommand.capture());
    verify(execution).setInputStream(input.capture());
    verify(execution).setOutputStream(standardOutput.capture(), eq(true));
    verify(execution).setErrStream(standardError.capture());

    assertEquals(FIXED_COMMAND, fixedCommand.getValue());
    assertFalse(fixedCommand.getValue().contains("python3 generate.py"));
    assertEquals("python3 generate.py\n",
                 new String(input.getValue().readAllBytes(),
                            StandardCharsets.UTF_8));
    assertSame(standardOutput.getValue(), standardError.getValue());
    assertEquals(new WorkerCommandResult(0, "complete\n", false), result);
    verify(execution).connect(10_000);
    verify(execution).disconnect();
    verify(execution, never()).isClosed();
  }

  @Test
  void waitsForTheExecutionChannelToClose() throws Exception {
    Fixture fixture = fixture();
    ChannelExec execution = mock(ChannelExec.class);
    CountDownLatch connected = new CountDownLatch(1);
    AtomicReference<OutputStream> destination = new AtomicReference<>();
    AtomicReference<WorkerCommandResult> result = new AtomicReference<>();
    doAnswer(invocation -> {
      destination.set(invocation.getArgument(0));
      return null;
    }).when(execution).setOutputStream(any(OutputStream.class), eq(true));
    doAnswer(invocation -> {
      connected.countDown();
      return null;
    }).when(execution).connect(anyInt());
    when(execution.getExitStatus()).thenReturn(0);
    when(fixture.session().openChannel("exec")).thenReturn(execution);

    Thread executing = Thread.ofVirtual().start(() -> {
      try {
        result.set(fixture.connection().execute("printf complete"));
      } catch (WorkerException error) {
        throw new AssertionError(error);
      }
    });
    assertTrue(connected.await(1, TimeUnit.SECONDS));
    assertTrue(executing.isAlive());

    destination.get().close();
    executing.join(TimeUnit.SECONDS.toMillis(1));

    assertFalse(executing.isAlive());
    assertEquals(new WorkerCommandResult(0, "", false), result.get());
  }

  @Test
  void closeUnblocksARunningCommand() throws Exception {
    Fixture fixture = fixture();
    ChannelExec execution = mock(ChannelExec.class);
    CountDownLatch connected = new CountDownLatch(1);
    AtomicReference<OutputStream> destination = new AtomicReference<>();
    AtomicReference<Throwable> failure = new AtomicReference<>();
    doAnswer(invocation -> {
      destination.set(invocation.getArgument(0));
      return null;
    }).when(execution).setOutputStream(any(OutputStream.class), eq(true));
    doAnswer(invocation -> {
      connected.countDown();
      return null;
    }).when(execution).connect(anyInt());
    doAnswer(invocation -> {
      destination.get().close();
      return null;
    }).when(fixture.session()).disconnect();
    when(fixture.session().openChannel("exec")).thenReturn(execution);
    Thread executing = Thread.ofVirtual().start(() -> {
      try {
        fixture.connection().execute("while true; do :; done");
      } catch (Throwable error) {
        failure.set(error);
      }
    });
    assertTrue(connected.await(1, TimeUnit.SECONDS));

    Thread closing = Thread.ofVirtual().start(fixture.connection()::close);
    closing.join(TimeUnit.SECONDS.toMillis(1));
    executing.join(TimeUnit.SECONDS.toMillis(1));

    assertFalse(closing.isAlive());
    assertFalse(executing.isAlive());
    assertTrue(failure.get() instanceof WorkerException);
    assertEquals(
        "Worker connection closed while running a command",
        failure.get().getMessage());
    verify(execution).disconnect();
    verify(fixture.session()).disconnect();
  }

  @Test
  void returnsNonzeroExitsAndContinuesWithLaterCommands() throws Exception {
    Fixture fixture = fixture();
    ChannelExec failed = execution(23, "first failed\n");
    ChannelExec succeeded = execution(0, "recovered\n");
    when(fixture.session().openChannel("exec"))
        .thenReturn(failed, succeeded);

    WorkerCommandResult first = fixture.connection().execute("exit 23");
    WorkerCommandResult second = fixture.connection().execute("printf recovered");

    assertEquals(new WorkerCommandResult(23, "first failed\n", false), first);
    assertEquals(new WorkerCommandResult(0, "recovered\n", false), second);
    verify(fixture.session(), times(2)).openChannel("exec");
    verify(failed).disconnect();
    verify(succeeded).disconnect();
  }

  @Test
  void preservesBoundedHeadAndTailForTwoHundredKilobytes() throws Exception {
    Fixture fixture = fixture();
    String output = "BEGIN_SENTINEL\n"
        + "x".repeat(200_000)
        + "\nEND_SENTINEL\n";
    ChannelExec execution = execution(0, output);
    when(fixture.session().openChannel("exec")).thenReturn(execution);

    WorkerCommandResult result = fixture.connection().execute("generate noisy output");

    assertTrue(result.truncated());
    assertTrue(result.output().startsWith("BEGIN_SENTINEL\n"));
    assertTrue(result.output().endsWith("\nEND_SENTINEL\n"));
    verify(fixture.session(), never()).disconnect();
    assertFalse(fixture.connection().isClosed());
  }

  @Test
  void retiresTheWorkerWhenCommandOutputExceedsTheTransportLimit()
      throws Exception
  {
    Fixture fixture = fixture();
    String output = "x".repeat(8 * 1024 * 1024 + 1);
    ChannelExec execution = execution(0, output);
    when(fixture.session().openChannel("exec")).thenReturn(execution);

    WorkerException failure = assertThrows(
        WorkerException.class,
        () -> fixture.connection().execute("generate unbounded output"));

    assertEquals("Worker command output exceeded its limit", failure.getMessage());
    verify(fixture.session()).disconnect();
    assertTrue(fixture.connection().isClosed());
    assertThrows(
        WorkerException.class,
        () -> fixture.connection().execute("printf cannot-reuse"));
  }

  @Test
  void rejectsInvalidCommandsBeforeOpeningAnSshChannel() throws Exception {
    Fixture fixture = fixture();

    assertThrows(WorkerException.class,
                 () -> fixture.connection().execute(null));
    assertThrows(WorkerException.class,
                 () -> fixture.connection().execute(" "));
    assertThrows(
        WorkerException.class,
        () -> fixture.connection().execute("x".repeat(256 * 1024 + 1)));

    verify(fixture.session(), never()).openChannel(any(String.class));
  }

  @Test
  void retiresTheWorkerWhenOpeningAnExecutionChannelFails() throws Exception {
    Fixture fixture = fixture();
    when(fixture.session().openChannel("exec"))
        .thenThrow(new JSchException("session is down"));

    assertThrows(
        WorkerException.class,
        () -> fixture.connection().execute("printf unreachable"));

    verify(fixture.session()).disconnect();
    assertTrue(fixture.connection().isClosed());
  }

  @Test
  void opensAValidNestedWorkspaceFile() throws Exception {
    Fixture fixture = fixture();
    String path = "reports/2026/result.pdf";
    String absolutePath = WORKSPACE + "/" + path;
    byte[] contents = "generated document".getBytes(StandardCharsets.UTF_8);
    SftpATTRS attributes = regularFile(contents.length);
    when(fixture.sftp().stat(absolutePath)).thenReturn(attributes);
    when(fixture.sftp().get(absolutePath))
        .thenReturn(new ByteArrayInputStream(contents));

    try (WorkerConnection.OpenFile file = fixture.connection().openFile(
        path,
        AttachmentPublisher.MAX_BYTES))
    {
      assertEquals("result.pdf", file.getFilename());
      assertArrayEquals(contents, file.getInputStream().readAllBytes());
    }

    verify(fixture.sftp()).stat(absolutePath);
    verify(fixture.sftp()).get(absolutePath);
  }

  @Test
  void rejectsAnOversizedWorkspaceFileBeforeOpeningIt() throws Exception {
    Fixture fixture = fixture();
    String path = "report.pdf";
    String absolutePath = WORKSPACE + "/" + path;
    SftpATTRS attributes = regularFile(AttachmentPublisher.MAX_BYTES + 1);
    when(fixture.sftp().stat(absolutePath)).thenReturn(attributes);

    assertThrows(
        WorkerException.class,
        () -> fixture.connection().openFile(
            path,
            AttachmentPublisher.MAX_BYTES));

    verify(fixture.sftp()).stat(absolutePath);
    verify(fixture.sftp(), never()).get(any(String.class));
  }

  @Test
  void atomicallyStreamsAFileIntoANestedWorkspacePath() throws Exception {
    Fixture fixture = fixture();
    byte[] content = "decrypted attachment".repeat(8_192)
                                            .getBytes(StandardCharsets.UTF_8);
    ByteArrayOutputStream uploaded = prepareUpload(
        fixture,
        "inputs/source.docx");
    fixture.connection().writeFile(
        "inputs/source.docx",
        new ByteArrayInputStream(content),
        content.length);

    assertArrayEquals(content, uploaded.toByteArray());
    ArgumentCaptor<String> temporaryPath = ArgumentCaptor.forClass(String.class);
    verify(fixture.sftp()).put(
        temporaryPath.capture(),
        eq(ChannelSftp.OVERWRITE));
    assertTrue(temporaryPath.getValue().startsWith(
        WORKSPACE + "/inputs/.confer-upload-"));
    assertTrue(temporaryPath.getValue().endsWith(".tmp"));
    verify(fixture.sftp()).rename(
        temporaryPath.getValue(),
        WORKSPACE + "/inputs/source.docx");

    InOrder order = inOrder(fixture.sftp());
    order.verify(fixture.sftp()).connect(10_000);
    order.verify(fixture.sftp()).disconnect();
  }

  @Test
  void createsMissingParentDirectories() throws Exception {
    Fixture fixture = fixture();
    byte[] content = {1, 2, 3};
    Set<String> directories = new HashSet<>();
    String first = WORKSPACE + "/imports";
    String second = first + "/originals";
    String destination = second + "/source.pdf";
    ByteArrayOutputStream uploaded = new ByteArrayOutputStream();
    SftpATTRS directory = directory();
    when(fixture.sftp().stat(any(String.class))).thenAnswer(invocation -> {
      String path = invocation.getArgument(0);
      if (directories.contains(path)) {
        return directory;
      }
      throw missing();
    });
    doAnswer(invocation -> {
      directories.add(invocation.getArgument(0));
      return null;
    }).when(fixture.sftp()).mkdir(any(String.class));
    when(fixture.sftp().put(any(String.class), eq(ChannelSftp.OVERWRITE)))
        .thenReturn(uploaded);

    fixture.connection().writeFile(
        "imports/originals/source.pdf",
        new ByteArrayInputStream(content),
        content.length);

    assertArrayEquals(content, uploaded.toByteArray());
    InOrder order = inOrder(fixture.sftp());
    order.verify(fixture.sftp()).mkdir(first);
    order.verify(fixture.sftp()).mkdir(second);
    verify(fixture.sftp()).rename(any(String.class), eq(destination));
  }

  @Test
  void rejectsInvalidUploadArgumentsBeforeOpeningAnSftpChannel() throws Exception {
    Fixture fixture = fixture();
    byte[] content = {1};
    List<String> invalidPaths = List.of(
        "",
        " ",
        "/",
        "/var/lib/confer/workspace/source.pdf",
        "../source.pdf",
        "inputs/../../source.pdf",
        "inputs/\0source.pdf");

    assertThrows(
        WorkerException.class,
        () -> fixture.connection().writeFile(null, new ByteArrayInputStream(content), 1));
    for (String path : invalidPaths) {
      assertThrows(
          WorkerException.class,
          () -> fixture.connection().writeFile(
              path,
              new ByteArrayInputStream(content),
              1));
    }
    assertThrows(
        WorkerException.class,
        () -> fixture.connection().writeFile(
            "source.pdf",
            new ByteArrayInputStream(content),
            -1));
    verify(fixture.session(), never()).openChannel(any(String.class));
  }

  @Test
  void removesPartialUploadsWhenTheSourceLengthChanges() throws Exception {
    Fixture shortSource = fixture();
    prepareUpload(shortSource, "source.pdf");

    WorkerException shortFailure = assertThrows(
        WorkerException.class,
        () -> shortSource.connection().writeFile(
            "source.pdf",
            new ByteArrayInputStream(new byte[] {1}),
            2));

    assertEquals(
        "Worker input size changed while uploading it",
        shortFailure.getMessage());
    verify(shortSource.sftp()).rm(any(String.class));
    verify(shortSource.sftp(), never()).rename(any(String.class), any(String.class));
    verify(shortSource.session(), never()).disconnect();

    Fixture longSource = fixture();
    prepareUpload(longSource, "source.pdf");

    WorkerException longFailure = assertThrows(
        WorkerException.class,
        () -> longSource.connection().writeFile(
            "source.pdf",
            new ByteArrayInputStream(new byte[] {1, 2}),
            1));

    assertEquals(
        "Worker input size changed while uploading it",
        longFailure.getMessage());
    verify(longSource.sftp()).rm(any(String.class));
    verify(longSource.sftp(), never()).rename(any(String.class), any(String.class));
    verify(longSource.session(), never()).disconnect();
  }

  @Test
  void closesTheConnectionWhenTheAtomicCommitResultIsAmbiguous() throws Exception {
    Fixture fixture = fixture();
    prepareUpload(fixture, "source.pdf");
    doThrow(new SftpException(ChannelSftp.SSH_FX_FAILURE, "rename failed"))
        .when(fixture.sftp())
        .rename(any(String.class), any(String.class));

    WorkerException failure = assertThrows(
        WorkerException.class,
        () -> fixture.connection().writeFile(
            "source.pdf",
            new ByteArrayInputStream(new byte[] {1}),
            1));

    assertEquals("Unable to write the worker file", failure.getMessage());
    verify(fixture.session()).disconnect();
    assertTrue(fixture.connection().isClosed());
  }

  @Test
  void rejectsInvalidWorkspacePathsBeforeOpeningAnSftpChannel()
      throws Exception
  {
    Fixture fixture = fixture();
    List<String> invalidPaths = List.of(
        "",
        " ",
        "/",
        "/var/lib/confer/workspace/report.pdf",
        "../report.pdf",
        "reports/../../report.pdf",
        "reports/\0report.pdf");

    assertThrows(WorkerException.class,
                 () -> fixture.connection().openFile(
                     null,
                     AttachmentPublisher.MAX_BYTES));
    for (String path : invalidPaths) {
      assertThrows(WorkerException.class,
                   () -> fixture.connection().openFile(
                       path,
                       AttachmentPublisher.MAX_BYTES));
    }

    verify(fixture.session(), never()).openChannel("sftp");
  }

  @Test
  void rejectsNonRegularFiles() throws Exception {
    Fixture directory = fileFixture(
        "directory",
        file(false, 10),
        new byte[10]);

    WorkerException directoryFailure = assertThrows(
        WorkerException.class,
        () -> directory.connection().openFile(
            "directory",
            AttachmentPublisher.MAX_BYTES));

    assertEquals("Worker file is not a regular file",
                 directoryFailure.getMessage());
    verify(directory.sftp(), never()).get(any(String.class));
  }

  @Test
  void opensEmptyFiles() throws Exception {
    Fixture empty = fileFixture("empty.pdf", regularFile(0), new byte[0]);

    try (WorkerConnection.OpenFile file = empty.connection().openFile(
        "empty.pdf",
        AttachmentPublisher.MAX_BYTES))
    {
      assertArrayEquals(new byte[0], file.getInputStream().readAllBytes());
    }
  }

  @Test
  void releasesTheSftpChannelAfterTheFileCloses() throws Exception {
    Fixture fixture = fileFixture(
        "report.pdf",
        regularFile(1),
        new byte[] {1});

    WorkerConnection.OpenFile file = fixture.connection().openFile(
        "report.pdf",
        AttachmentPublisher.MAX_BYTES);
    file.close();
    file.close();

    InOrder order = inOrder(fixture.sftp());
    order.verify(fixture.sftp()).connect(10_000);
    order.verify(fixture.sftp()).disconnect();
  }

  @Test
  void releasesTheFileOperationWhenClosingItsInputFails() throws Exception {
    Fixture fixture = fixture();
    String absolutePath = WORKSPACE + "/report.pdf";
    SftpATTRS attributes = regularFile(1);
    when(fixture.sftp().stat(absolutePath)).thenReturn(attributes);
    InputStream input = mock(InputStream.class);
    doThrow(new IOException("close failed")).when(input).close();
    when(fixture.sftp().get(absolutePath)).thenReturn(input);

    WorkerConnection.OpenFile file = fixture.connection().openFile(
        "report.pdf",
        AttachmentPublisher.MAX_BYTES);
    IOException error = assertThrows(IOException.class, file::close);

    assertEquals("close failed", error.getMessage());
    verify(fixture.sftp()).disconnect();

    ChannelExec execution = execution(0, "ready\n");
    when(fixture.session().openChannel("exec")).thenReturn(execution);
    assertEquals(0, fixture.connection().execute("printf ready").exitCode());
  }

  @Test
  void retiresTheWorkerWhenOpeningTheSftpChannelFails()
      throws Exception
  {
    Fixture fixture = fixture();
    when(fixture.session().openChannel("sftp"))
        .thenThrow(new JSchException("open failed"));

    assertThrows(WorkerException.class,
                 () -> fixture.connection().openFile(
                     "report.pdf",
                     AttachmentPublisher.MAX_BYTES));

    verify(fixture.session()).disconnect();
    assertTrue(fixture.connection().isClosed());
  }

  @Test
  void retiresTheWorkerWhenConnectingTheSftpChannelFails()
      throws Exception
  {
    Fixture fixture = fixture();
    doThrow(new JSchException("connect failed"))
        .when(fixture.sftp()).connect(10_000);

    assertThrows(WorkerException.class,
                 () -> fixture.connection().openFile(
                     "report.pdf",
                     AttachmentPublisher.MAX_BYTES));

    verify(fixture.sftp()).disconnect();
    verify(fixture.session()).disconnect();
    assertTrue(fixture.connection().isClosed());
  }

  @Test
  void closeOwnsTheSshSessionAndTransportExactlyOnce() throws Exception {
    Fixture fixture = fixture();

    fixture.connection().close();
    fixture.connection().close();

    verify(fixture.session()).disconnect();
    assertTrue(fixture.connection().isClosed());
    assertThrows(WorkerException.class,
                 () -> fixture.connection().execute("printf closed"));
    assertThrows(WorkerException.class,
                 () -> fixture.connection().openFile(
                     "report.pdf",
                     AttachmentPublisher.MAX_BYTES));
    assertThrows(
        WorkerException.class,
        () -> fixture.connection().writeFile(
            "source.pdf",
            new ByteArrayInputStream(new byte[] {1}),
            1));
  }

  @Test
  void isOpenRequiresBothTheWebsocketAndSshSession() throws Exception {
    Fixture fixture = fixture();
    jakarta.websocket.Session webSocket = mock(jakarta.websocket.Session.class);
    when(webSocket.isOpen()).thenReturn(true);

    fixture.connection().onOpen(webSocket, mock(EndpointConfig.class));
    assertTrue(fixture.connection().isOpen());

    when(fixture.session().isConnected()).thenReturn(false);
    assertFalse(fixture.connection().isOpen());

    when(fixture.session().isConnected()).thenReturn(true);
    when(webSocket.isOpen()).thenReturn(false);
    assertFalse(fixture.connection().isOpen());
  }

  private static Fixture fileFixture(String    path,
                                     SftpATTRS attributes,
                                     byte[]    contents)
      throws Exception
  {
    Fixture fixture = fixture();
    String absolutePath = WORKSPACE + "/" + path;
    when(fixture.sftp().stat(absolutePath)).thenReturn(attributes);
    when(fixture.sftp().get(absolutePath))
        .thenReturn(new ByteArrayInputStream(contents));
    return fixture;
  }

  private static ByteArrayOutputStream prepareUpload(Fixture fixture,
                                                     String  path)
      throws Exception
  {
    String absolutePath = WORKSPACE + "/" + path;
    int separator = absolutePath.lastIndexOf('/');
    String parent = absolutePath.substring(0, separator);
    ByteArrayOutputStream uploaded = new ByteArrayOutputStream();
    SftpATTRS directory = directory();
    when(fixture.sftp().stat(any(String.class))).thenAnswer(invocation -> {
      String candidate = invocation.getArgument(0);
      if (candidate.equals(parent)) {
        return directory;
      }
      throw missing();
    });
    when(fixture.sftp().put(any(String.class), eq(ChannelSftp.OVERWRITE)))
        .thenReturn(uploaded);
    return uploaded;
  }

  private static Fixture fixture() throws Exception {
    Session session = mock(Session.class);
    ChannelSftp sftp = mock(ChannelSftp.class);
    when(session.isConnected()).thenReturn(true);
    when(session.openChannel("sftp")).thenReturn(sftp);
    return new Fixture(
        new WorkerConnection(
            new ObjectMapper(),
            mock(WorkerAttestationHandshake.class),
            session),
        session,
        sftp);
  }

  private static ChannelExec execution(int    exitCode,
                                       String output)
      throws Exception
  {
    ChannelExec execution = mock(ChannelExec.class);
    AtomicReference<OutputStream> destination = new AtomicReference<>();
    doAnswer(invocation -> {
      destination.set(invocation.getArgument(0));
      return null;
    }).when(execution).setOutputStream(any(OutputStream.class), eq(true));
    doAnswer(invocation -> {
      try {
        destination.get().write(output.getBytes(StandardCharsets.UTF_8));
      } catch (IOException ignored) {
      } finally {
        destination.get().close();
      }
      return null;
    }).when(execution).connect(anyInt());
    when(execution.getExitStatus()).thenReturn(exitCode);
    return execution;
  }

  private static SftpATTRS regularFile(long size) {
    return file(true, size);
  }

  private static SftpATTRS directory() {
    SftpATTRS attributes = file(false, 0);
    when(attributes.isDir()).thenReturn(true);
    return attributes;
  }

  private static SftpException missing() {
    return new SftpException(ChannelSftp.SSH_FX_NO_SUCH_FILE, "missing");
  }

  private static SftpATTRS file(boolean regular,
                                long    size)
  {
    SftpATTRS attributes = mock(SftpATTRS.class);
    when(attributes.isReg()).thenReturn(regular);
    when(attributes.getSize()).thenReturn(size);
    return attributes;
  }

  private record Fixture(WorkerConnection connection,
                         Session          session,
                         ChannelSftp      sftp) {}
}
