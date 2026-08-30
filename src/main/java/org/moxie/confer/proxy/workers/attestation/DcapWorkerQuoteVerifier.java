package org.moxie.confer.proxy.workers.attestation;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Set;
import org.moxie.confer.proxy.workers.WorkerException;
import org.moxie.confer.proxy.workers.WorkerQuoteVerifier;

public class DcapWorkerQuoteVerifier implements WorkerQuoteVerifier {

  private static final String QVL_LIBRARY = "libsgx_dcap_quoteverify.so.1";

  private static final int SGX_QL_SUCCESS = 0;
  private static final int REPORT_DATA_BYTES = 64;

  private static final Set<Integer> ACCEPTED_RESULTS = Set.of(
      0x0000,
      0xa001,
      0xa002,
      0xa003,
      0xa007,
      0xa008,
      0xa009,
      0xa00a);

  private final SigstoreWorkerReleaseVerifier releases;
  private MethodHandle                         verifyQuote;

  public DcapWorkerQuoteVerifier(SigstoreWorkerReleaseVerifier releases) {
    this.releases = releases;
  }

  @Override
  public void verify(byte[] quote,
                     byte[] expectedReportData,
                     String manifest,
                     String manifestBundle)
      throws WorkerException
  {
    WorkerTdxClaims claims = authenticate(quote);

    if (claims.debuggable()) {
      throw new WorkerException("Debuggable workers are not trusted");
    }

    if (claims.migratable()) {
      throw new WorkerException("Migratable workers are not trusted");
    }

    if (!MessageDigest.isEqual(expectedReportData, decodeReportData(claims.reportData()))) {
      throw new WorkerException("Worker REPORTDATA does not match");
    }

    releases.verify(manifest, manifestBundle, claims);
  }

  WorkerTdxClaims authenticate(byte[] quote) throws WorkerException {
    VerificationResult result = verifyNative(quote, Instant.now().getEpochSecond());

    if (result.getError() != SGX_QL_SUCCESS) {
      throw new WorkerException("Unable to verify worker TDX quote: " + hex(result.getError()));
    }

    if (result.getCollateralExpirationStatus() != 0) {
      throw new WorkerException("Worker TDX quote collateral is expired");
    }

    if (!ACCEPTED_RESULTS.contains(result.getQuoteVerificationResult())) {
      throw new WorkerException("Worker TDX quote is not trusted: " + hex(result.getQuoteVerificationResult()));
    }

    return TdxQuote.parse(quote).getClaims();
  }

  VerificationResult verifyNative(byte[] quote, long verificationTime)
      throws WorkerException
  {
    MethodHandle nativeVerifier = getVerifyQuote();

    try (Arena arena = Arena.ofConfined()) {
      MemorySegment nativeQuote = arena.allocate(quote.length);
      nativeQuote.copyFrom(MemorySegment.ofArray(quote));

      MemorySegment collateralExpirationStatus = arena.allocate(ValueLayout.JAVA_INT);
      MemorySegment quoteVerificationResult    = arena.allocate(ValueLayout.JAVA_INT);

      int result = (int) nativeVerifier.invokeExact(nativeQuote,
                                                    quote.length,
                                                    MemorySegment.NULL,
                                                    verificationTime,
                                                    collateralExpirationStatus,
                                                    quoteVerificationResult,
                                                    MemorySegment.NULL,
                                                    MemorySegment.NULL);

      return new VerificationResult(result,
                                    collateralExpirationStatus.get(ValueLayout.JAVA_INT, 0),
                                    quoteVerificationResult.get(ValueLayout.JAVA_INT, 0));
    } catch (RuntimeException | Error error) {
      throw error;
    } catch (Throwable error) {
      throw new WorkerException("Unable to verify worker TDX quote", error);
    }
  }

  private synchronized MethodHandle getVerifyQuote() {
    if (verifyQuote == null) {
      verifyQuote = loadVerifyQuote();
    }

    return verifyQuote;
  }

  private MethodHandle loadVerifyQuote() {
    Linker       linker = Linker.nativeLinker();
    SymbolLookup qvl    = SymbolLookup.libraryLookup(QVL_LIBRARY, Arena.global());

    return linker.downcallHandle(requireSymbol(qvl, "tee_verify_quote"),
                                 FunctionDescriptor.of(ValueLayout.JAVA_INT,
                                                       ValueLayout.ADDRESS,
                                                       ValueLayout.JAVA_INT,
                                                       ValueLayout.ADDRESS,
                                                       ValueLayout.JAVA_LONG,
                                                       ValueLayout.ADDRESS,
                                                       ValueLayout.ADDRESS,
                                                       ValueLayout.ADDRESS,
                                                       ValueLayout.ADDRESS));
  }

  private static byte[] decodeReportData(String value)
      throws WorkerException
  {
    if (value == null || value.length() != REPORT_DATA_BYTES * 2) {
      throw new WorkerException("Worker REPORTDATA is invalid");
    }

    try {
      return HexFormat.of().parseHex(value);
    } catch (IllegalArgumentException error) {
      throw new WorkerException("Worker REPORTDATA is invalid", error);
    }
  }

  private static MemorySegment requireSymbol(SymbolLookup library,
                                             String       name)
  {
    return library.find(name).orElseThrow(() -> new IllegalStateException("Intel DCAP symbol is unavailable: " + name));
  }

  private static String hex(int value) {
    return "0x" + Integer.toHexString(value);
  }

  static class VerificationResult {

    private final int error;
    private final int collateralExpirationStatus;
    private final int quoteVerificationResult;

    VerificationResult(int error,
                       int collateralExpirationStatus,
                       int quoteVerificationResult)
    {
      this.error                      = error;
      this.collateralExpirationStatus = collateralExpirationStatus;
      this.quoteVerificationResult    = quoteVerificationResult;
    }

    int getError() {
      return error;
    }

    int getCollateralExpirationStatus() {
      return collateralExpirationStatus;
    }

    int getQuoteVerificationResult() {
      return quoteVerificationResult;
    }
  }
}
