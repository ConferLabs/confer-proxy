package org.moxie.confer.proxy.auth;

import com.auth0.jwt.JWTCreator;
import com.auth0.jwt.algorithms.Algorithm;
import jakarta.websocket.HandshakeResponse;
import jakarta.websocket.server.HandshakeRequest;
import jakarta.websocket.server.ServerEndpointConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.moxie.confer.proxy.websocket.WebsocketConnectionContext;
import org.moxie.confer.proxy.workers.WorkerClient;
import org.moxie.confer.proxy.workers.WorkerWorkspace;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WebsocketAuthenticatorTest {

  private static final String SECRET = "websocket-authenticator-test-secret";
  private static final String ATTACHMENT_PREFIX = "generated-attachments";

  private WebsocketAuthenticator authenticator;
  private ServerEndpointConfig  endpoint;
  private HandshakeResponse     response;

  @BeforeEach
  void setUp() throws ReflectiveOperationException {
    authenticator = new WebsocketAuthenticator();
    Field jwt = WebsocketAuthenticator.class.getDeclaredField("jwt");
    jwt.setAccessible(true);
    jwt.set(authenticator, new JWT(SECRET));
    endpoint = mock(ServerEndpointConfig.class);
    response = mock(HandshakeResponse.class);
    when(endpoint.getUserProperties()).thenReturn(new HashMap<>());
  }

  @Test
  void usesTheOpaqueSubjectAsTheAttachmentPrefix() {
    String token = token(ATTACHMENT_PREFIX, true);

    authenticator.modifyHandshake(endpoint, request(token), response);

    WebsocketConnectionContext context = (WebsocketConnectionContext)
        endpoint.getUserProperties().get(WebsocketConnectionContext.SESSION_PROPERTY);
    WorkerClient    client    = mock(WorkerClient.class);
    WorkerWorkspace workspace = mock(WorkerWorkspace.class);
    when(client.getWorkspace(ATTACHMENT_PREFIX)).thenReturn(workspace);
    context.getWorkerWorkspace(client);
    verify(client).getWorkspace(ATTACHMENT_PREFIX);
    assertFalse(context.requiresPayment(Instant.MAX));
  }

  @Test
  void rejectsMissingAndBlankSubjects() {
    for (String token : List.of(
        token(null, false),
        token(" ", false))) {
      ServerEndpointConfig rejectedEndpoint = mock(ServerEndpointConfig.class);
      when(rejectedEndpoint.getUserProperties()).thenReturn(new HashMap<>());

      RuntimeException error = assertThrows(
          RuntimeException.class,
          () -> authenticator.modifyHandshake(
              rejectedEndpoint,
              request(token),
              response));

      assertEquals("Invalid token", error.getMessage());
      assertFalse(rejectedEndpoint.getUserProperties().containsKey(
          WebsocketConnectionContext.SESSION_PROPERTY));
    }
  }

  @Test
  void rejectsAMissingToken() {
    HandshakeRequest request = mock(HandshakeRequest.class);
    when(request.getParameterMap()).thenReturn(Map.of());

    RuntimeException error = assertThrows(
        RuntimeException.class,
        () -> authenticator.modifyHandshake(endpoint, request, response));

    assertEquals("Missing token", error.getMessage());
    assertFalse(endpoint.getUserProperties().containsKey(
        WebsocketConnectionContext.SESSION_PROPERTY));
  }

  @Test
  void rejectsTokensWithTheWrongSignature() {
    String token = signedToken(
        ATTACHMENT_PREFIX,
        Instant.now().plusSeconds(60),
        "a-different-secret");

    assertInvalid(token);
  }

  @Test
  void rejectsExpiredTokens() {
    String token = signedToken(
        ATTACHMENT_PREFIX,
        Instant.now().minusSeconds(60),
        SECRET);

    assertInvalid(token);
  }

  @Test
  void rejectsTokensWithoutAnExpiry() {
    String token = signedToken(ATTACHMENT_PREFIX, null, SECRET);

    assertInvalid(token);
  }

  private static HandshakeRequest request(String token) {
    HandshakeRequest request = mock(HandshakeRequest.class);
    when(request.getParameterMap()).thenReturn(Map.of("token", List.of(token)));
    return request;
  }

  private static String token(String attachmentPrefix, boolean subscribed) {
    JWTCreator.Builder token = com.auth0.jwt.JWT.create()
        .withIssuer("kerf")
        .withExpiresAt(Instant.now().plusSeconds(60))
        .withClaim("subscribed", subscribed);
    if (attachmentPrefix != null) {
      token.withSubject(attachmentPrefix);
    }
    return token.sign(Algorithm.HMAC256(SECRET));
  }

  private void assertInvalid(String token) {
    RuntimeException error = assertThrows(
        RuntimeException.class,
        () -> authenticator.modifyHandshake(endpoint, request(token), response));

    assertEquals("Invalid token", error.getMessage());
    assertFalse(endpoint.getUserProperties().containsKey(
        WebsocketConnectionContext.SESSION_PROPERTY));
  }

  private static String signedToken(String  attachmentPrefix,
                                    Instant expiry,
                                    String  secret)
  {
    JWTCreator.Builder token = com.auth0.jwt.JWT.create()
        .withIssuer("kerf")
        .withSubject(attachmentPrefix);
    if (expiry != null) {
      token.withExpiresAt(expiry);
    }
    return token.sign(Algorithm.HMAC256(secret));
  }
}
