package org.moxie.confer.proxy.controllers;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.websocket.CloseReason;
import jakarta.websocket.Session;
import jakarta.websocket.server.ServerEndpoint;
import jakarta.ws.rs.WebApplicationException;
import org.moxie.confer.proxy.attestation.AttestationService;
import org.moxie.confer.proxy.auth.WebsocketAuthenticator;
import org.moxie.confer.proxy.entities.WebsocketRequest;
import org.moxie.confer.proxy.entities.WebsocketResponse;
import org.moxie.confer.proxy.streaming.StreamRegistry;
import org.moxie.confer.proxy.websocket.NoiseConnectionWebsocket;
import org.moxie.confer.proxy.websocket.Route;
import org.moxie.confer.proxy.websocket.WebsocketHandler;
import org.moxie.confer.proxy.websocket.WebsocketHandlerResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@ServerEndpoint(value = "/websocket", configurator = WebsocketAuthenticator.class)
public class WebsocketController extends NoiseConnectionWebsocket {

  private static final Logger log = LoggerFactory.getLogger(WebsocketController.class);

  @Inject
  @Named("vllm")
  OpenAIWebsocketHandler vllmWebsocketHandler;

  @Inject
  @Named("together")
  OpenAIWebsocketHandler togetherAIWebsocketHandler;

  @Inject
  PingWebsocketHandler pingWebsocketHandler;

  @Inject
  DocumentExtractionHandler documentExtractionHandler;

  private final Map<Route, WebsocketHandler> routes         = new HashMap<>();
  private final StreamRegistry               streamRegistry = new StreamRegistry();

  @Inject
  public WebsocketController(AttestationService attestationService, ObjectMapper mapper)
  {
    super(attestationService, mapper);
  }

  @PostConstruct
  private void initializeRoutes() {
    routes.put(new Route("POST", "/v1/vllm/chat/completions"), vllmWebsocketHandler);
    routes.put(new Route("POST", "/v1/together/chat/completions"), togetherAIWebsocketHandler);
    routes.put(new Route("POST", "/v1/document/extract"), documentExtractionHandler);
    routes.put(new Route("GET", "/ping"), pingWebsocketHandler);
  }

  @Override
  public void onClose(Session session, CloseReason closeReason) {
    super.onClose(session, closeReason);
    streamRegistry.cancelAll();
  }

  @Override
  protected void onReceiveMessage(Session session, byte[] data) {
    Thread.startVirtualThread(() -> handleRequest(session, data));
  }

  private void handleRequest(Session session, byte[] data) {
    WebsocketRequest request;

    try {
      confer.NoiseTransport.WebsocketRequest protoRequest =
          confer.NoiseTransport.WebsocketRequest.parseFrom(data);

      request = WebsocketRequest.fromProtobuf(protoRequest);
    } catch (com.google.protobuf.InvalidProtocolBufferException e) {
      log.warn("Failed to parse protobuf request", e);
      closeQuiet(session, CloseReason.CloseCodes.CANNOT_ACCEPT, "Invalid protobuf message");
      return;
    } catch (IllegalArgumentException e) {
      log.warn("Invalid request: {}", e.getMessage());
      closeQuiet(session, CloseReason.CloseCodes.CANNOT_ACCEPT, e.getMessage());
      return;
    }

    // Handle stream continuation chunks (no verb/path, just id + chunk)
    if (request.isStreamContinuation()) {
      handleStreamChunk(session, request);
      return;
    }

    Instant tokenExpiry = (Instant) session.getUserProperties().get("tokenExpiry");
    Boolean subscribed  = (Boolean) session.getUserProperties().get("subscribed" );
    boolean isFreeTier  = subscribed == null || !subscribed;

    if (isFreeTier && tokenExpiry != null && Instant.now().isAfter(tokenExpiry)) {
      sendResponseError(session, request.id(), 402, "Payment required");
      return;
    }

    Route            route   = new Route(request.verb().orElse(""), request.path().orElse(""));
    WebsocketHandler handler = routes.get(route);

    if (handler == null) {
      log.warn("No handler found for route: {}", route);
      sendResponseError(session, request.id(), 404, "Route not found");
      return;
    }

    WebsocketHandlerResponse handlerResponse;

    try {
      handlerResponse = handler.handle(request, streamRegistry);
    } catch (WebApplicationException e) {
      log.warn("Error processing request", e);
      sendResponseError(session, request.id(), e.getResponse().getStatus(), e.getMessage());
      return;
    } catch (Exception e) {
      log.warn("Error processing request", e);
      sendResponseError(session, request.id(), 500, "Internal Server Error");
      return;
    }

    sendHandlerResponse(session, request.id(), handlerResponse);
  }

  private void handleStreamChunk(Session session, WebsocketRequest request) {
    if (request.chunk().isEmpty()) {
      log.warn("Stream continuation without chunk data");
      sendResponseError(session, request.id(), 400, "Chunk data required");
      return;
    }

    try {
      WebsocketRequest.StreamChunk chunk = request.chunk().get();
      streamRegistry.handleChunk(request.id(), chunk.data(), chunk.sequenceNumber(), chunk.isFinal());
    } catch (IllegalStateException e) {
      log.warn("Stream {} already completed", request.id());
      sendResponseError(session, request.id(), 400, "Stream already completed");
    } catch (IOException e) {
      log.warn("Error writing chunk", e);
      streamRegistry.cancelStream(request.id());
      sendResponseError(session, request.id(), 500, "Stream write failed");
    }
  }

  private void sendHandlerResponse(Session session, long requestId, WebsocketHandlerResponse handlerResponse) {
    try {
      switch (handlerResponse) {
        case WebsocketHandlerResponse.SingleResponse(int statusCode, String body) -> {
          WebsocketResponse response = new WebsocketResponse(requestId, statusCode, body);
          byte[] responseData = response.toProtobuf().toByteArray();
          sendMessage(session, responseData);
        }
        case WebsocketHandlerResponse.StreamingResponse(Map<String, String> headers, jakarta.ws.rs.core.StreamingOutput stream) -> {
          WebsocketOutputStream outputStream = new WebsocketOutputStream(session, requestId, headers);
          stream.write(outputStream);
        }
      }
    } catch (WebApplicationException e) {
      log.warn("Error during streaming response", e);
      sendResponseError(session, requestId, e.getResponse().getStatus(), e.getMessage());
    } catch (IOException e) {
      log.warn("IOError processing response", e);
      sendResponseError(session, requestId, 500, "IO Error");
    }
  }

  private void sendResponseError(Session session, long id, int status, String message) {
    WebsocketResponse response   = new WebsocketResponse(id, status, message);
    byte[]            serialized = response.toProtobuf().toByteArray();

    sendMessage(session, serialized);
  }

  private class WebsocketOutputStream extends OutputStream {

    private final Session             session;
    private final long                id;
    private final Map<String, String> headers;

    private WebsocketOutputStream(Session session, long id, Map<String, String> headers) {
      this.session = session;
      this.id      = id;
      this.headers = headers;
    }

    @Override
    public void write(int b) throws IOException {
      byte[] barr = new byte[1];
      barr[0] = (byte) b;
      write(barr, 0, 1);
    }

    @Override
    public void write(byte[] b) throws IOException {
      write(b, 0, b.length);
    }

    @Override
    public void write(byte[] b, int offset, int length) throws IOException {
      WebsocketResponse response   = new WebsocketResponse(id, 200, b, offset, length, headers);
      byte[]            serialized = response.toProtobuf().toByteArray();
      sendMessage(session, serialized);
    }
  }

}
