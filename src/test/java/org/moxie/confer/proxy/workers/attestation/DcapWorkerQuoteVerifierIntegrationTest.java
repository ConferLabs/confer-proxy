package org.moxie.confer.proxy.workers.attestation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.moxie.confer.proxy.workers.WorkerException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

class DcapWorkerQuoteVerifierIntegrationTest {

  private static final int QUOTE_SIGNATURE_OFFSET = 636;

  @Test
  void authenticatesAPublicTdxQuoteWithTheSystemQvl()
      throws IOException, WorkerException
  {
    assumeSupportedPlatform();

    byte[] quote = quote();
    DcapWorkerQuoteVerifier verifier = verifier();
    WorkerTdxClaims claims = TdxQuote.parse(quote).getClaims();

    assertDoesNotThrow(() -> verify(verifier, quote));
    assertDoesNotThrow(() -> verify(verifier, quote));
    assertEquals(
        "dae67181d3d65e073ad8f95b7907d5e927bfe9761c9ff3e9b89734a45d8954dba41394c7717cb2735396c1d04231f94a",
        claims.mrtd());
    assertEquals(
        "3fa2f61f395b7f5feefb4ec2df61297f109ad8abcd6410c1b7df60f21f37b19297fc35e544039c7e1edece752afd17f6",
        claims.rtmr0());
    assertEquals(
        "f62dbc072bd5d3f3438b7b35c39a727f5aea2ffc2473f43723953f530daf62504f0a7944aa62c41a86e8a878c2b122c1",
        claims.rtmr1());
    assertEquals(
        "4969684dc87381fc3b3134176c8d8806eaf0a901859f5f70cfae8d17714b46c10a8de219048c9fc09f11f381a6fbe7c1",
        claims.rtmr2());
  }

  @ParameterizedTest
  @ValueSource(ints = {184, 376, 424, 472, 568, QUOTE_SIGNATURE_OFFSET})
  void rejectsEveryAuthenticatedPartOfAModifiedPublicTdxQuote(int offset)
      throws IOException
  {
    assumeSupportedPlatform();

    byte[] quote = quote();
    quote[offset] ^= 1;
    DcapWorkerQuoteVerifier verifier = verifier();

    assertThrows(WorkerException.class, () -> verify(verifier, quote));
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {
      "empty",
      "truncated header",
      "truncated report body",
      "missing signature length",
      "truncated signature",
      "unsupported quote version",
      "invalid signature length",
      "trailing garbage",
      "random bytes"
  })
  void rejectsMalformedQuotesWithoutCrashing(String description)
      throws IOException
  {
    assumeSupportedPlatform();

    byte[] malformedQuote = malformedQuote(description, quote());
    DcapWorkerQuoteVerifier verifier = verifier();

    assertThrows(
        WorkerException.class,
        () -> verifier.authenticate(malformedQuote));
  }

  @Test
  void authenticatesConcurrentQuotesWithOneLoadedQvl() throws Exception {
    assumeSupportedPlatform();

    byte[] quote = quote();
    DcapWorkerQuoteVerifier verifier = verifier();

    try (ExecutorService executor = Executors.newFixedThreadPool(4)) {
      List<Future<Void>> checks = new ArrayList<>();
      for (int index = 0; index < 4; index++) {
        checks.add(executor.submit(() -> {
          verify(verifier, quote);
          return null;
        }));
      }
      for (Future<Void> check : checks) {
        check.get();
      }
    }
  }

  private static DcapWorkerQuoteVerifier verifier() {
    return new DcapWorkerQuoteVerifier(mock(SigstoreWorkerReleaseVerifier.class));
  }

  private static void verify(DcapWorkerQuoteVerifier verifier,
                             byte[]                  quote)
      throws WorkerException
  {
    WorkerTdxClaims claims = TdxQuote.parse(quote).getClaims();
    verifier.verify(
        quote,
        HexFormat.of().parseHex(claims.reportData()),
        "{}",
        "{}");
  }

  private static void assumeSupportedPlatform() {
    Assumptions.assumeTrue(
        Boolean.getBoolean("dcap.integration.enabled"),
        "Native DCAP integration test is not enabled");
    Assumptions.assumeTrue(
        "Linux".equals(System.getProperty("os.name")),
        "Intel DCAP runtime requires Linux");
    Assumptions.assumeTrue(
        "amd64".equals(System.getProperty("os.arch")),
        "Intel DCAP runtime requires AMD64");
    assertNull(System.getenv("LD_LIBRARY_PATH"));
    assertNull(System.getenv("QCNL_CONF_PATH"));
    assertNull(System.getenv("XDG_CACHE_HOME"));
  }

  private static byte[] quote() throws IOException {
    return Files.readAllBytes(Path.of(requireProperty("dcap.integration.quote")));
  }

  private static byte[] malformedQuote(String description, byte[] quote) {
    int signatureLength = (quote[632] & 0xff)
        | (quote[633] & 0xff) << 8
        | (quote[634] & 0xff) << 16
        | (quote[635] & 0xff) << 24;
    return switch (description) {
      case "empty" -> new byte[0];
      case "truncated header" -> Arrays.copyOf(quote, 47);
      case "truncated report body" -> Arrays.copyOf(quote, 631);
      case "missing signature length" -> Arrays.copyOf(quote, 635);
      case "truncated signature" ->
          Arrays.copyOf(quote, 636 + signatureLength - 1);
      case "unsupported quote version" -> {
        quote[0] = 5;
        yield quote;
      }
      case "invalid signature length" -> {
        Arrays.fill(quote, 632, 636, (byte) 0xff);
        yield quote;
      }
      case "trailing garbage" -> {
        byte[] malformed = Arrays.copyOf(quote, quote.length + 1);
        malformed[malformed.length - 1] = 1;
        yield malformed;
      }
      case "random bytes" -> {
        Arrays.fill(quote, (byte) 0xa5);
        yield quote;
      }
      default -> throw new IllegalArgumentException(description);
    };
  }

  private static String requireProperty(String name) {
    String value = System.getProperty(name);
    if (value == null || value.isBlank()) {
      throw new IllegalStateException("Missing system property: " + name);
    }
    return value;
  }
}
