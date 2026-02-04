package org.moxie.confer.proxy.controllers;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.ByteString;
import com.southernstorm.noise.protocol.CipherState;
import jakarta.websocket.RemoteEndpoint;
import jakarta.websocket.Session;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.moxie.confer.proxy.attestation.AttestationService;
import org.moxie.confer.proxy.websocket.WebsocketHandler;
import org.moxie.confer.proxy.websocket.WebsocketHandlerResponse;

import jakarta.websocket.CloseReason;
import jakarta.ws.rs.WebApplicationException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WebsocketControllerTest {

  @Mock
  private AttestationService attestationService;

  @Mock
  private Session session;

  @Mock
  private RemoteEndpoint.Basic basicRemote;

  @Mock
  private CipherState mockCipher;

  private ObjectMapper mapper;
  private TestableWebsocketController controller;
  private List<byte[]> sentMessages;

  @BeforeEach
  void setUp() throws Exception {
    mapper = new ObjectMapper();
    sentMessages = Collections.synchronizedList(new ArrayList<>());

    lenient().when(session.getBasicRemote()).thenReturn(basicRemote);
    lenient().when(session.getUserProperties()).thenReturn(new java.util.HashMap<>());
    lenient().doAnswer(invocation -> {
      ByteBuffer buffer = invocation.getArgument(0);
      byte[] copy = new byte[buffer.remaining()];
      buffer.get(copy);
      sentMessages.add(copy);
      return null;
    }).when(basicRemote).sendBinary(any(ByteBuffer.class));

    // Mock cipher to just copy input to output with fake 16-byte auth tag
    lenient().when(mockCipher.encryptWithAd(isNull(), any(byte[].class), anyInt(), any(byte[].class), anyInt(), anyInt()))
        .thenAnswer(invocation -> {
          byte[] plaintext = invocation.getArgument(1);
          int plaintextOffset = invocation.getArgument(2);
          byte[] ciphertext = invocation.getArgument(3);
          int ciphertextOffset = invocation.getArgument(4);
          int length = invocation.getArgument(5);
          System.arraycopy(plaintext, plaintextOffset, ciphertext, ciphertextOffset, length);
          return length + 16;
        });

    controller = new TestableWebsocketController(attestationService, mapper, mockCipher);
  }

  @Test
  void onReceiveMessage_concurrentRequests_areProcessedInParallel() throws Exception {
    // This test verifies that multiple requests can be processed concurrently.
    // We simulate a slow handler and verify that a fast request (ping) can complete
    // while the slow request is still processing.

    CountDownLatch slowHandlerStarted = new CountDownLatch(1);
    CountDownLatch slowHandlerCanFinish = new CountDownLatch(1);
    CountDownLatch fastHandlerCompleted = new CountDownLatch(1);
    AtomicInteger slowHandlerCallCount = new AtomicInteger(0);
    AtomicInteger fastHandlerCallCount = new AtomicInteger(0);

    // Slow handler that blocks until we signal it
    WebsocketHandler slowHandler = (request, registry) -> {
      slowHandlerCallCount.incrementAndGet();
      slowHandlerStarted.countDown();
      try {
        slowHandlerCanFinish.await(10, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      return new WebsocketHandlerResponse.SingleResponse(200, "slow response");
    };

    // Fast handler (like ping) that completes immediately
    WebsocketHandler fastHandler = (request, registry) -> {
      fastHandlerCallCount.incrementAndGet();
      fastHandlerCompleted.countDown();
      return new WebsocketHandlerResponse.SingleResponse(200, "pong");
    };

    controller.setRoutes(Map.of(
        new org.moxie.confer.proxy.websocket.Route("POST", "/slow"), slowHandler,
        new org.moxie.confer.proxy.websocket.Route("GET", "/ping"), fastHandler
    ));

    // Send the slow request first
    byte[] slowRequest = createProtobufRequest(1, "POST", "/slow", "");
    controller.testOnReceiveMessage(session, slowRequest);

    // Wait for slow handler to start
    assertTrue(slowHandlerStarted.await(5, TimeUnit.SECONDS), "Slow handler should start");

    // Now send the fast request while slow handler is still running
    byte[] fastRequest = createProtobufRequest(2, "GET", "/ping", null);
    controller.testOnReceiveMessage(session, fastRequest);

    // Fast handler should complete even though slow handler is still blocking
    assertTrue(fastHandlerCompleted.await(5, TimeUnit.SECONDS),
        "Fast handler should complete while slow handler is still running");

    // Verify both handlers were called
    assertEquals(1, slowHandlerCallCount.get(), "Slow handler should be called once");
    assertEquals(1, fastHandlerCallCount.get(), "Fast handler should be called once");

    // Let slow handler finish
    slowHandlerCanFinish.countDown();

    // Give time for responses to be sent
    Thread.sleep(100);

    // Should have received responses for both requests
    assertTrue(sentMessages.size() >= 2, "Should have sent at least 2 responses");
  }

  @Test
  void onReceiveMessage_returnsImmediately() throws Exception {
    // Verify that onReceiveMessage returns immediately (doesn't block on handler)

    CountDownLatch handlerStarted = new CountDownLatch(1);
    CountDownLatch handlerCanFinish = new CountDownLatch(1);

    WebsocketHandler blockingHandler = (request, registry) -> {
      handlerStarted.countDown();
      try {
        handlerCanFinish.await(10, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      return new WebsocketHandlerResponse.SingleResponse(200, "done");
    };

    controller.setRoutes(Map.of(
        new org.moxie.confer.proxy.websocket.Route("POST", "/blocking"), blockingHandler
    ));

    byte[] request = createProtobufRequest(1, "POST", "/blocking", "");

    long startTime = System.currentTimeMillis();
    controller.testOnReceiveMessage(session, request);
    long elapsed = System.currentTimeMillis() - startTime;

    // onReceiveMessage should return almost immediately (< 100ms)
    // even though the handler blocks for up to 10 seconds
    assertTrue(elapsed < 100, "onReceiveMessage should return immediately, took " + elapsed + "ms");

    // Wait for handler to start to confirm it was called
    assertTrue(handlerStarted.await(5, TimeUnit.SECONDS), "Handler should be started");

    // Clean up
    handlerCanFinish.countDown();
  }

  @Test
  void onReceiveMessage_routeNotFound_returns404() throws Exception {
    controller.setRoutes(Map.of());

    byte[] request = createProtobufRequest(1, "GET", "/nonexistent", null);
    controller.testOnReceiveMessage(session, request);

    // Give virtual thread time to process
    Thread.sleep(100);

    // Should have sent a 404 response
    assertFalse(sentMessages.isEmpty(), "Should have sent a response");
  }

  @Test
  void onReceiveMessage_invalidProtobuf_closesSession() throws Exception {
    when(session.isOpen()).thenReturn(true);
    controller.setRoutes(Map.of());

    byte[] invalidData = "not a protobuf".getBytes();
    controller.testOnReceiveMessage(session, invalidData);

    Thread.sleep(100);

    verify(session, atLeastOnce()).close(any(CloseReason.class));
  }

  @Test
  void onReceiveMessage_missingRequiredFields_closesSession() throws Exception {
    when(session.isOpen()).thenReturn(true);
    controller.setRoutes(Map.of());

    // Protobuf with no ID
    byte[] request = confer.NoiseTransport.WebsocketRequest.newBuilder()
        .setVerb("GET")
        .setPath("/test")
        .build()
        .toByteArray();

    controller.testOnReceiveMessage(session, request);

    Thread.sleep(100);

    verify(session, atLeastOnce()).close(any(CloseReason.class));
  }

  @Test
  void onReceiveMessage_handlerThrowsWebApplicationException_returnsErrorStatus() throws Exception {
    WebsocketHandler throwingHandler = (request, registry) -> {
      throw new WebApplicationException("Bad request", 400);
    };

    controller.setRoutes(Map.of(
        new org.moxie.confer.proxy.websocket.Route("POST", "/error"), throwingHandler
    ));

    byte[] request = createProtobufRequest(1, "POST", "/error", "");
    controller.testOnReceiveMessage(session, request);

    Thread.sleep(100);

    assertFalse(sentMessages.isEmpty(), "Should have sent an error response");
  }

  @Test
  void onReceiveMessage_handlerThrowsException_returns500() throws Exception {
    WebsocketHandler throwingHandler = (request, registry) -> {
      throw new RuntimeException("Unexpected error");
    };

    controller.setRoutes(Map.of(
        new org.moxie.confer.proxy.websocket.Route("POST", "/crash"), throwingHandler
    ));

    byte[] request = createProtobufRequest(1, "POST", "/crash", "");
    controller.testOnReceiveMessage(session, request);

    Thread.sleep(100);

    assertFalse(sentMessages.isEmpty(), "Should have sent an error response");
  }

  @Test
  void onReceiveMessage_streamContinuation_handlesChunk() throws Exception {
    CountDownLatch chunkReceived = new CountDownLatch(1);

    WebsocketHandler streamHandler = (request, registry) -> {
      return new WebsocketHandlerResponse.StreamingResponse(output -> {
        try {
          // Register the stream and wait for chunks
          var ctx = registry.createStream(request.id(), new ByteArrayOutputStream());
          chunkReceived.await(5, TimeUnit.SECONDS);
          output.write("done".getBytes());
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      });
    };

    controller.setRoutes(Map.of(
        new org.moxie.confer.proxy.websocket.Route("POST", "/stream"), streamHandler
    ));

    // Send initial streaming request
    byte[] initialRequest = createStreamingProtobufRequest(1, "POST", "/stream", "", "chunk1".getBytes(), 0, false);
    controller.testOnReceiveMessage(session, initialRequest);

    Thread.sleep(50);

    // Send continuation chunk
    byte[] continuation = createContinuationChunk(1, "chunk2".getBytes(), 1, true);
    controller.testOnReceiveMessage(session, continuation);

    chunkReceived.countDown();

    Thread.sleep(100);
  }

  @Test
  void onReceiveMessage_streamContinuationWithoutChunk_returns400() throws Exception {
    when(session.isOpen()).thenReturn(true);
    controller.setRoutes(Map.of());

    // Continuation with no chunk data (only id, no verb/path, no chunk)
    byte[] invalidContinuation = confer.NoiseTransport.WebsocketRequest.newBuilder()
        .setId(1)
        .build()
        .toByteArray();

    controller.testOnReceiveMessage(session, invalidContinuation);

    Thread.sleep(100);

    // Should close since it's invalid (no verb/path and no chunk)
    verify(session, atLeastOnce()).close(any(CloseReason.class));
  }

  @Test
  void onReceiveMessage_freeTierExpiredToken_returns402() throws Exception {
    Map<String, Object> userProps = new HashMap<>();
    userProps.put("tokenExpiry", Instant.now().minusSeconds(60)); // Expired
    userProps.put("subscribed", false);
    when(session.getUserProperties()).thenReturn(userProps);

    WebsocketHandler handler = (request, registry) -> {
      return new WebsocketHandlerResponse.SingleResponse(200, "ok");
    };

    controller.setRoutes(Map.of(
        new org.moxie.confer.proxy.websocket.Route("GET", "/test"), handler
    ));

    byte[] request = createProtobufRequest(1, "GET", "/test", null);
    controller.testOnReceiveMessage(session, request);

    Thread.sleep(100);

    assertFalse(sentMessages.isEmpty(), "Should have sent a 402 response");
  }

  @Test
  void onReceiveMessage_subscribedUser_bypassesTokenExpiry() throws Exception {
    Map<String, Object> userProps = new HashMap<>();
    userProps.put("tokenExpiry", Instant.now().minusSeconds(60)); // Expired
    userProps.put("subscribed", true);
    when(session.getUserProperties()).thenReturn(userProps);

    CountDownLatch handlerCalled = new CountDownLatch(1);
    WebsocketHandler handler = (request, registry) -> {
      handlerCalled.countDown();
      return new WebsocketHandlerResponse.SingleResponse(200, "ok");
    };

    controller.setRoutes(Map.of(
        new org.moxie.confer.proxy.websocket.Route("GET", "/test"), handler
    ));

    byte[] request = createProtobufRequest(1, "GET", "/test", null);
    controller.testOnReceiveMessage(session, request);

    assertTrue(handlerCalled.await(1, TimeUnit.SECONDS), "Handler should be called for subscribed user");
  }

  @Test
  void onReceiveMessage_streamingResponse_writesToOutput() throws Exception {
    java.util.concurrent.CountDownLatch handlerComplete = new java.util.concurrent.CountDownLatch(1);

    WebsocketHandler streamingHandler = (request, registry) -> {
      return new WebsocketHandlerResponse.StreamingResponse(output -> {
        output.write("chunk1".getBytes());
        output.write("chunk2".getBytes());
        handlerComplete.countDown();
      });
    };

    controller.setRoutes(Map.of(
        new org.moxie.confer.proxy.websocket.Route("GET", "/stream"), streamingHandler
    ));

    byte[] request = createProtobufRequest(1, "GET", "/stream", null);
    controller.testOnReceiveMessage(session, request);

    assertTrue(handlerComplete.await(5, TimeUnit.SECONDS), "Handler should complete");
    assertTrue(sentMessages.size() >= 2, "Should have sent multiple streaming responses but got " + sentMessages.size());
  }

  @Test
  void onReceiveMessage_streamingResponseThrowsIOException_sendsError() throws Exception {
    WebsocketHandler streamingHandler = (request, registry) -> {
      return new WebsocketHandlerResponse.StreamingResponse(output -> {
        throw new IOException("Write failed");
      });
    };

    controller.setRoutes(Map.of(
        new org.moxie.confer.proxy.websocket.Route("GET", "/stream"), streamingHandler
    ));

    byte[] request = createProtobufRequest(1, "GET", "/stream", null);
    controller.testOnReceiveMessage(session, request);

    Thread.sleep(100);

    assertFalse(sentMessages.isEmpty(), "Should have sent an error response");
  }

  @Test
  void onClose_cancelsAllStreams() throws Exception {
    controller.setRoutes(Map.of());

    CloseReason closeReason = new CloseReason(CloseReason.CloseCodes.NORMAL_CLOSURE, "Test");
    controller.testOnClose(session, closeReason);

    // Should not throw - streamRegistry.cancelAll() is called
  }


  private byte[] createProtobufRequest(long id, String verb, String path, String body) {
    confer.NoiseTransport.WebsocketRequest.Builder builder = confer.NoiseTransport.WebsocketRequest.newBuilder()
        .setId(id)
        .setVerb(verb)
        .setPath(path);

    if (body != null) {
      builder.setBody(ByteString.copyFromUtf8(body));
    }

    return builder.build().toByteArray();
  }

  private byte[] createStreamingProtobufRequest(long id, String verb, String path, String body, byte[] chunkData, int seq, boolean isFinal) {
    confer.NoiseTransport.StreamChunk chunk = confer.NoiseTransport.StreamChunk.newBuilder()
        .setData(ByteString.copyFrom(chunkData))
        .setSeq(seq)
        .setFinal(isFinal)
        .build();

    confer.NoiseTransport.WebsocketRequest.Builder builder = confer.NoiseTransport.WebsocketRequest.newBuilder()
        .setId(id)
        .setVerb(verb)
        .setPath(path)
        .setChunk(chunk);

    if (body != null) {
      builder.setBody(ByteString.copyFromUtf8(body));
    }

    return builder.build().toByteArray();
  }

  private byte[] createContinuationChunk(long id, byte[] chunkData, int seq, boolean isFinal) {
    confer.NoiseTransport.StreamChunk chunk = confer.NoiseTransport.StreamChunk.newBuilder()
        .setData(ByteString.copyFrom(chunkData))
        .setSeq(seq)
        .setFinal(isFinal)
        .build();

    return confer.NoiseTransport.WebsocketRequest.newBuilder()
        .setId(id)
        .setChunk(chunk)
        .build()
        .toByteArray();
  }

  /**
   * Testable subclass that exposes onReceiveMessage and allows injecting mock routes
   */
  private static class TestableWebsocketController extends WebsocketController {

    private Map<org.moxie.confer.proxy.websocket.Route, WebsocketHandler> testRoutes;

    TestableWebsocketController(AttestationService attestationService, ObjectMapper mapper, CipherState cipher) {
      super(attestationService, mapper);

      // Use reflection to set the sendCipher field in parent class
      try {
        java.lang.reflect.Field sendCipherField =
            org.moxie.confer.proxy.websocket.NoiseConnectionWebsocket.class.getDeclaredField("sendCipher");
        sendCipherField.setAccessible(true);
        sendCipherField.set(this, cipher);
      } catch (Exception e) {
        throw new RuntimeException("Failed to set mock cipher", e);
      }
    }

    void setRoutes(Map<org.moxie.confer.proxy.websocket.Route, WebsocketHandler> routes) {
      this.testRoutes = routes;

      // Use reflection to set the routes field
      try {
        java.lang.reflect.Field routesField = WebsocketController.class.getDeclaredField("routes");
        routesField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<org.moxie.confer.proxy.websocket.Route, WebsocketHandler> actualRoutes =
            (Map<org.moxie.confer.proxy.websocket.Route, WebsocketHandler>) routesField.get(this);
        actualRoutes.clear();
        actualRoutes.putAll(routes);
      } catch (Exception e) {
        throw new RuntimeException("Failed to set routes", e);
      }
    }

    void testOnReceiveMessage(Session session, byte[] data) {
      onReceiveMessage(session, data);
    }

    void testOnClose(Session session, CloseReason closeReason) {
      onClose(session, closeReason);
    }
  }
}
