package org.moxie.confer.proxy.controllers;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Response;
import org.moxie.confer.proxy.attestation.AttestationException;
import org.moxie.confer.proxy.attestation.AttestationResponse;
import org.moxie.confer.proxy.attestation.AttestationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ApplicationScoped
@Path("/v1/ping")
public class PingController {

  private static final Logger log = LoggerFactory.getLogger(PingController.class);

  @Inject
  AttestationService attestationService;

  @GET
  public Response getPing() {
    try {
      AttestationResponse response = attestationService.getSignedAttestation();
      if (response == null || response.attestation() == null) {
        log.error("[ALERT] Attestation health check failed: attestation response is null");
        return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                       .entity("Attestation unavailable")
                       .build();
      }
      return Response.ok("PONG").build();
    } catch (AttestationException e) {
      log.error("[ALERT] Attestation health check failed: unable to get signed attestation", e);
      return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                     .entity("Attestation unavailable")
                     .build();
    }
  }
}
