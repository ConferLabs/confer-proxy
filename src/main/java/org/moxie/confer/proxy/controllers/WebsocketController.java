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

  private final Map<Route, WebsocketHandler> routes = new HashMap<>();

  @Inject
  public WebsocketController(AttestationService attestationService, ObjectMapper mapper)
  {
    super(attestationService, mapper);
  }

  @PostConstruct
  private void initializeRoutes() {
    routes.put(new Route("POST", "/v1/vllm/chat/completions"), vllmWebsocketHandler);
    routes.put(new Route("POST", "/v1/together/chat/completions"), togetherAIWebsocketHandler);
    routes.put(new Route("GET", "/ping"), pingWebsocketHandler);
  }

  @Override
  protected void onReceiveMessage(Session session, byte[] data, int offset, int length) {
    WebsocketRequest request;

    try {
      byte[] messageBytes = new byte[length];
      System.arraycopy(data, offset, messageBytes, 0, length);

      confer.NoiseTransport.WebsocketRequest protoRequest =
          confer.NoiseTransport.WebsocketRequest.parseFrom(messageBytes);

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

    Instant tokenExpiry = (Instant) session.getUserProperties().get("tokenExpiry");
    Boolean subscribed = (Boolean) session.getUserProperties().get("subscribed");
    boolean isFreeTier = subscribed == null || !subscribed;

    if (isFreeTier && tokenExpiry != null && Instant.now().isAfter(tokenExpiry)) {
      sendResponseError(session, request.id(), 402, "Payment required");
      return;
    }

    Route            route   = new Route(request.verb(), request.path());
    WebsocketHandler handler = routes.get(route);

    if (handler != null) {
      WebsocketHandlerResponse handlerResponse;

      try {
        handlerResponse = handler.handle(request);
      } catch (WebApplicationException e) {
        log.warn("Error processing request", e);
        sendResponseError(session, request.id(), e.getResponse().getStatus(), e.getMessage());
        return;
      } catch (Exception e) {
        log.warn("Error processing request", e);
        sendResponseError(session, request.id(), 500, "Internal Server Error");
        return;
      }

      try {
        switch (handlerResponse) {
          case WebsocketHandlerResponse.SingleResponse(int statusCode, String body) -> {
            WebsocketResponse response     = new WebsocketResponse(request.id(), statusCode, body);
            byte[]            responseData = response.toProtobuf().toByteArray();

            sendMessage(session, responseData, 0, responseData.length);
          }
          case WebsocketHandlerResponse.StreamingResponse(var stream) -> {
            WebsocketOutputStream outputStream = new WebsocketOutputStream(session, request.id());
            stream.write(outputStream);
          }
        }
      } catch (IOException e) {
        log.warn("IOError processing response", e);
        sendResponseError(session, request.id(), 500, "IO Error");
      }
    } else {
      log.warn("No handler found for route: {}", route);
      sendResponseError(session, request.id(), 404, "Route not found");
    }
  }

  private void sendResponseError(Session session, long id, int status, String message) {
    WebsocketResponse response   = new WebsocketResponse(id, status, message);
    byte[]            serialized = response.toProtobuf().toByteArray();

    sendMessage(session, serialized, 0, serialized.length);
  }

  private class WebsocketOutputStream extends OutputStream {

    private final Session session;
    private final long    id;

    private WebsocketOutputStream(Session session, long id) {
      this.session = session;
      this.id      = id;
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
      WebsocketResponse response   = new WebsocketResponse(id, 200, new String(b, offset, length));
      byte[]            serialized = response.toProtobuf().toByteArray();
      sendMessage(session, serialized, 0, serialized.length);
    }
  }

}
