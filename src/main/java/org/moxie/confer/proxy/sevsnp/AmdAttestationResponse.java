package org.moxie.confer.proxy.sevsnp;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.moxie.confer.proxy.attestation.AttestationResponse;

public class AmdAttestationResponse implements AttestationResponse {

  private final String attestation;
  private final String manifest;
  private final String manifestBundle;

  public AmdAttestationResponse(String attestation, String manifest, String manifestBundle) {
    this.attestation = attestation;
    this.manifest = manifest;
    this.manifestBundle = manifestBundle;
  }

  @Override
  @JsonProperty("platform")
  public String platform() {
    return "SEV-SNP";
  }

  @Override
  @JsonProperty("attestation")
  public String attestation() {
    return attestation;
  }

  @Override
  @JsonProperty("manifest")
  public String manifest() {
    return manifest;
  }

  @Override
  @JsonProperty("manifestBundle")
  public String manifestBundle() {
    return manifestBundle;
  }
}
