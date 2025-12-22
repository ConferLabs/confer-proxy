package org.moxie.confer.proxy.sevsnp;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.moxie.confer.proxy.attestation.AttestationService;
import org.moxie.confer.proxy.attestation.AttestationException;
import org.moxie.confer.proxy.attestation.AttestationResponse;
import org.moxie.confer.proxy.config.Config;
import org.moxie.confer.proxy.config.ManifestInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.time.Instant;

/**
 * SEV-SNP attestation service that generates raw attestation reports.
 */
public class SevSnpAttestationService extends AttestationService {

  private static final Logger log = LoggerFactory.getLogger(SevSnpAttestationService.class);

  private static final long CACHE_DURATION_SECONDS = 60;

  private final ManifestInfo manifestInfo;

  public SevSnpAttestationService(KeyPair serverKeyPair, HttpClient httpClient, ObjectMapper mapper, Config config, ManifestInfo manifestInfo) {
    super(serverKeyPair, httpClient, mapper, config);
    this.manifestInfo = manifestInfo;
  }

  private Instant attestationGeneratedAt;

  @Override
  protected AttestationResponse generateAttestation() throws AttestationException {
    log.info("Generating SEV-SNP attestation report");

    AttestationData attestation  = getAttestationData(getRawPublicKey());
    String          base64Report = attestation.getBase64Quote();

    this.attestationGeneratedAt = Instant.now();

    return new AmdAttestationResponse(base64Report, manifestInfo.manifest(), manifestInfo.manifestBundle());
  }

  @Override
  protected boolean needsRefresh(AttestationResponse attestation) {
    if (attestation == null || attestationGeneratedAt == null) {
      return true;
    }

    long    secondsSinceGeneration = Instant.now().getEpochSecond() - attestationGeneratedAt.getEpochSecond();
    boolean needsRefresh           = secondsSinceGeneration >= CACHE_DURATION_SECONDS;

    if (needsRefresh) {
      log.debug("SEV-SNP attestation report is {} seconds old, refreshing", secondsSinceGeneration);
    }

    return needsRefresh;
  }

  @Override
  public boolean isPlatformSupported() {
    return Files.exists(Path.of("/dev/sev-guest"));
  }

  @Override
  public String getPlatformType() {
    return "SEV-SNP";
  }
}
