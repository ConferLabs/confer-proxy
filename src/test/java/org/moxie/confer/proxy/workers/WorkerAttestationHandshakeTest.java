package org.moxie.confer.proxy.workers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkerAttestationHandshakeTest {

  private static final byte[] CHALLENGE = sequence(32, 0);
  private static final byte[] CLIENT_KEY = ed25519Key((byte) 7);
  private static final byte[] HOST_KEY = ed25519Key((byte) 9);
  private static final byte[] QUOTE = "tdx-quote".getBytes(StandardCharsets.US_ASCII);
  private static final String MANIFEST = "{\"version\":1}";
  private static final String MANIFEST_BUNDLE = "{\"bundle\":true}";

  @Test
  void generatesRequestBeforeVerifyingAttestation()
      throws Exception
  {
    AtomicReference<byte[]> reportData = new AtomicReference<>();
    AtomicReference<byte[]> challenge = new AtomicReference<>();
    AtomicBoolean verifierCalled = new AtomicBoolean();

    byte[] hostKey = verifyResponse(
        validDocument(),
        challenge,
        (quote, expectedReportData, manifest, manifestBundle) -> {
          assertArrayEquals(QUOTE, quote);
          assertEquals(MANIFEST, manifest);
          assertEquals(MANIFEST_BUNDLE, manifestBundle);
          verifierCalled.set(true);
          reportData.set(expectedReportData);
        });

    assertTrue(verifierCalled.get());
    assertArrayEquals(HOST_KEY, hostKey);
    assertArrayEquals(
        expectedReportData(challenge.get()),
        reportData.get());
  }

  @Test
  void stopsBeforeSshHandoffWhenQuoteVerificationFails() throws Exception {
    assertThrows(
        WorkerException.class,
        () -> verifyResponse(
            validDocument(),
            (quote, reportData, manifest, manifestBundle) -> {
              throw new WorkerException("untrusted worker");
            }));
  }

  @Test
  void matchesTheVersionFourReportDataVector() throws Exception {
    assertEquals(
        "6ecc465db853453cb0845d319a3c66f0dc2fb355aad55b1c8933153b533706ad"
        + "cfcb475b3eb4d264b0d540c30c9255c8198e07b2fca8e2dafdfe750c2fda66cc",
        HexFormat.of().formatHex(expectedReportData()));
  }

  @Test
  void rejectsAChallengeMismatchBeforeQuoteVerification() throws Exception {
    String document = validDocument().replace(
        encodeUrl(CHALLENGE),
        encodeUrl(sequence(32, 1)));
    AtomicBoolean verifierCalled = new AtomicBoolean();

    assertThrows(
        IOException.class,
        () -> verifyResponse(
            document,
            (quote, reportData, manifest, manifestBundle) ->
                verifierCalled.set(true)));

    assertFalse(verifierCalled.get());
  }

  @Test
  void rejectsAClientKeyMismatchBeforeQuoteVerification() throws Exception {
    String document = validDocument().replace(
        openSshKey(CLIENT_KEY),
        openSshKey(ed25519Key((byte) 8)));

    assertThrows(
        IOException.class,
        () -> verifyResponse(
            document,
            (quote, reportData, manifest, manifestBundle) -> {}));
  }

  @Test
  void rejectsInvalidHostKeys() throws Exception {
    String document = validDocument().replace(
        openSshKey(HOST_KEY),
        openSshKey(HOST_KEY) + " comment");

    assertThrows(
        IOException.class,
        () -> verifyResponse(
            document,
            (quote, reportData, manifest, manifestBundle) -> {}));
  }

  @Test
  void rejectsUnknownFields() throws Exception {
    String unknown = validDocument().replace(
        "\"version\":4,",
        "\"version\":4,\"extra\":true,");

    assertHandshakeFailure(unknown);
  }

  @ParameterizedTest
  @CsvSource({
      "platform, TDX, SNP",
      "protocol, SSH-2.0, SSH-1.5"
  })
  void rejectsUnexpectedPlatformAndProtocol(String field,
                                            String expected,
                                            String untrusted)
      throws Exception
  {
    String document = validDocument().replace(
        "\"" + field + "\":\"" + expected + "\"",
        "\"" + field + "\":\"" + untrusted + "\"");

    assertHandshakeFailure(document);
  }

  @Test
  void requiresVersionFourAndRejectsTheLegacyIndexField() throws Exception {
    String versionThree = validDocument().replace("\"version\":4,",
                                                  "\"version\":3,");
    String legacyIndex = validDocument().replace(
        "\"quote\":\"" + encodeUrl(QUOTE) + "\"",
        "\"quote\":\"" + encodeUrl(QUOTE) + "\","
        + "\"pythonIndexUrl\":\"https://example.test/simple/\"");

    assertHandshakeFailure(versionThree);
    assertHandshakeFailure(legacyIndex);
  }

  @Test
  void rejectsMissingOrOversizedReleaseEvidence() throws Exception {
    String missing = validDocument().replace(
        ",\"manifest\":\"{\\\"version\\\":1}\"",
        "");
    String empty = validDocument().replace(
        "\"manifest\":\"{\\\"version\\\":1}\"",
        "\"manifest\":\"\"");
    String oversized = validDocument().replace(
        "{\\\"version\\\":1}",
        "x".repeat(32 * 1024 + 1));

    assertHandshakeFailure(missing);
    assertHandshakeFailure(empty);
    assertHandshakeFailure(oversized);
  }

  private static WorkerAttestationHandshake handshake(
      WorkerQuoteVerifier verifier)
  {
    return new WorkerAttestationHandshake(
        new ObjectMapper(),
        verifier);
  }

  private static void assertHandshakeFailure(String document) throws Exception {
    AtomicBoolean verifierCalled = new AtomicBoolean();

    assertThrows(
        IOException.class,
        () -> verifyResponse(
            document,
            (quote, reportData, manifest, manifestBundle) ->
                verifierCalled.set(true)));

    assertFalse(verifierCalled.get());
  }

  private static byte[] verifyResponse(String              document,
                                       WorkerQuoteVerifier verifier)
      throws IOException, WorkerException
  {
    return verifyResponse(document, null, verifier);
  }

  private static byte[] verifyResponse(
      String                  document,
      AtomicReference<byte[]> observedChallenge,
      WorkerQuoteVerifier     verifier)
      throws IOException, WorkerException
  {
    WorkerAttestationHandshake.Attempt attempt =
        handshake(verifier).begin(CLIENT_KEY);
    JsonNode parsed = new ObjectMapper().readTree(attempt.getRequest());
    assertEquals(3, parsed.size());
    assertEquals(4, parsed.path("version").intValue());
    assertEquals(openSshKey(CLIENT_KEY), parsed.path("clientKey").textValue());
    String encodedChallenge = parsed.path("challenge").textValue();
    byte[] challenge = Base64.getUrlDecoder().decode(encodedChallenge);
    assertEquals(32, challenge.length);
    if (observedChallenge != null) {
      observedChallenge.set(challenge);
    }

    return attempt.verify(
        document == null
            ? null
            : document.replace(encodeUrl(CHALLENGE), encodedChallenge)
                      .getBytes(StandardCharsets.UTF_8));
  }

  private static String validDocument() {
    return "{"
           + "\"version\":4,"
           + "\"platform\":\"TDX\","
           + "\"protocol\":\"SSH-2.0\","
           + "\"challenge\":\"" + encodeUrl(CHALLENGE) + "\","
           + "\"hostKey\":\"" + openSshKey(HOST_KEY) + "\","
           + "\"clientKey\":\"" + openSshKey(CLIENT_KEY) + "\","
           + "\"quote\":\"" + encodeUrl(QUOTE) + "\","
           + "\"manifest\":\"{\\\"version\\\":1}\","
           + "\"manifestBundle\":\"{\\\"bundle\\\":true}\""
           + "}";
  }

  private static byte[] expectedReportData() throws Exception {
    return expectedReportData(CHALLENGE);
  }

  private static byte[] expectedReportData(byte[] challenge)
      throws Exception
  {
    MessageDigest digest = MessageDigest.getInstance("SHA-512");
    digest.update("confer.worker.ssh.prelude.v4"
                      .getBytes(StandardCharsets.US_ASCII));
    digest.update(HOST_KEY);
    digest.update(CLIENT_KEY);
    digest.update(challenge);
    return digest.digest();
  }

  private static byte[] ed25519Key(byte value) {
    byte[] algorithm = "ssh-ed25519".getBytes(StandardCharsets.US_ASCII);
    byte[] key = new byte[32];
    Arrays.fill(key, value);
    return ByteBuffer.allocate(51)
                     .putInt(algorithm.length)
                     .put(algorithm)
                     .putInt(key.length)
                     .put(key)
                     .array();
  }

  private static String openSshKey(byte[] key) {
    return "ssh-ed25519 " + Base64.getEncoder().encodeToString(key);
  }

  private static String encodeUrl(byte[] value) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
  }

  private static byte[] sequence(int length, int start) {
    byte[] value = new byte[length];
    for (int index = 0; index < value.length; index++) {
      value[index] = (byte) (start + index);
    }
    return value;
  }
}
