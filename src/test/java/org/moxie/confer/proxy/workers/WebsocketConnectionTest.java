package org.moxie.confer.proxy.workers;

import jakarta.websocket.ClientEndpointConfig;
import jakarta.websocket.CloseReason;
import jakarta.websocket.Endpoint;
import jakarta.websocket.MessageHandler;
import jakarta.websocket.RemoteEndpoint;
import jakarta.websocket.Session;
import jakarta.websocket.WebSocketContainer;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.same;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WebsocketConnectionTest {

  private static final URI CONTROLLER_URI = URI.create(
      "wss://controller.test/v1/worker");
  private static final int MAXIMUM_MESSAGE_BYTES = 2048;
  private static final int MAXIMUM_QUEUED_BYTES  = 8192;

  @Test
  void connectsAndConfiguresCompleteMessageHandlers() throws Exception {
    Fixture fixture = new Fixture();

    fixture.connect();

    verify(fixture.container).connectToServer(
        same(fixture.connection),
        eq(fixture.config),
        eq(CONTROLLER_URI));
    verify(fixture.session).setMaxBinaryMessageBufferSize(
        MAXIMUM_MESSAGE_BYTES);
    fixture.binaryHandler();
  }

  @Test
  void reportsWhetherTheWebsocketIsOpen() throws Exception {
    Fixture fixture = new Fixture();
    assertFalse(fixture.connection.isOpen());

    fixture.connect();
    assertTrue(fixture.connection.isOpen());

    when(fixture.session.isOpen()).thenReturn(false);
    assertFalse(fixture.connection.isOpen());
  }

  @Test
  void queuesCompleteBinaryMessagesInArrivalOrder() throws Exception {
    Fixture fixture = Fixture.connected();
    byte[] first = new byte[] {1, 2, 3};
    byte[] second = new byte[] {4, 5, 6};

    fixture.receiveBinary(first);
    fixture.receiveBinary(second);

    assertArrayEquals(first, toByteArray(fixture.connection.read()));
    assertArrayEquals(second, toByteArray(fixture.connection.read()));
  }

  @Test
  void readTimeoutDoesNotCloseTheConnection() throws Exception {
    Fixture fixture = Fixture.connected();

    assertThrows(IOException.class, () -> fixture.connection.read(1));

    byte[] available = new byte[] {1};
    fixture.receiveBinary(available);
    assertArrayEquals(available, toByteArray(fixture.connection.read()));
  }

  @Test
  void drainsQueuedMessagesBeforeReportingNormalClose() throws Exception {
    Fixture fixture = Fixture.connected();
    byte[] finalMessage = new byte[] {1};
    fixture.receiveBinary(finalMessage);

    fixture.connection.onClose(
        fixture.session,
        new CloseReason(
            CloseReason.CloseCodes.NORMAL_CLOSURE,
            "complete"));

    assertArrayEquals(finalMessage, toByteArray(fixture.connection.read()));
    assertNull(fixture.connection.read());
  }

  @Test
  void abnormalCloseFailsImmediatelyWithoutDrainingMessages()
      throws Exception
  {
    Fixture fixture = Fixture.connected();
    fixture.receiveBinary(new byte[] {1});

    fixture.connection.onClose(
        fixture.session,
        new CloseReason(
            CloseReason.CloseCodes.UNEXPECTED_CONDITION,
            "failed"));

    assertThrows(IOException.class, fixture.connection::read);
  }

  @Test
  void queuesMoreThan64SmallMessagesWithinTheByteLimit() throws Exception {
    Fixture fixture = Fixture.connected();
    for (int index = 0; index < 128; index++) {
      fixture.receiveBinary(new byte[] {(byte) index});
    }

    for (int index = 0; index < 128; index++) {
      assertArrayEquals(
          new byte[] {(byte) index},
          toByteArray(fixture.connection.read()));
    }
  }

  @Test
  void acceptsTheExactInputQueueByteLimit() throws Exception {
    Fixture fixture = Fixture.connected();

    fixture.fillQueue();

    fixture.drainQueue();
  }

  @Test
  void failsImmediatelyWhenTheInputQueueByteLimitIsExceeded()
      throws Exception
  {
    Fixture fixture = Fixture.connected();

    fixture.fillQueue();
    fixture.receiveBinary(new byte[1]);

    assertThrows(IOException.class, fixture.connection::read);
  }

  @Test
  void readingReleasesInputQueueCapacity() throws Exception {
    Fixture fixture = Fixture.connected();
    byte[] message = new byte[MAXIMUM_MESSAGE_BYTES];

    fixture.fillQueue();
    assertArrayEquals(message, toByteArray(fixture.connection.read()));

    fixture.receiveBinary(message);
    fixture.drainQueue();
  }

  @Test
  void emptyMessagesDoNotConsumeInputQueueCapacity() throws Exception {
    Fixture fixture = Fixture.connected();
    for (int index = 0; index < 128; index++) {
      fixture.receiveBinary(new byte[0]);
    }

    fixture.fillQueue();

    fixture.drainQueue();
  }

  @Test
  void writesBinaryMessages() throws Exception {
    Fixture fixture = Fixture.connected();
    byte[] binary = new byte[] {1, 2, 3};

    fixture.connection.write(ByteBuffer.wrap(binary));

    ArgumentCaptor<ByteBuffer> sent = ArgumentCaptor.forClass(ByteBuffer.class);
    verify(fixture.remote).sendBinary(sent.capture());
    ByteBuffer data = sent.getValue();
    byte[] actual = new byte[data.remaining()];
    data.get(actual);
    assertArrayEquals(binary, actual);
  }

  @Test
  void writeFailuresFailTheConnection() throws Exception {
    Fixture fixture = Fixture.connected();
    IOException source = new IOException("write failed");
    doThrow(source).when(fixture.remote).sendBinary(any(ByteBuffer.class));

    assertSame(source, assertThrows(
        IOException.class,
        () -> fixture.connection.write(ByteBuffer.wrap(new byte[] {1}))));
    assertThrows(IOException.class, fixture.connection::getOpenSession);
  }

  @Test
  void blockedWritesDoNotPreventClosingTheConnection() throws Exception {
    Fixture fixture = Fixture.connected();
    CountDownLatch writing = new CountDownLatch(1);
    CountDownLatch releaseWrite = new CountDownLatch(1);
    doAnswer(ignored -> {
      writing.countDown();
      if (!releaseWrite.await(5, TimeUnit.SECONDS)) {
        throw new IOException("Timed out waiting to release WebSocket write");
      }
      return null;
    }).when(fixture.remote).sendBinary(any(ByteBuffer.class));
    AtomicReference<IOException> writeFailure = new AtomicReference<>();
    Thread writer = Thread.ofVirtual().start(() -> {
      try {
        fixture.connection.write(ByteBuffer.wrap(new byte[] {1}));
      } catch (IOException error) {
        writeFailure.set(error);
      }
    });
    assertTrue(writing.await(1, TimeUnit.SECONDS));

    Thread closing = Thread.ofVirtual().start(fixture.connection::close);
    try {
      closing.join(TimeUnit.SECONDS.toMillis(1));
      assertFalse(closing.isAlive());
      verify(fixture.session).close(any(CloseReason.class));
    } finally {
      releaseWrite.countDown();
      writer.join(TimeUnit.SECONDS.toMillis(1));
      closing.join(TimeUnit.SECONDS.toMillis(1));
    }

    assertFalse(writer.isAlive());
    assertNull(writeFailure.get());
  }

  @Test
  void closeWakesABlockedRead() throws Exception {
    Fixture fixture = Fixture.connected();
    CountDownLatch started = new CountDownLatch(1);
    AtomicReference<IOException> failure = new AtomicReference<>();
    Thread reader = Thread.ofVirtual().start(() -> {
      started.countDown();
      try {
        fixture.connection.read();
      } catch (IOException error) {
        failure.set(error);
      }
    });
    assertTrue(started.await(1, TimeUnit.SECONDS));

    fixture.connection.close();
    reader.join(TimeUnit.SECONDS.toMillis(1));

    assertTrue(failure.get() != null);
    verify(fixture.session).close(any(CloseReason.class));
  }

  @Test
  void closingBeforeOpenIsIrreversible() throws Exception {
    Fixture fixture = new Fixture();
    fixture.connection.close();

    fixture.connection.onOpen(fixture.session, fixture.config);

    verify(fixture.session).close(any(CloseReason.class));
    assertThrows(IOException.class, fixture.connection::getOpenSession);
  }

  @Test
  void errorsFailTheConnectionAndPreserveTheirCause() throws Exception {
    Fixture fixture = Fixture.connected();
    IOException source = new IOException("relay failed");

    fixture.connection.onError(fixture.session, source);

    IOException failure = assertThrows(
        IOException.class,
        fixture.connection::read);
    assertSame(source, failure.getCause());
  }

  private static class TestConnection extends WebsocketConnection {

    private TestConnection() {
      super(MAXIMUM_MESSAGE_BYTES, MAXIMUM_QUEUED_BYTES);
    }

    private void open(WebSocketContainer   container,
                      ClientEndpointConfig config)
        throws IOException
    {
      connect(container, config, CONTROLLER_URI);
    }

    private ByteBuffer read() throws IOException {
      return readMessage(0);
    }

    private ByteBuffer read(int timeoutMillis) throws IOException {
      return readMessage(timeoutMillis);
    }

    private void write(ByteBuffer message) throws IOException {
      writeMessage(message);
    }

    private boolean isOpen() {
      return isWebSocketOpen();
    }

  }

  private static byte[] toByteArray(ByteBuffer source) {
    byte[] data = new byte[source.remaining()];
    source.get(data);
    return data;
  }

  private static class Fixture {

    private final WebSocketContainer container = mock(WebSocketContainer.class);
    private final ClientEndpointConfig config = mock(ClientEndpointConfig.class);
    private final Session session = mock(Session.class);
    private final RemoteEndpoint.Basic remote = mock(RemoteEndpoint.Basic.class);
    private final TestConnection connection = new TestConnection();

    private Fixture() throws Exception {
      when(session.isOpen()).thenReturn(true);
      when(session.getBasicRemote()).thenReturn(remote);
      when(container.connectToServer(
          any(Endpoint.class),
          any(ClientEndpointConfig.class),
          eq(CONTROLLER_URI)))
          .thenAnswer(invocation -> {
            Endpoint endpoint = invocation.getArgument(0);
            endpoint.onOpen(session, config);
            return session;
          });
    }

    private static Fixture connected() throws Exception {
      Fixture fixture = new Fixture();
      fixture.connect();
      return fixture;
    }

    private void connect() throws IOException {
      connection.open(container, config);
    }

    private void receiveBinary(byte[] data) {
      binaryHandler().onMessage(ByteBuffer.wrap(data));
    }

    private void fillQueue() {
      for (int queued = 0;
           queued < MAXIMUM_QUEUED_BYTES;
           queued += MAXIMUM_MESSAGE_BYTES) {
        receiveBinary(new byte[MAXIMUM_MESSAGE_BYTES]);
      }
    }

    private void drainQueue() throws IOException {
      for (int queued = 0;
           queued < MAXIMUM_QUEUED_BYTES;
           queued += MAXIMUM_MESSAGE_BYTES) {
        assertArrayEquals(
            new byte[MAXIMUM_MESSAGE_BYTES],
            toByteArray(connection.read()));
      }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private MessageHandler.Whole<ByteBuffer> binaryHandler() {
      ArgumentCaptor<MessageHandler.Whole> handler =
          ArgumentCaptor.forClass(MessageHandler.Whole.class);
      verify(session).addMessageHandler(eq(ByteBuffer.class), handler.capture());
      return (MessageHandler.Whole<ByteBuffer>) handler.getValue();
    }
  }
}
