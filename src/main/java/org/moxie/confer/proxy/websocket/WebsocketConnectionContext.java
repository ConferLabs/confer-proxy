package org.moxie.confer.proxy.websocket;

import org.moxie.confer.proxy.streaming.StreamRegistry;
import org.moxie.confer.proxy.workers.WorkerClient;
import org.moxie.confer.proxy.workers.WorkerWorkspace;

import java.time.Instant;

public class WebsocketConnectionContext {

  public static final String SESSION_PROPERTY = WebsocketConnectionContext.class.getName();

  private final String         attachmentPrefix;
  private final Instant        expiresAt;
  private final boolean        subscribed;
  private final StreamRegistry streams;

  private WorkerWorkspace workerWorkspace;
  private boolean         closed;

  public WebsocketConnectionContext(String  attachmentPrefix,
                                    Instant expiresAt,
                                    boolean subscribed)
  {
    this.attachmentPrefix = attachmentPrefix;
    this.expiresAt        = expiresAt;
    this.subscribed       = subscribed;
    this.streams          = new StreamRegistry();
  }

  public StreamRegistry getStreams() {
    return streams;
  }

  public boolean requiresPayment(Instant now) {
    return !subscribed && !now.isBefore(expiresAt);
  }

  public synchronized WorkerWorkspace getWorkerWorkspace(WorkerClient client) {
    if (closed) {
      throw new IllegalStateException("WebSocket connection is closed");
    }

    if (workerWorkspace != null) {
      return workerWorkspace;
    }

    workerWorkspace = client.getWorkspace(attachmentPrefix);

    return workerWorkspace;
  }

  public void close() {
    WorkerWorkspace closing;
    synchronized (this) {
      if (closed) {
        return;
      }
      closed          = true;
      closing         = workerWorkspace;
      workerWorkspace = null;
    }
    streams.close();
    if (closing != null) {
      closing.close();
    }
  }
}
