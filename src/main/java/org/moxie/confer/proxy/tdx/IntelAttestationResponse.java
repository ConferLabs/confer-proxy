package org.moxie.confer.proxy.tdx;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.moxie.confer.proxy.attestation.AttestationResponse;

public class IntelAttestationResponse implements AttestationResponse {
  private final String attestation;
  private final String manifest;
  private final String manifestBundle;

  public IntelAttestationResponse(String attestation, String manifest, String manifestBundle) {
    this.attestation = attestation;
    this.manifest = manifest;
    this.manifestBundle = manifestBundle;
  }

  @Override
  @JsonProperty("platform")
  public String platform() {
    return "TDX";
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
