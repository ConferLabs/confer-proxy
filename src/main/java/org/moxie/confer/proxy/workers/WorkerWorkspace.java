package org.moxie.confer.proxy.workers;

import org.moxie.confer.proxy.attachments.AttachmentPublisher;
import org.moxie.confer.proxy.attachments.AttachmentReference;
import org.moxie.confer.proxy.documents.DecryptedDocument;
import org.moxie.confer.proxy.lifecycle.ManagedResource;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class WorkerWorkspace implements ManagedResource {

  private final WorkerClient.Session session;
  private final AttachmentPublisher  attachments;
  private final String               attachmentPrefix;
  private final Map<String, String>  boundPaths;

  private WorkerConnection connection;
  private volatile boolean closed;

  WorkerWorkspace(WorkerClient.Session session,
                  AttachmentPublisher  attachments,
                  String               attachmentPrefix)
  {
    this.session          = session;
    this.attachments      = attachments;
    this.attachmentPrefix = attachmentPrefix;
    this.boundPaths       = new HashMap<>();
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
      return attachments.publish(attachmentPrefix, file.getFilename(), file.getInputStream());
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
