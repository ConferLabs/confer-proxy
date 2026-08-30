package org.moxie.confer.proxy.workers;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.websocket.ClientEndpointConfig;
import jakarta.websocket.CloseReason;
import jakarta.websocket.Endpoint;
import jakarta.websocket.EndpointConfig;
import jakarta.websocket.MessageHandler;
import jakarta.websocket.RemoteEndpoint;
import jakarta.websocket.Session;
import jakarta.websocket.WebSocketContainer;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.AdditionalMatchers.aryEq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkerWebsocketConnectionTest {

  private static final URI CONTROLLER_URI = URI.create(
      "wss://controller.test/v1/worker");
  private static final String TOKEN = "token";
  private static final byte[] READY = bytes("{\"type\":\"ready\"}");
  private static final byte[] REQUEST = bytes("{\"request\":true}");
  private static final byte[] RESPONSE = bytes("{\"attestation\":true}");
  private static final byte[] CLIENT_KEY = new byte[] {1, 2, 3};
  private static final byte[] HOST_KEY = new byte[] {4, 5, 6};
  private static final byte[] SSH_BANNER =
      "SSH-2.0-OpenSSH_9.9\r\n".getBytes(StandardCharsets.US_ASCII);

  @Test
  void connectsWithBearerAuthenticationAndCompletesAttestation()
      throws Exception
  {
    Fixture fixture = new Fixture();
    fixture.prepareReadyConnection();

    assertArrayEquals(HOST_KEY, fixture.connect());

    Map<String, List<String>> headers = new HashMap<>();
    fixture.observedConfig.get()
                          .getConfigurator()
                          .beforeRequest(headers);
    assertEquals(List.of("Bearer token"), headers.get("Authorization"));

    InOrder order = inOrder(fixture.attestation,
                            fixture.attempt,
                            fixture.remote);
    order.verify(fixture.attestation).begin(aryEq(CLIENT_KEY));
    order.verify(fixture.attempt).getRequest();
    order.verify(fixture.remote).sendBinary(eq(ByteBuffer.wrap(REQUEST)));
    order.verify(fixture.attempt).verify(aryEq(RESPONSE));

    fixture.connection.close();
    verify(fixture.session).close(any(CloseReason.class));
  }

  @Test
  void parsesTheReadyResponseAsJson() throws Exception {
    Fixture fixture = new Fixture();
    fixture.prepareReadyConnection(bytes(
        "{\n"
        + "  \"type\" : \"ready\"\n"
        + "}"));

    assertArrayEquals(HOST_KEY, fixture.connect());
  }

  @Test
  void rejectsExtraFieldsInTheReadyResponse() throws Exception {
    List<byte[]> invalidMessages = List.of(
        bytes("{\"type\":\"ready\",\"manifest\":\"{}\"}"),
        bytes("{\"type\":\"ready\",\"ignored\":true}"));

    for (byte[] message : invalidMessages) {
      Fixture fixture = new Fixture();
      fixture.prepareInitialMessage(message);

      assertThrows(IOException.class, fixture::connect);
      verify(fixture.session).close(any(CloseReason.class));
    }
  }

  @Test
  void buffersPipelinedSshBytesUntilAttestationIsVerified()
      throws Exception
  {
    Fixture fixture = new Fixture();
    fixture.prepareReadyConnection();
    doAnswer(ignored -> {
      fixture.receiveBinary(RESPONSE);
      fixture.receiveBinary(SSH_BANNER);
      return null;
    }).when(fixture.remote).sendBinary(eq(ByteBuffer.wrap(REQUEST)));

    assertArrayEquals(HOST_KEY, fixture.connect());
    assertArrayEquals(
        SSH_BANNER,
        fixture.connection.getInputStream().readNBytes(SSH_BANNER.length));
  }

  @Test
  void closesWhenAttestationVerificationFails()
      throws Exception
  {
    Fixture fixture = new Fixture();
    fixture.prepareReadyConnection();
    WorkerException rejection = new WorkerException("untrusted worker");
    when(fixture.attempt.verify(aryEq(RESPONSE)))
        .thenThrow(rejection);

    assertSame(rejection, assertThrows(
        WorkerException.class,
        fixture::connect));

    verify(fixture.session).close(any(CloseReason.class));
    assertThrows(
        IOException.class,
        () -> fixture.connection.getInputStream().read());
    assertThrows(
        IOException.class,
        () -> fixture.connection.getOutputStream().write(1));
  }

  @Test
  void closingDuringAttestationVerificationCannotEnableSsh()
      throws Exception
  {
    Fixture fixture = new Fixture();
    fixture.prepareReadyConnection();
    CountDownLatch verifying = new CountDownLatch(1);
    CountDownLatch releaseVerification = new CountDownLatch(1);
    doAnswer(ignored -> {
      verifying.countDown();
      assertTrue(releaseVerification.await(1, TimeUnit.SECONDS));
      return HOST_KEY;
    }).when(fixture.attempt).verify(aryEq(RESPONSE));
    AtomicReference<byte[]> hostKey = new AtomicReference<>();
    Thread connecting = Thread.ofVirtual().start(() -> {
      try {
        hostKey.set(fixture.connect());
      } catch (IOException | WorkerException error) {
        throw new AssertionError(error);
      }
    });
    assertTrue(verifying.await(1, TimeUnit.SECONDS));

    fixture.connection.close();
    releaseVerification.countDown();
    connecting.join(TimeUnit.SECONDS.toMillis(1));

    assertArrayEquals(HOST_KEY, hostKey.get());
    assertThrows(
        IOException.class,
        () -> fixture.connection.getInputStream().read());
    assertThrows(
        IOException.class,
        () -> fixture.connection.getOutputStream().write(1));
    verify(fixture.session).close(any(CloseReason.class));
  }

  @Test
  void overflowDuringAttestationVerificationCannotEnableSsh()
      throws Exception
  {
    Fixture fixture = new Fixture();
    fixture.prepareReadyConnection();
    doAnswer(ignored -> {
      for (int index = 0; index < 64; index++) {
        fixture.receiveBinary(new byte[256 * 1024]);
      }
      fixture.receiveBinary(new byte[1]);
      return HOST_KEY;
    }).when(fixture.attempt).verify(aryEq(RESPONSE));

    assertArrayEquals(HOST_KEY, fixture.connect());

    verify(fixture.session).close(any(CloseReason.class));
    assertThrows(
        IOException.class,
        () -> fixture.connection.getInputStream().read());
  }

  @Test
  void closesWhenProtocolSetupThrowsAnUncheckedFailure()
      throws Exception
  {
    Fixture fixture = new Fixture();
    fixture.prepareReadyConnection();
    IllegalStateException failure = new IllegalStateException("bug");
    when(fixture.attestation.begin(aryEq(CLIENT_KEY)))
        .thenThrow(failure);

    assertSame(failure, assertThrows(
        IllegalStateException.class,
        fixture::connect));

    verify(fixture.session).close(any(CloseReason.class));
  }

  @Test
  void rejectsInvalidProtocolMessages() throws Exception {
    Fixture invalidReady = new Fixture();
    invalidReady.prepareInitialMessage(RESPONSE);
    assertThrows(IOException.class, invalidReady::connect);
    verify(invalidReady.session).close(any(CloseReason.class));

    Fixture invalidResponse = new Fixture();
    invalidResponse.prepareReadyConnection();
    when(invalidResponse.attempt.verify(aryEq(SSH_BANNER)))
        .thenThrow(new IOException("Invalid attestation response"));
    doAnswer(ignored -> {
      invalidResponse.receiveBinary(SSH_BANNER);
      return null;
    }).when(invalidResponse.remote)
      .sendBinary(eq(ByteBuffer.wrap(REQUEST)));
    assertThrows(IOException.class, invalidResponse::connect);
    verify(invalidResponse.session).close(any(CloseReason.class));
  }

  @Test
  void fragmentsLargeSshWritesIntoBoundedBinaryMessages()
      throws Exception
  {
    Fixture fixture = Fixture.ssh();
    List<byte[]> frames = new ArrayList<>();
    doAnswer(invocation -> {
      ByteBuffer source = invocation.getArgument(0);
      byte[] frame = new byte[source.remaining()];
      source.get(frame);
      frames.add(frame);
      return null;
    }).when(fixture.remote).sendBinary(any(ByteBuffer.class));
    byte[] data = new byte[70 * 1024];
    for (int index = 0; index < data.length; index++) {
      data[index] = (byte) index;
    }

    fixture.connection.getOutputStream().write(data);

    assertEquals(List.of(32 * 1024, 32 * 1024, 6 * 1024),
                 frames.stream().map(frame -> frame.length).toList());
    assertArrayEquals(data, concatenate(frames));
  }

  @Test
  void appliesReadTimeoutToSshReads()
      throws Exception
  {
    Fixture fixture = Fixture.ssh();
    fixture.connection.setReadTimeout(1);

    assertThrows(
        IOException.class,
        () -> fixture.connection.getInputStream().read());
  }

  @Test
  void drainsFinalSshBytesBeforeReportingNormalClose()
      throws Exception
  {
    Fixture fixture = Fixture.ssh();
    fixture.receiveBinary(SSH_BANNER);
    fixture.connection.onClose(
        fixture.session,
        new CloseReason(
            CloseReason.CloseCodes.NORMAL_CLOSURE,
            "complete"));

    assertArrayEquals(
        SSH_BANNER,
        fixture.connection.getInputStream().readNBytes(SSH_BANNER.length));
    assertEquals(-1, fixture.connection.getInputStream().read());
  }

  @Test
  void translatesSshWriteFailuresToIoExceptions()
      throws Exception
  {
    Fixture fixture = Fixture.ssh();
    doThrow(new IOException("write failed"))
        .when(fixture.remote)
        .sendBinary(any(ByteBuffer.class));

    assertThrows(
        IOException.class,
        () -> fixture.connection.getOutputStream().write(1));
    verify(fixture.session).close(any(CloseReason.class));
  }

  @Test
  void controllerCloseAndErrorBeforeReadyFailTheConnection()
      throws Exception
  {
    Fixture closed = new Fixture();
    when(closed.container.connectToServer(
        any(Endpoint.class),
        any(ClientEndpointConfig.class),
        eq(CONTROLLER_URI)))
        .thenAnswer(invocation -> {
          Endpoint endpoint = invocation.getArgument(0);
          endpoint.onOpen(closed.session, closed.endpointConfig);
          endpoint.onClose(
              closed.session,
              new CloseReason(
                  CloseReason.CloseCodes.NORMAL_CLOSURE,
                  "complete"));
          return closed.session;
        });
    assertThrows(IOException.class, closed::connect);

    Fixture errored = new Fixture();
    IOException source = new IOException("relay failed");
    when(errored.container.connectToServer(
        any(Endpoint.class),
        any(ClientEndpointConfig.class),
        eq(CONTROLLER_URI)))
        .thenAnswer(invocation -> {
          Endpoint endpoint = invocation.getArgument(0);
          endpoint.onOpen(errored.session, errored.endpointConfig);
          endpoint.onError(errored.session, source);
          return errored.session;
        });
    IOException failure = assertThrows(
        IOException.class,
        errored::connect);
    assertSame(source, failure.getCause());
    verify(errored.session).close(any(CloseReason.class));
  }

  private static byte[] concatenate(List<byte[]> values) {
    int length = values.stream().mapToInt(value -> value.length).sum();
    ByteBuffer combined = ByteBuffer.allocate(length);
    for (byte[] value : values) {
      combined.put(value);
    }
    return combined.array();
  }

  private static byte[] bytes(String value) {
    return value.getBytes(StandardCharsets.UTF_8);
  }

  private static class Fixture {

    private final WebSocketContainer container = mock(WebSocketContainer.class);
    private final Session session = mock(Session.class);
    private final RemoteEndpoint.Basic remote = mock(RemoteEndpoint.Basic.class);
    private final EndpointConfig endpointConfig = mock(EndpointConfig.class);
    private final WorkerAttestationHandshake attestation =
        mock(WorkerAttestationHandshake.class);
    private final WorkerAttestationHandshake.Attempt attempt =
        mock(WorkerAttestationHandshake.Attempt.class);
    private final AtomicReference<ClientEndpointConfig> observedConfig =
        new AtomicReference<>();
    private final WorkerWebsocketConnection connection =
        new WorkerWebsocketConnection(new ObjectMapper(), attestation);

    private Fixture() throws Exception {
      when(session.isOpen()).thenReturn(true);
      when(session.getBasicRemote()).thenReturn(remote);
      when(attestation.begin(aryEq(CLIENT_KEY))).thenReturn(attempt);
      when(attempt.getRequest()).thenReturn(REQUEST);
      when(attempt.verify(aryEq(RESPONSE))).thenReturn(HOST_KEY);
    }

    private static Fixture ssh() throws Exception {
      Fixture fixture = new Fixture();
      fixture.prepareReadyConnection();
      assertArrayEquals(HOST_KEY, fixture.connect());
      return fixture;
    }

    private void prepareReadyConnection() throws Exception {
      prepareReadyConnection(READY);
    }

    private void prepareReadyConnection(byte[] ready) throws Exception {
      prepareInitialMessage(ready);
      doAnswer(ignored -> {
        receiveBinary(RESPONSE);
        return null;
      }).when(remote).sendBinary(eq(ByteBuffer.wrap(REQUEST)));
    }

    private void prepareInitialMessage(byte[] message)
        throws Exception
    {
      when(container.connectToServer(
          any(Endpoint.class),
          any(ClientEndpointConfig.class),
          eq(CONTROLLER_URI)))
          .thenAnswer(invocation -> {
            observedConfig.set(invocation.getArgument(1));
            Endpoint endpoint = invocation.getArgument(0);
            endpoint.onOpen(session, endpointConfig);
            if (message != null) {
              receiveBinary(message);
            }
            return session;
          });
    }

    private byte[] connect() throws IOException, WorkerException {
      return connection.connect(
          container,
          CONTROLLER_URI,
          TOKEN,
          CLIENT_KEY);
    }

    private void receiveBinary(byte[] data) {
      binaryHandler().onMessage(ByteBuffer.wrap(data));
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
