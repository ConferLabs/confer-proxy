package org.moxie.confer.proxy.tdx;

import com.auth0.jwt.JWT;
import com.auth0.jwt.exceptions.JWTDecodeException;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.moxie.confer.proxy.attestation.AttestationException;
import org.moxie.confer.proxy.attestation.AttestationResponse;
import org.moxie.confer.proxy.attestation.AttestationService;
import org.moxie.confer.proxy.config.Config;
import org.moxie.confer.proxy.config.ManifestInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.time.Instant;
import java.util.Map;

public class TdxAttestationService extends AttestationService {

  private static final Logger log = LoggerFactory.getLogger(TdxAttestationService.class);

  private final ManifestInfo manifestInfo;

  public TdxAttestationService(KeyPair serverKeyPair, HttpClient httpClient, ObjectMapper mapper, Config config, ManifestInfo manifestInfo) {
    super(serverKeyPair, httpClient, mapper, config);
    this.manifestInfo = manifestInfo;
  }

  @Override
  protected AttestationResponse generateAttestation() throws AttestationException {
    log.info("Generating TDX attestation");

    try {
      AttestationData         attestation = getAttestationData(getRawPublicKey());
      IntelAttestationRequest jwtRequest  = new IntelAttestationRequest(attestation.getBase64Quote());

      HttpResponse<String> jwtResponse = httpClient.send(HttpRequest.newBuilder(URI.create(config.getItaUrl() + "/appraisal/v1/attest"))
                                                                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(jwtRequest)))
                                                                    .header("Accept", "application/json")
                                                                    .header("Content-Type", "application/json")
                                                                    .header("x-api-key", config.getItaApiKey())
                                                                    .build(),
                                                         HttpResponse.BodyHandlers.ofString());

      String              body        = jwtResponse.body();
      Map<String, Object> itaResponse = mapper.readValue(body, Map.class);
      String              jwt         = (String) itaResponse.get("token");

      return new IntelAttestationResponse(jwt, manifestInfo.manifest(), manifestInfo.manifestBundle());
    } catch (IOException | InterruptedException e) {
      throw new AttestationException(e);
    }
  }

  @Override
  protected boolean needsRefresh(AttestationResponse attestation) {
    if (attestation == null || attestation.attestation() == null) return true;

    try {
      DecodedJWT decoded    = JWT.decode(attestation.attestation());
      long       expiration = decoded.getClaims().get("exp").asLong();

      log.debug("TDX attestation token expiration: {}", expiration);

      return Instant.ofEpochSecond(expiration).isBefore(Instant.now().plusSeconds(60));
    } catch (JWTDecodeException e) {
      log.warn("TDX Attestation Token Malformed", e);
      return true;
    }
  }

  @Override
  public boolean isPlatformSupported() {
    return Files.exists(Path.of("/dev/tdx_guest"));
  }

  @Override
  public String getPlatformType() {
    return "TDX";
  }
}
