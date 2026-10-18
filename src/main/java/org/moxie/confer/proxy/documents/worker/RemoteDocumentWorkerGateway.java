package org.moxie.confer.proxy.documents.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validator;
import org.moxie.confer.proxy.documents.DocumentStorageGateway;
import org.moxie.confer.proxy.workers.WorkerException;
import org.moxie.confer.proxy.workers.WorkerProcess;
import org.moxie.confer.proxy.workers.WorkerWorkspace;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Runs each document worker process on the connection's own confidential
 * worker, over a channel of its attested SSH connection.
 */
public class RemoteDocumentWorkerGateway implements DocumentWorkerGateway {

  /**
   * Concurrent document processes per worker connection. This is the only
   * limit on them; the worker image leaves headroom above it for processes
   * that are still exiting.
   */
  public static final int MAX_PROCESSES = 8;

  private static final String COMMAND = "/usr/local/libexec/confer-document-session";

  private final WorkerWorkspace        workspace;
  private final DocumentStorageGateway storage;
  private final ObjectMapper           mapper;
  private final Validator              validator;

  public RemoteDocumentWorkerGateway(WorkerWorkspace        workspace,
                                     DocumentStorageGateway storage,
                                     ObjectMapper           mapper,
                                     Validator              validator)
  {
    this.workspace = workspace;
    this.storage   = storage;
    this.mapper    = mapper;
    this.validator = validator;
  }

  @Override
  public DocumentWorkerConnection connect() throws IOException {
    WorkerProcess process;
    try {
      process = workspace.startProcess(COMMAND);
    } catch (WorkerException error) {
      throw new IOException("Unable to start a remote document worker", error);
    }

    return new DocumentWorkerConnection(
        new DocumentWorkerInputStream(mapper, new ProcessOutput(process)),
        new DocumentWorkerOutputStream(mapper, process.getOutputStream()),
        storage,
        mapper,
        validator);
  }

  private static final class ProcessOutput extends FilterInputStream {

    private final WorkerProcess process;

    private ProcessOutput(WorkerProcess process) {
      super(process.getInputStream());
      this.process = process;
    }

    @Override
    public void close() throws IOException {
      try {
        super.close();
      } finally {
        process.close();
      }
    }
  }
}
