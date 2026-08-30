package org.moxie.confer.proxy.workers;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

class WorkerAttestationHandshake {

  private static final byte[] REPORT_DOMAIN =
      "confer.worker.ssh.prelude.v4".getBytes(StandardCharsets.US_ASCII);
  private static final byte[] SSH_ED25519 =
      "ssh-ed25519".getBytes(StandardCharsets.US_ASCII);
  private static final byte[] ED25519_KEY_PREFIX = getEd25519KeyPrefix();

  private static final int CHALLENGE_BYTES               = 32;
  private static final int ED25519_KEY_BLOB_BYTES        = 51;
  private static final int MAX_QUOTE_BYTES               = 64 * 1024;
  private static final int MAX_RELEASE_EVIDENCE_BYTES    = 32 * 1024;

  private final ObjectMapper        objectMapper;
  private final WorkerQuoteVerifier verifier;
  private final SecureRandom        random = new SecureRandom();

  WorkerAttestationHandshake(ObjectMapper        objectMapper,
                             WorkerQuoteVerifier verifier)
  {
    this.objectMapper = objectMapper;
    this.verifier     = verifier;
  }

  Attempt begin(byte[] clientKey)
      throws IOException
  {
    byte[] challenge = new byte[CHALLENGE_BYTES];
    random.nextBytes(challenge);

    byte[] request = objectMapper.writeValueAsBytes(new AttestationRequest(4, encodeUrl(challenge), encodeOpenSshKey(clientKey)));

    return new Attempt(request, challenge, clientKey);
  }

  private byte[] verifyAttestation(byte[] document,
                                   byte[] challenge,
                                   byte[] clientKey)
      throws IOException, WorkerException
  {
    AttestationResponse response = objectMapper.readValue(document, AttestationResponse.class);

    if (response == null) {
      throw new IOException("Worker attestation must be a JSON object");
    }

    if (response.version() != 4) {
      throw new IOException("Worker attestation version is invalid");
    }

    if (!"TDX".equals(response.platform())) {
      throw new IOException("Worker attestation platform is invalid");
    }

    if (!"SSH-2.0".equals(response.protocol())) {
      throw new IOException("Worker attestation protocol is invalid");
    }

    byte[] returnedChallenge = decodeUrl(response.challenge(), CHALLENGE_BYTES, "Challenge");

    if (!MessageDigest.isEqual(challenge, returnedChallenge)) {
      throw new IOException("Worker attestation challenge does not match");
    }

    byte[] returnedClientKey = decodeOpenSshKey(response.clientKey(), "Client key");

    if (!MessageDigest.isEqual(clientKey, returnedClientKey)) {
      throw new IOException("Worker attestation client key does not match");
    }

    byte[] hostKey        = decodeOpenSshKey(response.hostKey(), "Host key");
    byte[] quote          = decodeUrl(response.quote(), MAX_QUOTE_BYTES, "Quote");
    String manifest       = requireReleaseEvidence(response.manifest());
    String manifestBundle = requireReleaseEvidence(response.manifestBundle());

    verifier.verify(quote, getExpectedReportData(hostKey, clientKey, challenge), manifest, manifestBundle);

    return hostKey;
  }

  private static byte[] getExpectedReportData(byte[] hostKey,
                                              byte[] clientKey,
                                              byte[] challenge)
  {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-512");
      digest.update(REPORT_DOMAIN);
      digest.update(hostKey);
      digest.update(clientKey);
      digest.update(challenge);
      return digest.digest();
    } catch (NoSuchAlgorithmException error) {
      throw new IllegalStateException("SHA-512 is unavailable", error);
    }
  }

  private static byte[] decodeOpenSshKey(String value,
                                         String description)
      throws IOException
  {
    String prefix = "ssh-ed25519 ";

    if (value == null || !value.startsWith(prefix)) {
      throw new IOException(description + " is not SSH Ed25519");
    }

    byte[] key;

    try {
      key = Base64.getDecoder().decode(value.substring(prefix.length()));
    } catch (IllegalArgumentException error) {
      throw new IOException(description + " is not valid base64", error);
    }

    requireEd25519Key(key, description);
    return key;
  }

  private static String encodeOpenSshKey(byte[] key) {
    return "ssh-ed25519 " + Base64.getEncoder().encodeToString(key);
  }

  private static String encodeUrl(byte[] value) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
  }

  private static byte[] decodeUrl(String encoded,
                                  int    maximumBytes,
                                  String description)
      throws IOException
  {
    if (encoded == null || encoded.isEmpty()) {
      throw new IOException(description + " encoding is missing");
    }
    byte[] decoded;
    try {
      decoded = Base64.getUrlDecoder().decode(encoded);
    } catch (IllegalArgumentException error) {
      throw new IOException(description + " encoding is invalid", error);
    }
    if (decoded.length == 0
        || decoded.length > maximumBytes) {
      throw new IOException(description + " length is invalid");
    }
    return decoded;
  }

  private static void requireEd25519Key(byte[] key,
                                        String description)
      throws IOException
  {
    if (key.length != ED25519_KEY_BLOB_BYTES
        || !Arrays.equals(key, 0, ED25519_KEY_PREFIX.length,
                          ED25519_KEY_PREFIX, 0, ED25519_KEY_PREFIX.length)) {
      throw new IOException(description + " is not SSH Ed25519");
    }
  }

  private static String requireReleaseEvidence(String value)
      throws IOException
  {
    if (value == null
        || value.isEmpty()
        || value.getBytes(StandardCharsets.UTF_8).length
           > MAX_RELEASE_EVIDENCE_BYTES) {
      throw new IOException("Worker release evidence is invalid");
    }
    return value;
  }

  private static byte[] getEd25519KeyPrefix() {
    return ByteBuffer.allocate(Integer.BYTES + SSH_ED25519.length + Integer.BYTES)
                     .putInt(SSH_ED25519.length)
                     .put(SSH_ED25519)
                     .putInt(32)
                     .array();
  }

  class Attempt {

    private final byte[] request;
    private final byte[] challenge;
    private final byte[] clientKey;

    private Attempt(byte[] request,
                    byte[] challenge,
                    byte[] clientKey)
    {
      this.request        = request;
      this.challenge      = challenge;
      this.clientKey      = clientKey;
    }

    byte[] getRequest() {
      return request;
    }

    byte[] verify(byte[] response)
        throws IOException, WorkerException
    {
      return verifyAttestation(response, challenge, clientKey);
    }
  }

  private record AttestationRequest(int    version,
                                    String challenge,
                                    String clientKey) {}

  private record AttestationResponse(int    version,
                                     String platform,
                                     String protocol,
                                     String challenge,
                                     String hostKey,
                                     String clientKey,
                                     String quote,
                                     String manifest,
                                     String manifestBundle) {}
}
