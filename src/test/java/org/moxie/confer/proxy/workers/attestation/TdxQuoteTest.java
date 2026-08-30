package org.moxie.confer.proxy.workers.attestation;

import org.junit.jupiter.api.Test;
import org.moxie.confer.proxy.workers.WorkerException;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TdxQuoteTest {

  @Test
  void extractsTdxReportBody() throws Exception {
    byte[] quote = quote();
    fill(quote, 184, 48, 0x11);
    fill(quote, 376, 48, 0x22);
    fill(quote, 424, 48, 0x33);
    fill(quote, 472, 48, 0x44);
    fill(quote, 568, 64, 0x55);

    WorkerTdxClaims claims = TdxQuote.parse(quote).getClaims();

    assertEquals("11".repeat(48), claims.mrtd());
    assertEquals("22".repeat(48), claims.rtmr0());
    assertEquals("33".repeat(48), claims.rtmr1());
    assertEquals("44".repeat(48), claims.rtmr2());
    assertEquals("55".repeat(64), claims.reportData());
    assertFalse(claims.debuggable());
    assertFalse(claims.migratable());
  }

  @Test
  void securityAttributesAreExtracted() throws Exception {
    byte[] quote = quote();
    ByteBuffer.wrap(quote)
              .order(ByteOrder.LITTLE_ENDIAN)
              .putLong(168, 1L | (1L << 29));

    WorkerTdxClaims claims = TdxQuote.parse(quote).getClaims();

    assertTrue(claims.debuggable());
    assertTrue(claims.migratable());
  }

  @Test
  void unsupportedVersionIsRejected() {
    byte[] quote = quote();
    ByteBuffer.wrap(quote).order(ByteOrder.LITTLE_ENDIAN).putShort(0, (short) 5);

    assertThrows(WorkerException.class, () -> TdxQuote.parse(quote));
  }

  @Test
  void nonTdxQuoteIsRejected() {
    byte[] quote = quote();
    ByteBuffer.wrap(quote).order(ByteOrder.LITTLE_ENDIAN).putInt(4, 0);

    assertThrows(WorkerException.class, () -> TdxQuote.parse(quote));
  }

  @Test
  void truncatedQuoteIsRejected() {
    assertThrows(WorkerException.class, () -> TdxQuote.parse(new byte[635]));
  }

  @Test
  void inconsistentSignatureLengthIsRejected() {
    byte[] quote = quote();
    ByteBuffer.wrap(quote).order(ByteOrder.LITTLE_ENDIAN).putInt(632, 5);

    assertThrows(WorkerException.class, () -> TdxQuote.parse(quote));
  }

  @Test
  void zeroPaddedQuoteBufferIsAccepted() throws Exception {
    byte[] quote = quote();
    byte[] padded = new byte[8000];
    System.arraycopy(quote, 0, padded, 0, quote.length);

    assertDoesNotThrow(() -> TdxQuote.parse(padded));
  }

  @Test
  void nonzeroQuotePaddingIsRejected() {
    byte[] quote = quote();
    byte[] padded = new byte[8000];
    System.arraycopy(quote, 0, padded, 0, quote.length);
    padded[padded.length - 1] = 1;

    assertThrows(WorkerException.class, () -> TdxQuote.parse(padded));
  }

  static byte[] quote() {
    byte[] quote = new byte[640];
    ByteBuffer body = ByteBuffer.wrap(quote).order(ByteOrder.LITTLE_ENDIAN);
    body.putShort(0, (short) 4);
    body.putInt(4, 0x81);
    body.putInt(632, quote.length - 636);
    return quote;
  }

  private static void fill(byte[] value,
                           int    offset,
                           int    length,
                           int    byteValue)
  {
    for (int index = offset; index < offset + length; index++) {
      value[index] = (byte) byteValue;
    }
  }
}
