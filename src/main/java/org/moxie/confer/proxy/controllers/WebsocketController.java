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
import org.moxie.confer.proxy.entities.InvalidWebsocketRequestException;
import org.moxie.confer.proxy.entities.WebsocketRequest;
import org.moxie.confer.proxy.entities.WebsocketResponse;
import org.moxie.confer.proxy.websocket.NoiseConnectionWebsocket;
import org.moxie.confer.proxy.websocket.Route;
import org.moxie.confer.proxy.websocket.WebsocketConnectionContext;
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
  PingWebsocketHandler pingWebsocketHandler;

  @Inject
  DocumentExtractionHandler documentExtractionHandler;

  @Inject
  LegacyDocumentExtractionHandler legacyDocumentExtractionHandler;

  @Inject
  EmbeddingHandler embeddingHandler;

  @Inject
  ExternalProxyFetchHandler externalProxyFetchHandler;

  private final Map<Route, WebsocketHandler> routes = new HashMap<>();

  @Inject
  public WebsocketController(AttestationService attestationService, ObjectMapper mapper)
  {
    super(attestationService, mapper);
  }

  @PostConstruct
  void initializeRoutes() {
    routes.put(new Route("POST", "/v1/vllm/chat/completions"), vllmWebsocketHandler);
    routes.put(new Route("POST", "/v1/document/extract"), legacyDocumentExtractionHandler);
    routes.put(new Route("POST", "/v2/document/extract"), documentExtractionHandler);
    routes.put(new Route("POST", "/v1/embeddings"), embeddingHandler);
    routes.put(new Route("POST", "/v1/fetch/html"), externalProxyFetchHandler);
    routes.put(new Route("GET", "/ping"), pingWebsocketHandler);
  }

  @Override
  public void onClose(Session session, CloseReason closeReason) {
    super.onClose(session, closeReason);
    closeConnectionContext(session);
  }

  @Override
  public void onError(Session session, Throwable throwable) {
    super.onError(session, throwable);
    failConnection(session, "WebSocket error");
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
    } catch (com.google.protobuf.InvalidProtocolBufferException error) {
      log.warn("Failed to parse protobuf request", error);
      closeQuiet(session, CloseReason.CloseCodes.CANNOT_ACCEPT, "Invalid protobuf message");
      return;
    } catch (InvalidWebsocketRequestException error) {
      log.warn("Invalid request: {}", error.getMessage());
      closeQuiet(session, CloseReason.CloseCodes.CANNOT_ACCEPT, error.getMessage());
      return;
    }

    WebsocketConnectionContext context = getConnectionContext(session);
    if (context == null) {
      sendResponseError(session, request.id(), 401, "Authentication required");
      return;
    }

    if (request.isStreamContinuation()) {
      handleStreamChunk(context, session, request);
      return;
    }

    if (context.requiresPayment(Instant.now())) {
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

    try (WebsocketHandlerResponse handlerResponse = handler.handle(context, request)) {
      sendHandlerResponse(session, request.id(), handlerResponse);
    } catch (WebApplicationException e) {
      log.warn("Error processing request", e);
      sendResponseError(session, request.id(), e.getResponse().getStatus(), e.getMessage());
      return;
    } catch (RuntimeException e) {
      log.warn("Error processing request", e);
      sendResponseError(session, request.id(), 500, "Internal Server Error");
      return;
    }
  }

  private static WebsocketConnectionContext getConnectionContext(Session session) {
    Object value = session.getUserProperties().get(WebsocketConnectionContext.SESSION_PROPERTY);
    return value instanceof WebsocketConnectionContext context ? context : null;
  }

  private static void closeConnectionContext(Session session) {
    WebsocketConnectionContext context = getConnectionContext(session);

    if (context != null) {
      context.close();
    }
  }

  private void handleStreamChunk(WebsocketConnectionContext context,
                                 Session                    session,
                                 WebsocketRequest           request)
  {
    if (request.chunk().isEmpty()) {
      log.warn("Stream continuation without chunk data");
      sendResponseError(session, request.id(), 400, "Chunk data required");
      return;
    }

    try {
      WebsocketRequest.StreamChunk chunk = request.chunk().get();
      context.getStreams().handleChunk(request.id(), chunk.data(), chunk.sequenceNumber(), chunk.isFinal());
    } catch (IllegalStateException e) {
      log.warn("Stream {} already completed", request.id());
      sendResponseError(session, request.id(), 400, "Stream already completed");
    } catch (IOException e) {
      log.warn("Error writing chunk", e);
      context.getStreams().cancelStream(request.id());
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
        case WebsocketHandlerResponse.StreamingResponse response -> {
          response.writeTo(new WebsocketOutputStream(session, requestId, response.headers()));
        }
      }
    } catch (WebApplicationException e) {
      log.warn("Error during streaming response", e);
      sendResponseError(session, requestId, e.getResponse().getStatus(), e.getMessage());
    } catch (IOException e) {
      log.warn("IOError processing response", e);
      failConnection(session, "Streaming response failed");
    }
  }

  private void sendResponseError(Session session, long id, int status, String message) {
    WebsocketResponse response   = new WebsocketResponse(id, status, message);
    byte[]            serialized = response.toProtobuf().toByteArray();

    try {
      sendMessage(session, serialized);
    } catch (IOException error) {
      log.warn("Failed to send error response");
      failConnection(session, "Response failed");
    }
  }

  private void failConnection(Session session,
                              String  reason)
  {
    closeConnectionContext(session);
    closeQuiet(
        session,
        CloseReason.CloseCodes.UNEXPECTED_CONDITION,
        reason);
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
