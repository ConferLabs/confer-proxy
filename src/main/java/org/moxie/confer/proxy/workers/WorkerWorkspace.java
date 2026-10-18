package org.moxie.confer.proxy.workers;

import org.moxie.confer.proxy.attachments.AttachmentPublisher;
import org.moxie.confer.proxy.attachments.AttachmentReference;
import org.moxie.confer.proxy.documents.DecryptedDocument;
import org.moxie.confer.proxy.documents.worker.DocumentWorkerScheduler;
import org.moxie.confer.proxy.documents.worker.DocumentWorkerSchedulers;
import org.moxie.confer.proxy.lifecycle.ManagedResource;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class WorkerWorkspace implements ManagedResource {

  private final WorkerClient.Session     session;
  private final AttachmentPublisher      attachments;
  private final DocumentWorkerSchedulers documentSchedulers;
  private final String                   attachmentPrefix;
  private final Map<String, String>      boundPaths;

  private final Object documentWorkersLock = new Object();

  private WorkerConnection        connection;
  private DocumentWorkerScheduler documentWorkers;
  private volatile boolean        closed;

  WorkerWorkspace(WorkerClient.Session     session,
                  AttachmentPublisher      attachments,
                  DocumentWorkerSchedulers documentSchedulers,
                  String                   attachmentPrefix)
  {
    this.session            = session;
    this.attachments        = attachments;
    this.documentSchedulers = documentSchedulers;
    this.attachmentPrefix   = attachmentPrefix;
    this.boundPaths         = new HashMap<>();
  }

  /**
   * Document worker processes for this connection. Every document process the
   * connection starts goes through this scheduler, so its limit holds across
   * extractions and document tools.
   */
  public DocumentWorkerScheduler getDocumentWorkers() {
    synchronized (documentWorkersLock) {
      if (documentWorkers == null) {
        documentWorkers = documentSchedulers.forWorkspace(this);
      }
      return documentWorkers;
    }
  }

  /**
   * Starts a process on this connection's worker. Unlike commands, processes
   * run concurrently with each other and with workspace operations, so this
   * does not take the workspace lock that a running command holds.
   */
  public WorkerProcess startProcess(String command)
      throws WorkerException
  {
    if (closed) {
      throw new WorkerException("Worker workspace is closed");
    }
    return session.getConnection().startProcess(command);
  }

  public synchronized WorkerCommandResult execute(String            command,
                                                  List<Input>       inputs,
                                                  WorkerInputSource inputSource)
      throws WorkerException
  {
    WorkerConnection worker = getConnection();

    for (Input input : inputs) {
      String path = WorkerConnection.normalizeWorkspacePath(input.path());

      if (!boundPaths.containsKey(path) || !Objects.equals(boundPaths.get(path), input.attachmentId())) {
        stage(worker, input.attachmentId(), path, inputSource);
      }
    }

    return worker.execute(command);
  }

  public synchronized AttachmentReference publishFile(String path)
      throws WorkerException
  {
    try (WorkerConnection.OpenFile file = getConnection().openFile(path, AttachmentPublisher.MAX_BYTES)) {
      return attachments.publish(
          attachmentPrefix,
          file.getFilename(),
          file.getInputStream(),
          getDocumentWorkers());
    } catch (IOException error) {
      throw new WorkerException("Worker file publishing is temporarily unavailable", error);
    }
  }

  @Override
  public void close() {
    closed = true;
    session.close();
  }

  private void stage(WorkerConnection  worker,
                     String            attachmentId,
                     String            path,
                     WorkerInputSource inputSource)
      throws WorkerException
  {
    try (DecryptedDocument document = inputSource.open(attachmentId)) {
      worker.writeFile(path, document.content(), document.length());
      boundPaths.put(path, attachmentId);
    } catch (IOException error) {
      throw new WorkerException("An attachment could not be copied into the worker workspace", error);
    }
  }

  private WorkerConnection getConnection()
      throws WorkerException
  {
    if (closed) {
      throw new WorkerException("Worker workspace is closed");
    }

    WorkerConnection current = session.getConnection();

    if (current != connection) {
      boundPaths.clear();
      connection = current;
    }

    return current;
  }

  public record Input(String attachmentId, String path) {}
}
