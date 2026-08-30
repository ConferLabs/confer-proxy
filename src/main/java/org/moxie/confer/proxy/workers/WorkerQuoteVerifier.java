package org.moxie.confer.proxy.workers;

@FunctionalInterface
public interface WorkerQuoteVerifier {

  /**
   * Verifies the quote chain, exact REPORTDATA, TDX security claims, and the
   * Sigstore-signed worker release measurements.
   */
  void verify(byte[] quote,
              byte[] expectedReportData,
              String manifest,
              String manifestBundle)
      throws WorkerException;
}
