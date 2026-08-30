package org.moxie.confer.proxy.auth;

import com.auth0.jwt.interfaces.DecodedJWT;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JWTTest {

  private static final String SECRET = "jwt-test-secret-at-least-32-characters";
  private static final String SUBJECT = "generated-attachments";

  @Test
  void generatesAFreshControllerTokenForTheVerifiedSubject() {
    JWT jwt = new JWT(SECRET);
    Instant before = Instant.now();

    DecodedJWT token = jwt.verify(jwt.generate(SUBJECT));

    assertEquals(SUBJECT, token.getSubject());
    assertEquals("kerf", token.getIssuer());
    assertTrue(token.getExpiresAtAsInstant().isAfter(
        before.plus(Duration.ofMinutes(14))));
    assertTrue(token.getExpiresAtAsInstant().isBefore(
        before.plus(Duration.ofMinutes(16))));
  }
}
