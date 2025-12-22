package org.moxie.confer.proxy.auth;

import com.auth0.jwt.JWTVerifier;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.interfaces.DecodedJWT;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

public class JWT {

  private final String jwtSecret;

  public JWT(String jwtSecret) {
    this.jwtSecret = jwtSecret;
  }

  public DecodedJWT verify(String jwt) throws JWTVerificationException {
    JWTVerifier verifier = com.auth0.jwt.JWT.require(Algorithm.HMAC256(jwtSecret))
                                            .withIssuer("kerf")
                                            .build();

    return verifier.verify(jwt);
  }

  public String generate(UUID userId) {
    return com.auth0.jwt.JWT.create()
              .withSubject(userId.toString())
              .withIssuer("kerf")
              .withExpiresAt(Instant.now().plus(Duration.ofMinutes(15)))
              .sign(Algorithm.HMAC256(jwtSecret));
  }

}
