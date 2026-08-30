package org.moxie.confer.proxy.workers.attestation;

import org.moxie.confer.proxy.workers.WorkerException;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.HexFormat;

class TdxQuote {

  private static final int VERSION_OFFSET = 0;
  private static final int TEE_TYPE_OFFSET = 4;
  private static final int TD_ATTRIBUTES_OFFSET = 168;
  private static final int MRTD_OFFSET = 184;
  private static final int RTMR0_OFFSET = 376;
  private static final int RTMR1_OFFSET = 424;
  private static final int RTMR2_OFFSET = 472;
  private static final int REPORT_DATA_OFFSET = 568;
  private static final int SIGNATURE_LENGTH_OFFSET = 632;
  private static final int SIGNATURE_OFFSET = 636;

  private static final int QUOTE_VERSION = 4;
  private static final long TDX_TEE_TYPE = 0x81L;
  private static final long DEBUG_ATTRIBUTE = 1L;
  private static final long MIGRATABLE_ATTRIBUTE = 1L << 29;
  private static final int MEASUREMENT_BYTES = 48;
  private static final int REPORT_DATA_BYTES = 64;

  private final byte[] quote;

  private TdxQuote(byte[] quote) {
    this.quote = quote;
  }

  static TdxQuote parse(byte[] quote) throws WorkerException {
    if (quote == null || quote.length < SIGNATURE_OFFSET) {
      throw new WorkerException("Worker TDX quote is invalid");
    }

    ByteBuffer body = ByteBuffer.wrap(quote).order(ByteOrder.LITTLE_ENDIAN);
    int version = Short.toUnsignedInt(body.getShort(VERSION_OFFSET));
    long teeType = Integer.toUnsignedLong(body.getInt(TEE_TYPE_OFFSET));
    long signatureLength = Integer.toUnsignedLong(
        body.getInt(SIGNATURE_LENGTH_OFFSET));
    long signedQuoteLength = SIGNATURE_OFFSET + signatureLength;
    if (version != QUOTE_VERSION
        || teeType != TDX_TEE_TYPE
        || signedQuoteLength > quote.length
        || !hasOnlyZeroPadding(quote, (int) signedQuoteLength)) {
      throw new WorkerException("Worker TDX quote is invalid");
    }

    return new TdxQuote(Arrays.copyOf(quote, (int) signedQuoteLength));
  }

  WorkerTdxClaims getClaims() {
    ByteBuffer body = ByteBuffer.wrap(quote).order(ByteOrder.LITTLE_ENDIAN);
    long attributes = body.getLong(TD_ATTRIBUTES_OFFSET);
    return new WorkerTdxClaims(
        hex(MRTD_OFFSET, MEASUREMENT_BYTES),
        hex(RTMR0_OFFSET, MEASUREMENT_BYTES),
        hex(RTMR1_OFFSET, MEASUREMENT_BYTES),
        hex(RTMR2_OFFSET, MEASUREMENT_BYTES),
        hex(REPORT_DATA_OFFSET, REPORT_DATA_BYTES),
        (attributes & DEBUG_ATTRIBUTE) != 0,
        (attributes & MIGRATABLE_ATTRIBUTE) != 0);
  }

  private String hex(int offset, int length) {
    return HexFormat.of().formatHex(
        Arrays.copyOfRange(quote, offset, offset + length));
  }

  private static boolean hasOnlyZeroPadding(byte[] quote, int offset) {
    for (int index = offset; index < quote.length; index++) {
      if (quote[index] != 0) {
        return false;
      }
    }
    return true;
  }
}
