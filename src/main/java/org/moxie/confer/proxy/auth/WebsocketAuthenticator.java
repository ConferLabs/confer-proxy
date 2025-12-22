package org.moxie.confer.proxy.auth;

import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.interfaces.DecodedJWT;
import jakarta.enterprise.inject.spi.CDI;
import jakarta.websocket.HandshakeResponse;
import jakarta.websocket.server.HandshakeRequest;
import jakarta.websocket.server.ServerEndpointConfig;
import org.moxie.confer.proxy.config.Config;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class WebsocketAuthenticator extends ServerEndpointConfig.Configurator {

  private volatile JWT jwt;

  private JWT jwt() {
    JWT j = jwt;
    if (j != null) return j;

    synchronized (this) {
      if (jwt == null) {
        Config cfg = CDI.current().select(Config.class).get();
        jwt = new JWT(cfg.getJwtSecret());
      }

      return jwt;
    }
  }

  @Override
  public void modifyHandshake(ServerEndpointConfig sec,
                              HandshakeRequest req,
                              HandshakeResponse resp)
  {
    Optional<String> token = first(req.getParameterMap(), "token");

    if (token.isEmpty()) {
      rejectHandshake(resp, "Missing token");
      return;
    }

    try {
      DecodedJWT decoded    = jwt().verify(token.get());
      Instant    expiry     = decoded.getExpiresAtAsInstant();
      Boolean    subscribed = decoded.getClaim("subscribed").asBoolean();
      sec.getUserProperties().put("tokenExpiry", expiry);
      sec.getUserProperties().put("subscribed", subscribed != null ? subscribed : false);
    } catch (JWTVerificationException e) {
      rejectHandshake(resp, "Invalid token");
    }
  }

  private void rejectHandshake(HandshakeResponse resp, String message) {
      throw new RuntimeException(message);
  }

  private static Optional<String> first(Map<String, List<String>> params, String key) {
    List<String> list = params.get(key);
    return (list == null || list.isEmpty()) ? Optional.empty() : Optional.of(list.getFirst());
  }

}
