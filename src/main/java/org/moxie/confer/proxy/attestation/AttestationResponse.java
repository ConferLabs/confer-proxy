package org.moxie.confer.proxy.attestation;

/**
 * Common interface for platform-specific attestation responses.
 */
public interface AttestationResponse {
  /**
   * Get the platform type identifier.
   *
   * @return Platform identifier (e.g., "TDX", "SEV-SNP")
   */
  String platform();

  /**
   * Get the attestation data.
   * For TDX: signed JWT
   * For SEV-SNP: base64-encoded raw attestation report
   *
   * @return Attestation data string
   */
  String attestation();

  /**
   * Get the Sigstore-signed manifest JSON.
   * Contains image version, proxy version, and TDX measurements.
   *
   * @return Manifest JSON string
   */
  String manifest();

  /**
   * Get the Sigstore bundle JSON.
   * Contains the signature, certificate, and Rekor inclusion proof.
   *
   * @return Manifest bundle JSON string
   */
  String manifestBundle();
}
