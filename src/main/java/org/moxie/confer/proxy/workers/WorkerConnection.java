package org.moxie.confer.proxy.workers;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.SftpATTRS;
import com.jcraft.jsch.SftpException;
import jakarta.websocket.WebSocketContainer;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.UUID;

class WorkerConnection extends WorkerWebsocketConnection {

  private static final Path   WORKSPACE = Path.of("/var/lib/confer/workspace");
  private static final String COMMAND   = "cd '" + WORKSPACE + "' && " + "exec /bin/bash --noprofile --norc -s";

  private static final int      CHANNEL_CONNECT_TIMEOUT_MILLIS = 10_000;
  private static final int      MAX_COMMAND_BYTES              = 256 * 1024;
  private static final int      MAX_OUTPUT_BYTES               =  16 * 1024;
  private static final long     MAX_COMMAND_OUTPUT_BYTES       = 8L  * 1024 * 1024;

  private Session session;

  WorkerConnection(ObjectMapper               mapper,
                   WorkerAttestationHandshake attestation)
  {
    super(mapper, attestation);
  }

  WorkerConnection(ObjectMapper               mapper,
                   WorkerAttestationHandshake attestation,
                   Session                    session)
  {
    this(mapper, attestation);
    this.session = session;
  }

  void connect(WebSocketContainer webSockets,
               URI                controllerUri,
               String             bearerToken)
      throws WorkerException
  {
    try {
      WorkerSshSessionFactory ssh     = WorkerSshSessionFactory.create();
      byte[]                  hostKey = super.connect(webSockets, controllerUri, bearerToken, ssh.getPublicKey());

      Session connected = ssh.connect(this, hostKey);

      synchronized (this) {
        if (isClosed()) {
          connected.disconnect();
          throw new WorkerException("Worker connection attempt was cancelled");
        }

        session = connected;
      }
    } catch (IOException | JSchException error) {
      close();
      throw new WorkerException("Unable to establish an attested worker connection", error);
    }
  }

  public WorkerCommandResult execute(String command)
      throws WorkerException
  {
    byte[] encodedCommand = encodeCommand(command);
    return executeCommand(encodedCommand);
  }

  public OpenFile openFile(String path,
                           long   maximumBytes)
      throws WorkerException
  {
    Path workerPath = getWorkspacePath(path);
    if (maximumBytes < 0) {
      throw new WorkerException("Worker file size limit is invalid");
    }
    return openFile(workerPath, maximumBytes);
  }

  public void writeFile(String      path,
                        InputStream content,
                        long        length)
      throws WorkerException
  {
    Path workerPath = getWorkspacePath(path);
    if (length < 0) {
      throw new WorkerException("Worker input size is invalid");
    }
    writeFile(workerPath, content, length);
  }

  @Override
  public void close() {
    super.close();

    Session closing;
    synchronized (this) {
      closing = session;
      session = null;
    }
    if (closing != null) {
      closing.disconnect();
    }
  }

  synchronized boolean isOpen() {
    return isWebSocketOpen() && session != null && session.isConnected();
  }

  private WorkerCommandResult executeCommand(byte[] command)
      throws WorkerException
  {
    ChannelExec          channel = openExecutionChannel();
    HeadTailOutputStream output  = new HeadTailOutputStream(MAX_OUTPUT_BYTES, MAX_COMMAND_OUTPUT_BYTES);

    try {
      channel.setCommand(COMMAND);
      channel.setInputStream(new ByteArrayInputStream(command));
      channel.setOutputStream(output, true);
      channel.setErrStream(output);
      channel.connect(CHANNEL_CONNECT_TIMEOUT_MILLIS);
      output.awaitClose();

      if (output.isLimitExceeded()) {
        close();
        throw new WorkerException("Worker command output exceeded its limit");
      }

      requireOpen("Worker connection closed while running a command");
      int exitStatus = channel.getExitStatus();

      if (exitStatus == -1) {
        throw new WorkerException("Worker command ended without an exit status");
      }

      return new WorkerCommandResult(exitStatus, output.getOutput(), output.isTruncated());
    } catch (JSchException error) {
      close();
      throw new WorkerException("Unable to run a worker command", error);
    } finally {
      channel.disconnect();
    }
  }

  private OpenFile openFile(Path path,
                            long maximumBytes)
      throws WorkerException
  {
    ChannelSftp channel = openSftpChannel();

    try {
      String    absolutePath = path.toString();
      SftpATTRS attributes   = channel.stat(absolutePath);

      if (!attributes.isReg()) {
        throw new WorkerException("Worker file is not a regular file");
      }

      if (attributes.getSize() < 0 || attributes.getSize() > maximumBytes) {
        throw new WorkerException("Worker file size is invalid");
      }

      InputStream input = channel.get(absolutePath);

      return new OpenFile(input, path.getFileName().toString(), channel::disconnect);
    } catch (SftpException error) {
      channel.disconnect();
      if (error.id == ChannelSftp.SSH_FX_NO_SUCH_FILE) {
        throw new WorkerException("Worker file does not exist", error);
      }
      throw new WorkerException("Unable to open the worker file", error);
    } catch (WorkerException error) {
      channel.disconnect();
      throw error;
    }
  }

  private void writeFile(Path        path,
                         InputStream content,
                         long        length)
      throws WorkerException
  {
    ChannelSftp channel       = openSftpChannel();
    String      destination   = path.toString();
    String      temporaryPath = path.resolveSibling(".confer-upload-" + UUID.randomUUID() + ".tmp").toString();

    try {
      prepareParentDirectories(channel, path);

      try (OutputStream output = channel.put(temporaryPath, ChannelSftp.OVERWRITE)) {
        transferExactly(content, output, length);
      }

      try {
        removeFileIfPresent(channel, destination);
        channel.rename(temporaryPath, destination);
      } catch (SftpException error) {
        close();
        throw error;
      }
    } catch (SftpException error) {
      throw new WorkerException("Unable to write the worker file", error);
    } catch (IOException error) {
      throw new WorkerException("Unable to transfer the worker file", error);
    } finally {
      removeTemporaryFile(channel, temporaryPath);
      channel.disconnect();
    }
  }

  private ChannelExec openExecutionChannel() throws WorkerException {
    Session connected = requireOpen("Worker connection is closed");

    try {
      return (ChannelExec) connected.openChannel("exec");
    } catch (JSchException error) {
      close();
      throw new WorkerException("Unable to open a worker execution channel", error);
    }
  }

  private ChannelSftp openSftpChannel() throws WorkerException {
    Session connected = requireOpen("Worker connection is closed");

    try {
      ChannelSftp sftp = (ChannelSftp) connected.openChannel("sftp");

      try {
        sftp.connect(CHANNEL_CONNECT_TIMEOUT_MILLIS);
        return sftp;
      } catch (JSchException error) {
        sftp.disconnect();
        throw error;
      }
    } catch (JSchException error) {
      close();
      throw new WorkerException(
          "Unable to open a worker file transfer",
          error);
    }
  }

  private static void prepareParentDirectories(ChannelSftp channel,
                                               Path        path)
      throws SftpException, WorkerException
  {
    Path directory = WORKSPACE;
    for (Path element : WORKSPACE.relativize(path.getParent())) {
      directory = directory.resolve(element);
      createDirectoryIfMissing(channel, directory.toString());
    }
  }

  private static void createDirectoryIfMissing(ChannelSftp channel,
                                               String      path)
      throws SftpException, WorkerException
  {
    try {
      if (!channel.stat(path).isDir()) {
        throw new WorkerException("Worker file parent is not a directory");
      }
    } catch (SftpException error) {
      if (error.id != ChannelSftp.SSH_FX_NO_SUCH_FILE) {
        throw error;
      }
      channel.mkdir(path);
    }
  }

  private static void transferExactly(InputStream  input,
                                      OutputStream output,
                                      long         length)
      throws IOException, WorkerException
  {
    byte[] buffer = new byte[64 * 1024];
    long remaining = length;
    while (remaining > 0) {
      int count = input.read(
          buffer,
          0,
          (int) Math.min(buffer.length, remaining));
      if (count < 0) {
        throw new WorkerException(
            "Worker input size changed while uploading it");
      }
      output.write(buffer, 0, count);
      remaining -= count;
    }
    if (input.read() != -1) {
      throw new WorkerException(
          "Worker input size changed while uploading it");
    }
  }

  private static void removeFileIfPresent(ChannelSftp channel,
                                          String      path)
      throws SftpException
  {
    try {
      channel.rm(path);
    } catch (SftpException error) {
      if (error.id != ChannelSftp.SSH_FX_NO_SUCH_FILE) {
        throw error;
      }
    }
  }

  private void removeTemporaryFile(ChannelSftp channel,
                                   String      path)
  {
    try {
      channel.rm(path);
    } catch (SftpException ignored) {
    }
  }

  private synchronized Session requireOpen(String message)
      throws WorkerException
  {
    if (isClosed() || session == null || !session.isConnected()) {
      throw new WorkerException(message);
    }
    return session;
  }

  static class OpenFile implements AutoCloseable {

    private final InputStream input;
    private final String      filename;
    private final Runnable    release;

    private boolean closed;

    OpenFile(InputStream input,
             String      filename,
             Runnable    release)
    {
      this.input    = input;
      this.filename = filename;
      this.release  = release;
    }

    InputStream getInputStream() {
      return input;
    }

    String getFilename() {
      return filename;
    }

    @Override
    public synchronized void close() throws IOException {
      if (closed) {
        return;
      }
      closed = true;
      try {
        input.close();
      } finally {
        release.run();
      }
    }
  }

  private static byte[] encodeCommand(String command)
      throws WorkerException
  {
    if (command == null || command.isBlank()) {
      throw new WorkerException("Worker command is required");
    }

    byte[] encoded = command.getBytes(StandardCharsets.UTF_8);

    if (encoded.length > MAX_COMMAND_BYTES) {
      throw new WorkerException("Worker command is too large");
    }

    return encoded;
  }

  private static Path getWorkspacePath(String value)
      throws WorkerException
  {
    return WORKSPACE.resolve(normalizeWorkspacePath(value));
  }

  static String normalizeWorkspacePath(String value)
      throws WorkerException
  {
    if (value == null || value.isBlank()) {
      throw new WorkerException("Worker file path is invalid");
    }

    try {
      Path path = Path.of(value);

      if (path.isAbsolute()) {
        throw new WorkerException("Worker file path is invalid");
      }

      Path normalized = path.normalize();

      if (normalized.toString().isEmpty() || normalized.startsWith("..")) {
        throw new WorkerException("Worker file path is invalid");
      }

      return normalized.toString();
    } catch (InvalidPathException error) {
      throw new WorkerException("Worker file path is invalid", error);
    }
  }
}
