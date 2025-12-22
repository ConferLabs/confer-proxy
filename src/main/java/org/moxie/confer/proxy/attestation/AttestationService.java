package org.moxie.confer.proxy.attestation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.moxie.confer.proxy.config.Config;
import org.moxie.confer.proxy.tdx.QuoteGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.http.HttpClient;
import java.security.KeyPair;
import java.util.Objects;

/**
 * Abstract base class for platform-specific attestation services.
 * Handles shared functionality like caching, expiration, and public key extraction.
 * Subclasses implement platform-specific quote generation and attestation verification.
 */
public abstract class AttestationService {

  private static final Logger log = LoggerFactory.getLogger(AttestationService.class);

  protected final KeyPair      serverKeyPair;
  protected final HttpClient   httpClient;
  protected final ObjectMapper mapper;
  protected final Config       config;

  private AttestationResponse cachedAttestation;

  protected AttestationService(KeyPair serverKeyPair, HttpClient httpClient, ObjectMapper mapper, Config config) {
    this.serverKeyPair = serverKeyPair;
    this.httpClient    = httpClient;
    this.mapper        = mapper;
    this.config        = config;
  }

  /**
   * Get a signed attestation response from the attestation service.
   * Uses cached response if available and not expired, otherwise generates a new one.
   *
   * @return Attestation response containing JWT token
   */
  public synchronized AttestationResponse getSignedAttestation() throws AttestationException {
    if (!needsRefresh(cachedAttestation)) {
      log.debug("Using cached {} attestation", getPlatformType());
      return cachedAttestation;
    }

    log.info("Generating new {} attestation", getPlatformType());
    cachedAttestation = generateAttestation();
    return cachedAttestation;
  }

  /**
   * Get the raw server public key used in the Noise protocol.
   * This key is embedded in the attestation report_data for binding.
   *
   * @return 32-byte X25519 public key
   */
  public byte[] getRawPublicKey() {
    if (serverKeyPair.getPublic() instanceof java.security.interfaces.XECPublicKey xecKey) {
      byte[] uArr = xecKey.getU().toByteArray();
      byte[] raw  = new byte[32];

      // Reverse byte order: BigInteger is big-endian, X25519 uses little-endian
      for (int i = 0; i < uArr.length && i < 32; i++) {
        raw[i] = uArr[uArr.length - 1 - i];
      }

      return raw;
    }

    throw new IllegalStateException("Expected XECPublicKey but got: " + serverKeyPair.getPublic().getClass());
  }

  /**
   * Get the raw server private key used in the Noise protocol.
   *
   * @return 32-byte X25519 private key
   */
  public byte[] getRawPrivateKey() {
    if (serverKeyPair.getPrivate() instanceof java.security.interfaces.XECPrivateKey xecKey) {
      return xecKey.getScalar().orElseThrow(() ->
        new IllegalStateException("Cannot extract scalar from XECPrivateKey")
      );
    }

    throw new IllegalStateException("Expected XECPrivateKey but got: " + serverKeyPair.getPrivate().getClass());
  }

  /**
   * Check if the cached attestation needs to be refreshed.
   * Platform-specific implementations define what "needs refresh" means.
   *
   * @param attestation Cached attestation response
   * @return true if attestation should be regenerated
   */
  protected abstract boolean needsRefresh(AttestationResponse attestation);

  /**
   * Generate a new attestation response.
   *
   * @return Attestation response containing JWT token
   */
  protected abstract AttestationResponse generateAttestation() throws AttestationException;

  /**
   * Check if the current platform supports this attestation type.
   *
   * @return true if the TEE is available and supported
   */
  public abstract boolean isPlatformSupported();

  /**
   * Get the platform type identifier for this attestation service.
   *
   * @return Platform identifier (e.g., "TDX", "SEV-SNP")
   */
  public abstract String getPlatformType();

  /**
   * Create 64-byte report data containing the public key.
   * Used by both TDX and SEV-SNP attestation.
   *
   * @param publicKey 32-byte X25519 public key
   * @return 64-byte report data with public key in first 32 bytes
   */
  protected byte[] createReportData(byte[] publicKey) {
    byte[] reportData = new byte[64];
    System.arraycopy(publicKey, 0, reportData, 0, publicKey.length);
    return reportData;
  }

  /**
   * Get attestation data by generating a quote using the TSM interface.
   *
   * @param publicKey Server's X25519 public key
   * @return Attestation data containing the quote and public key
   * @throws AttestationException if quote generation fails
   */
  protected AttestationData getAttestationData(byte[] publicKey) throws AttestationException {
    try {
      Objects.requireNonNull(publicKey, "Public key cannot be null");

      byte[]         reportData     = createReportData(publicKey);
      QuoteGenerator quoteGenerator = new QuoteGenerator(reportData);
      byte[]         quote          = quoteGenerator.generateQuote();

      return new AttestationData(quote, publicKey);
    } catch (IOException e) {
      throw new AttestationException(e);
    }
  }

  /**
   * Data holder for attestation quote and public key.
   */
  public record AttestationData(byte[] quote, byte[] publicKey) {
    public String getBase64Quote() {
      return new String(java.util.Base64.getEncoder().encode(quote));
    }

    public String getBase64Key() {
      return new String(java.util.Base64.getEncoder().encode(publicKey));
    }
  }
}
