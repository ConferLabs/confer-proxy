package org.moxie.confer.proxy.workers;

import jakarta.websocket.ClientEndpointConfig;
import jakarta.websocket.CloseReason;
import jakarta.websocket.DeploymentException;
import jakarta.websocket.Endpoint;
import jakarta.websocket.EndpointConfig;
import jakarta.websocket.MessageHandler;
import jakarta.websocket.Session;
import jakarta.websocket.WebSocketContainer;
import org.moxie.confer.proxy.lifecycle.ManagedResource;

import java.io.IOException;
import java.net.URI;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.concurrent.TimeUnit;

abstract class WebsocketConnection extends Endpoint implements ManagedResource {

  private final int                    maximumMessageBytes;
  private final int                    maximumQueuedBytes;
  private final Object                 writeLock = new Object();
  private final ArrayDeque<ByteBuffer> messages  = new ArrayDeque<>();

  private volatile Session     session;
  private          IOException terminalFailure;
  private          int         queuedBytes;
  private          boolean     closed;

  WebsocketConnection(int maximumMessageBytes,
                      int maximumQueuedBytes)
  {
    this.maximumMessageBytes = maximumMessageBytes;
    this.maximumQueuedBytes  = maximumQueuedBytes;
  }

  protected final void connect(WebSocketContainer   container,
                               ClientEndpointConfig config,
                               URI                  uri)
      throws IOException
  {
    synchronized (this) {
      if (closed) {
        throw new IOException("WebSocket connection is closed");
      }
    }

    try {
      container.connectToServer(this, config, uri);
    } catch (IOException | DeploymentException e) {
      IOException failure = new IOException("Unable to establish WebSocket connection", e);
      fail(failure);
      throw failure;
    }
  }

  protected final ByteBuffer readMessage(int timeoutMillis) throws IOException {
    if (timeoutMillis < 0) {
      throw new IllegalArgumentException("WebSocket read timeout is invalid");
    }

    synchronized (this) {
      long deadline = getDeadline(timeoutMillis);

      while (messages.isEmpty() && terminalFailure == null && !closed) {
        try {
          if (timeoutMillis == 0) {
            wait();
          } else {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
              throw new SocketTimeoutException("WebSocket read timed out");
            }
            TimeUnit.NANOSECONDS.timedWait(this, remaining);
          }
        } catch (InterruptedException error) {
          throw new AssertionError(error);
        }
      }

      if (terminalFailure != null) {
        throw terminalFailure;
      }

      ByteBuffer message = messages.pollFirst();
      if (message != null) {
        queuedBytes -= message.remaining();
      }
      return message;
    }
  }

  protected final void writeMessage(ByteBuffer message)
      throws IOException
  {
    synchronized (writeLock) {
      Session connected = getOpenSession();

      try {
        connected.getBasicRemote().sendBinary(message.duplicate());
      } catch (IOException error) {
        fail(error);
        throw error;
      } catch (IllegalStateException error) {
        IOException failure = new IOException("Unable to write WebSocket message", error);
        fail(failure);
        throw failure;
      }
    }
  }

  @Override
  public void onOpen(Session opened, EndpointConfig ignoredConfig) {
    try {
      opened.setMaxBinaryMessageBufferSize(maximumMessageBytes);
      opened.addMessageHandler(ByteBuffer.class, (MessageHandler.Whole<ByteBuffer>) this::onBinary);
    } catch (IllegalStateException error) {
      fail(new IOException("Unable to open WebSocket connection", error));
      close(opened);
      return;
    }

    boolean closeOpened;

    synchronized (this) {
      closeOpened = closed;
      if (!closeOpened) {
        session = opened;
      }
    }

    if (closeOpened) {
      close(opened);
    }
  }

  @Override
  public void onClose(Session ignoredSession, CloseReason reason) {
    if (reason.getCloseCode() == CloseReason.CloseCodes.NORMAL_CLOSURE) {
      finish();
    } else {
      fail(new IOException("WebSocket closed unexpectedly with status " + reason.getCloseCode().getCode()));
    }
  }

  @Override
  public void onError(Session ignoredSession, Throwable error) {
    fail(new IOException("WebSocket connection failed", error));
  }

  @Override
  public void close() {
    if (terminate(new IOException("WebSocket connection closed"))) {
      close(session);
    }
  }

  private void onBinary(ByteBuffer data) {
    ByteBuffer copy = ByteBuffer.allocate(data.remaining());
    copy.put(data);
    copy.flip();
    receive(copy.asReadOnlyBuffer());
  }

  private void receive(ByteBuffer message) {
    synchronized (this) {
      if (closed || !message.hasRemaining()) {
        return;
      }

      if (message.remaining() <= maximumQueuedBytes - queuedBytes) {
        messages.addLast(message);
        queuedBytes += message.remaining();
        notifyAll();
        return;
      }

      terminalFailure = new IOException("WebSocket message queue exceeded its byte capacity");
      closed = true;
      messages.clear();
      queuedBytes = 0;
      notifyAll();
    }
    close(session);
  }

  protected final synchronized Session getOpenSession() throws IOException {
    if (terminalFailure != null) {
      throw terminalFailure;
    }

    Session connected = session;

    if (closed || connected == null || !connected.isOpen()) {
      throw new IOException("WebSocket connection is closed");
    }

    return connected;
  }

  protected final synchronized boolean isClosed() {
    return closed;
  }

  protected final synchronized boolean isWebSocketOpen() {
    Session connected = session;
    return !closed
        && terminalFailure == null
        && connected != null
        && connected.isOpen();
  }

  private void fail(IOException failure) {
    if (terminate(failure)) {
      close(session);
    }
  }

  private void finish() {
    synchronized (this) {
      if (closed) {
        return;
      }
      closed = true;
      notifyAll();
    }
  }

  private boolean terminate(IOException failure) {
    synchronized (this) {
      if (closed) {
        return false;
      }
      terminalFailure = failure;
      closed          = true;
      messages.clear();
      queuedBytes = 0;
      notifyAll();
    }
    return true;
  }

  private static long getDeadline(int timeoutMillis) {
    if (timeoutMillis == 0) {
      return 0;
    }

    return System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
  }

  private static void close(Session closing) {
    if (closing == null || !closing.isOpen()) {
      return;
    }
    try {
      closing.close(new CloseReason(CloseReason.CloseCodes.NORMAL_CLOSURE, "complete"));
    } catch (IOException | IllegalStateException ignored) {
    }
  }
}
