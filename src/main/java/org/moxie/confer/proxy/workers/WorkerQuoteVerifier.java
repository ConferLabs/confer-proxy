package org.moxie.confer.proxy.workers;

@FunctionalInterface
public interface WorkerQuoteVerifier {

  /**
   * Verifies the quote chain, exact REPORTDATA, the exact RTMR3 that records
   * only this reservation, TDX security claims, and the Sigstore-signed worker
   * release measurements.
   */
  void verify(byte[] quote,
              byte[] expectedReportData,
              byte[] expectedRtmr3,
              String manifest,
              String manifestBundle)
      throws WorkerException;
}
