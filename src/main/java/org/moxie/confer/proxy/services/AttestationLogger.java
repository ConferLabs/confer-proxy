package org.moxie.confer.proxy.services;

import com.auth0.jwt.JWT;
import com.auth0.jwt.interfaces.DecodedJWT;
import io.helidon.config.Config;
import io.helidon.microprofile.cdi.RuntimeStart;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.moxie.confer.proxy.attestation.AttestationException;
import org.moxie.confer.proxy.attestation.AttestationResponse;
import org.moxie.confer.proxy.attestation.AttestationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ApplicationScoped
public class AttestationLogger {

  private static final Logger log = LoggerFactory.getLogger(AttestationLogger.class);

  @Inject
  AttestationService attestationService;

  void onStartup(@Observes @RuntimeStart Config config) {
    try {
      AttestationResponse response = attestationService.getSignedAttestation();

      if ("TDX".equals(response.platform())) {
        DecodedJWT jwt = JWT.decode(response.attestation());
        log.info("=== TDX Measurements ===");
        logClaim(jwt, "tdx_mrtd", "MRTD");
        logClaim(jwt, "tdx_rtmr0", "RTMR[0]");
        logClaim(jwt, "tdx_rtmr1", "RTMR[1]");
        logClaim(jwt, "tdx_rtmr2", "RTMR[2]");
        logClaim(jwt, "tdx_rtmr3", "RTMR[3]");
        log.info("========================");
      } else if ("SEV-SNP".equals(response.platform())) {
        byte[] report = java.util.Base64.getDecoder().decode(response.attestation());
        // SEV-SNP report: measurement is 48 bytes at offset 144
        byte[] measurement = new byte[48];
        System.arraycopy(report, 144, measurement, 0, 48);
        log.info("=== SEV-SNP Measurements ===");
        log.info("Launch Measurement: {}", bytesToHex(measurement));
        log.info("============================");
      }

    } catch (AttestationException e) {
      log.error("[ALERT] Attestation startup failed: unable to get signed attestation", e);
    }
  }

  private void logClaim(DecodedJWT jwt, String claimName, String displayName) {
    var claim = jwt.getClaim(claimName);
    if (!claim.isNull()) {
      log.info("{}: {}", displayName, claim.asString());
    }
  }

  private static String bytesToHex(byte[] bytes) {
    StringBuilder sb = new StringBuilder();
    for (byte b : bytes) {
      sb.append(String.format("%02x", b));
    }
    return sb.toString();
  }
}