package org.moxie.confer.proxy.workers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.websocket.ClientEndpointConfig;
import jakarta.websocket.WebSocketContainer;
import org.glassfish.tyrus.client.ClientProperties;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import java.util.Objects;

class WorkerWebsocketConnection extends WebsocketConnection {

  private static final int WEBSOCKET_CONNECT_TIMEOUT_MILLIS  = 15_000;
  private static final int WORKER_ALLOCATION_TIMEOUT_MILLIS = 15_000;
  private static final int ATTESTATION_TIMEOUT_MILLIS       = 35_000;

  private static final int MAX_MESSAGE_BYTES   = 256 * 1024;
  private static final int MAX_QUEUED_BYTES    = 16 * 1024 * 1024;
  private static final int WRITE_MESSAGE_BYTES = 32 * 1024;

  private final ObjectMapper               mapper;
  private final WorkerAttestationHandshake attestation;

  private final Input  inputStream  = new Input();
  private final Output outputStream = new Output();

  private volatile int readTimeoutMillis;

  WorkerWebsocketConnection(ObjectMapper               mapper,
                            WorkerAttestationHandshake attestation)
  {
    super(MAX_MESSAGE_BYTES, MAX_QUEUED_BYTES);
    this.mapper      = mapper;
    this.attestation = attestation;
  }

  byte[] connect(WebSocketContainer container,
                 URI                controllerUri,
                 String             bearerToken,
                 byte[]             clientKey)
      throws IOException, WorkerException
  {
    try {
      super.connect(container, getEndpointConfig(bearerToken), controllerUri);

      requireReady(readMessage(WORKER_ALLOCATION_TIMEOUT_MILLIS));

      WorkerAttestationHandshake.Attempt attempt = attestation.begin(clientKey);
      writeMessage(ByteBuffer.wrap(attempt.getRequest()));

      ByteBuffer response = readMessage(ATTESTATION_TIMEOUT_MILLIS);

      if (response == null) {
        throw new IOException("Worker attestation response is missing");
      }

      return attempt.verify(getBytes(response));
    } catch (IOException | WorkerException | RuntimeException error) {
      close();
      throw error;
    }
  }

  InputStream getInputStream() {
    return inputStream;
  }

  OutputStream getOutputStream() {
    return outputStream;
  }

  void setReadTimeout(int timeoutMillis) {
    if (timeoutMillis < 0) {
      throw new IllegalArgumentException("WebSocket read timeout is invalid");
    }
    readTimeoutMillis = timeoutMillis;
  }

  private void requireReady(ByteBuffer message) throws IOException {
    if (message == null) {
      throw new IOException("Worker controller did not allocate a worker");
    }
    JsonNode response = mapper.readTree(getBytes(message));
    if (response == null
        || response.size() != 1
        || !"ready".equals(response.path("type").textValue())) {
      throw new IOException("Worker controller did not allocate a worker");
    }
  }

  private static byte[] getBytes(ByteBuffer message) {
    byte[] bytes = new byte[message.remaining()];
    message.get(bytes);
    return bytes;
  }

  private static ClientEndpointConfig getEndpointConfig(String bearerToken) {
    ClientEndpointConfig config = ClientEndpointConfig.Builder.create()
        .configurator(new ClientEndpointConfig.Configurator() {
          @Override
          public void beforeRequest(Map<String, List<String>> headers) {
            headers.put("Authorization", List.of("Bearer " + bearerToken));
          }
        })
        .build();

    config.getUserProperties().put(
        ClientProperties.HANDSHAKE_TIMEOUT,
        WEBSOCKET_CONNECT_TIMEOUT_MILLIS);

    return config;
  }

  private class Input extends InputStream {

    private ByteBuffer current;

    @Override
    public int read() throws IOException {
      if (!ensureAvailable()) {
        return -1;
      }
      return current.get() & 0xff;
    }

    @Override
    public int read(byte[] destination,
                    int    destinationOffset,
                    int    length)
        throws IOException
    {
      Objects.checkFromIndexSize(
          destinationOffset,
          length,
          destination.length);
      if (length == 0) {
        return 0;
      }
      if (!ensureAvailable()) {
        return -1;
      }
      int copied = Math.min(length, current.remaining());
      current.get(destination, destinationOffset, copied);
      return copied;
    }

    @Override
    public void close() {
      WorkerWebsocketConnection.this.close();
    }

    private boolean ensureAvailable() throws IOException {
      while (current == null || !current.hasRemaining()) {
        current = readMessage(readTimeoutMillis);
        if (current == null) {
          return false;
        }
      }
      return true;
    }
  }

  private class Output extends OutputStream {

    @Override
    public void write(int value) throws IOException {
      write(new byte[] {(byte) value});
    }

    @Override
    public synchronized void write(byte[] source,
                                   int    sourceOffset,
                                   int    length)
        throws IOException
    {
      Objects.checkFromIndexSize(sourceOffset, length, source.length);

      int remaining = length;
      int offset = sourceOffset;
      while (remaining > 0) {
        int count = Math.min(remaining, WRITE_MESSAGE_BYTES);
        writeMessage(ByteBuffer.wrap(source, offset, count));
        remaining -= count;
        offset += count;
      }
    }

    @Override
    public void close() {
      WorkerWebsocketConnection.this.close();
    }
  }
}
