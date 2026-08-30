package org.moxie.confer.proxy.workers.attestation;

import java.time.Instant;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.moxie.confer.proxy.workers.WorkerException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class DcapWorkerQuoteVerifierTest {

  private static final String MANIFEST = "{\"version\":1}";
  private static final String MANIFEST_BUNDLE = "{\"bundle\":true}";

  private byte[]                         reportData;
  private SigstoreWorkerReleaseVerifier releases;

  @BeforeEach
  void setUp() {
    reportData = new byte[64];
    for (int index = 0; index < reportData.length; index++) {
      reportData[index] = (byte) index;
    }
    releases = mock(SigstoreWorkerReleaseVerifier.class);
  }

  @ParameterizedTest
  @ValueSource(ints = {
      0x0000,
      0xa001,
      0xa002,
      0xa003,
      0xa007,
      0xa008,
      0xa009,
      0xa00a
  })
  void nonTerminalVerificationResultsAreAccepted(int verificationResult) {
    DcapWorkerQuoteVerifier verifier = verifier(
        result(0, 0, verificationResult));

    assertDoesNotThrow(() -> verifyAuthenticatedQuote(verifier));
  }

  @ParameterizedTest
  @ValueSource(ints = {0xa004, 0xa005, 0xa006, 0xa0ff, 0x1234})
  void terminalAndUnknownVerificationResultsAreRejected(int verificationResult) {
    DcapWorkerQuoteVerifier verifier = verifier(
        result(0, 0, verificationResult));

    assertThrows(
        WorkerException.class,
        () -> verifyAuthenticatedQuote(verifier));
  }

  @Test
  void nativeVerificationErrorIsRejected() {
    DcapWorkerQuoteVerifier verifier = verifier(result(0xe001, 0, 0));

    assertThrows(
        WorkerException.class,
        () -> verifyAuthenticatedQuote(verifier));
  }

  @Test
  void expiredCollateralIsRejected() {
    DcapWorkerQuoteVerifier verifier = verifier(result(0, 1, 0));

    assertThrows(
        WorkerException.class,
        () -> verifyAuthenticatedQuote(verifier));
  }

  @Test
  void currentTimeIsUsedForCollateralValidation() throws Exception {
    AtomicLong verificationTime = new AtomicLong();
    DcapWorkerQuoteVerifier verifier = new DcapWorkerQuoteVerifier(releases) {
      @Override
      VerificationResult verifyNative(byte[] quote, long timestamp) {
        verificationTime.set(timestamp);
        return result(0, 0, 0);
      }
    };

    long before = Instant.now().minusSeconds(1).getEpochSecond();
    verifyAuthenticatedQuote(verifier);
    long after = Instant.now().plusSeconds(1).getEpochSecond();

    assertTrue(verificationTime.get() >= before);
    assertTrue(verificationTime.get() <= after);
  }

  @Test
  void authenticatedMalformedQuoteIsRejected() {
    DcapWorkerQuoteVerifier verifier = verifier(result(0, 0, 0));

    assertThrows(
        WorkerException.class,
        () -> verifier.verify(
            new byte[636],
            new byte[64],
            MANIFEST,
            MANIFEST_BUNDLE));
  }

  @Test
  void signedReleaseDeterminesTrustedMeasurements() throws Exception {
    WorkerTdxClaims claims = claims(false, false, reportData);
    DcapWorkerQuoteVerifier verifier = verifier(claims);

    assertDoesNotThrow(() -> verifyQuote(verifier));
    verify(releases).verify(MANIFEST, MANIFEST_BUNDLE, claims);
  }

  @Test
  void debuggableQuoteIsRejected() {
    DcapWorkerQuoteVerifier verifier = verifier(
        claims(true, false, reportData));

    assertThrows(WorkerException.class, () -> verifyQuote(verifier));
  }

  @Test
  void migratableQuoteIsRejected() {
    DcapWorkerQuoteVerifier verifier = verifier(
        claims(false, true, reportData));

    assertThrows(WorkerException.class, () -> verifyQuote(verifier));
  }

  @Test
  void mismatchedReportDataIsRejected() {
    DcapWorkerQuoteVerifier verifier = verifier(
        claims(false, false, new byte[64]));

    assertThrows(WorkerException.class, () -> verifyQuote(verifier));
  }

  @ParameterizedTest
  @MethodSource("invalidReportData")
  void invalidReportDataEncodingIsRejected(String untrustedReportData) {
    DcapWorkerQuoteVerifier verifier = verifier(
        claimsWithReportData(untrustedReportData));

    assertThrows(WorkerException.class, () -> verifyQuote(verifier));
  }

  @Test
  void untrustedReleaseIsRejected() throws Exception {
    WorkerTdxClaims claims = claims(false, false, reportData);
    doThrow(new WorkerException("untrusted release"))
        .when(releases)
        .verify(MANIFEST, MANIFEST_BUNDLE, claims);
    DcapWorkerQuoteVerifier verifier = verifier(claims);

    assertThrows(WorkerException.class, () -> verifyQuote(verifier));
  }

  private DcapWorkerQuoteVerifier verifier(
      DcapWorkerQuoteVerifier.VerificationResult result)
  {
    return new DcapWorkerQuoteVerifier(releases) {
      @Override
      VerificationResult verifyNative(byte[] quote, long verificationTime) {
        return result;
      }
    };
  }

  private DcapWorkerQuoteVerifier verifier(WorkerTdxClaims claims) {
    return new DcapWorkerQuoteVerifier(releases) {
      @Override
      WorkerTdxClaims authenticate(byte[] quote) {
        return claims;
      }
    };
  }

  private void verifyQuote(DcapWorkerQuoteVerifier verifier)
      throws WorkerException
  {
    verifier.verify(
        new byte[] {1},
        reportData,
        MANIFEST,
        MANIFEST_BUNDLE);
  }

  private void verifyAuthenticatedQuote(DcapWorkerQuoteVerifier verifier)
      throws WorkerException
  {
    byte[] quote = TdxQuoteTest.quote();
    verifier.verify(
        quote,
        HexFormat.of().parseHex(TdxQuote.parse(quote).getClaims().reportData()),
        MANIFEST,
        MANIFEST_BUNDLE);
  }

  private static Stream<String> invalidReportData() {
    return Stream.of(
        (String) null,
        "00",
        "g".repeat(128));
  }

  private static DcapWorkerQuoteVerifier.VerificationResult result(
      int error,
      int collateralExpirationStatus,
      int verificationResult)
  {
    return new DcapWorkerQuoteVerifier.VerificationResult(
        error,
        collateralExpirationStatus,
        verificationResult);
  }

  private static WorkerTdxClaims claimsWithReportData(String reportData) {
    return new WorkerTdxClaims(
        hex('a'),
        hex('b'),
        hex('c'),
        hex('d'),
        reportData,
        false,
        false);
  }

  private static WorkerTdxClaims claims(boolean debuggable,
                                        boolean migratable,
                                        byte[] reportData)
  {
    return new WorkerTdxClaims(
        hex('a'),
        hex('b'),
        hex('c'),
        hex('d'),
        HexFormat.of().formatHex(reportData),
        debuggable,
        migratable);
  }

  private static String hex(char value) {
    return Character.toString(value).repeat(96);
  }
}
