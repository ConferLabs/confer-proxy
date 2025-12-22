package org.moxie.confer.proxy.producers;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import org.moxie.confer.proxy.attestation.AttestationService;
import org.moxie.confer.proxy.config.Config;
import org.moxie.confer.proxy.config.ManifestInfo;
import org.moxie.confer.proxy.sevsnp.SevSnpAttestationService;
import org.moxie.confer.proxy.tdx.TdxAttestationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.http.HttpClient;
import java.security.KeyPair;

/**
 * CDI producer for platform-specific attestation services.
 * Detects the available TEE platform and returns the appropriate implementation.
 */
@ApplicationScoped
public class AttestationServiceProducer {

  private static final Logger log = LoggerFactory.getLogger(AttestationServiceProducer.class);

  @Inject
  @Named("noiseServerKeyPair")
  KeyPair serverKeyPair;

  @Inject
  HttpClient httpClient;

  @Inject
  ObjectMapper mapper;

  @Inject
  Config config;

  @Inject
  ManifestInfo manifestInfo;

  /**
   * Produces the appropriate attestation service based on platform detection.
   * Fails closed if no TEE is available (throws IllegalStateException).
   *
   * @return Platform-specific attestation service
   * @throws IllegalStateException if no supported TEE platform is detected
   */
  @Produces
  @ApplicationScoped
  public AttestationService produceAttestationService() {
    TdxAttestationService tdxService = new TdxAttestationService(serverKeyPair, httpClient, mapper, config, manifestInfo);

    if (tdxService.isPlatformSupported()) {
      log.info("TDX platform detected");
      return tdxService;
    }

    SevSnpAttestationService sevSnpService = new SevSnpAttestationService(serverKeyPair, httpClient, mapper, config, manifestInfo);

    if (sevSnpService.isPlatformSupported()) {
      log.info("SEV-SNP platform detected");
      return sevSnpService;
    }

    throw new IllegalStateException(
      "No supported TEE platform detected. " +
      "Expected /dev/tdx_guest (TDX) or /dev/sev-guest (SEV-SNP). " +
      "Attestation is required for security."
    );
  }
}
