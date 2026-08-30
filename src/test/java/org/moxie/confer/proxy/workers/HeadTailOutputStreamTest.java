package org.moxie.confer.proxy.workers;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HeadTailOutputStreamTest {

  @Test
  void returnsEmptyOutputBeforeAnythingIsWritten() {
    HeadTailOutputStream output = new HeadTailOutputStream(8, Long.MAX_VALUE);

    assertEquals("", output.getOutput());
    assertFalse(output.isTruncated());
    assertFalse(output.isLimitExceeded());
  }

  @Test
  void returnsCompleteOutputWithinTheLimit() throws Exception {
    HeadTailOutputStream output = new HeadTailOutputStream(8, Long.MAX_VALUE);

    output.write('a');
    byte[] remainder = "bcdefgh".getBytes(StandardCharsets.UTF_8);
    output.write(remainder, 0, remainder.length);

    assertEquals("abcdefgh", output.getOutput());
    assertFalse(output.isTruncated());
  }

  @Test
  void truncatesOneByteBeyondTheRetentionLimit() throws Exception {
    HeadTailOutputStream output = new HeadTailOutputStream(8, Long.MAX_VALUE);

    output.write("abcdefghi".getBytes(StandardCharsets.UTF_8));

    assertEquals("abcd\n[... 1 bytes omitted ...]\nfghi", output.getOutput());
    assertTrue(output.isTruncated());
  }

  @Test
  void preservesTheHeadAndTailWhenOutputExceedsTheLimit() throws Exception {
    HeadTailOutputStream output = new HeadTailOutputStream(8, Long.MAX_VALUE);
    byte[] first = "abcdef".getBytes(StandardCharsets.UTF_8);
    byte[] second = "ghijkl".getBytes(StandardCharsets.UTF_8);

    output.write(first, 0, first.length);
    output.write(second, 0, second.length);

    assertEquals("abcd\n[... 4 bytes omitted ...]\nijkl", output.getOutput());
    assertTrue(output.isTruncated());
  }

  @Test
  void preservesTheTailAcrossMultipleRingBufferWraps() throws Exception {
    HeadTailOutputStream output = new HeadTailOutputStream(8, Long.MAX_VALUE);

    output.write("abcdefghijklmnopqrstuvwxyz".getBytes(StandardCharsets.UTF_8));

    assertEquals("abcd\n[... 18 bytes omitted ...]\nwxyz", output.getOutput());
    assertTrue(output.isTruncated());
  }

  @Test
  void supportsOddAndSingleByteRetentionLimits() throws Exception {
    HeadTailOutputStream odd = new HeadTailOutputStream(3, Long.MAX_VALUE);
    HeadTailOutputStream one = new HeadTailOutputStream(1, Long.MAX_VALUE);

    odd.write("abcdef".getBytes(StandardCharsets.UTF_8));
    one.write("abc".getBytes(StandardCharsets.UTF_8));

    assertEquals("a\n[... 3 bytes omitted ...]\nef", odd.getOutput());
    assertEquals("\n[... 2 bytes omitted ...]\nc", one.getOutput());
  }

  @Test
  void writesOnlyTheRequestedSourceRange() throws Exception {
    HeadTailOutputStream output = new HeadTailOutputStream(8, Long.MAX_VALUE);
    byte[] source = "xxabcdefyy".getBytes(StandardCharsets.UTF_8);

    output.write(source, 2, 6);
    output.write(source, source.length, 0);

    assertEquals("abcdef", output.getOutput());
  }

  @Test
  void rejectsInvalidSourceRanges() {
    HeadTailOutputStream output = new HeadTailOutputStream(8, Long.MAX_VALUE);
    byte[] source = new byte[4];

    assertThrows(IndexOutOfBoundsException.class, () -> output.write(source, -1, 1));
    assertThrows(IndexOutOfBoundsException.class, () -> output.write(source, 0, -1));
    assertThrows(IndexOutOfBoundsException.class, () -> output.write(source, 3, 2));
    assertThrows(NullPointerException.class, () -> output.write(null, 0, 0));
  }

  @Test
  void retainsTheAcceptedPartOfABulkWriteThatExceedsTheTransportLimit() throws Exception {
    HeadTailOutputStream output = new HeadTailOutputStream(8, 6);
    byte[] source = "abcdefgh".getBytes(StandardCharsets.UTF_8);

    IOException error = assertThrows(IOException.class, () -> output.write(source, 0, source.length));

    assertEquals("Output limit exceeded", error.getMessage());
    assertEquals("abcdef", output.getOutput());
    assertFalse(output.isTruncated());
    assertTrue(output.isLimitExceeded());
    assertThrows(IOException.class, () -> output.write('i'));
    assertThrows(IOException.class, () -> output.write(source, 0, 1));
    output.write(source, 0, 0);
    assertEquals("abcdef", output.getOutput());
  }

  @Test
  void detectsOutputBeyondTheTransportLimit() throws Exception {
    HeadTailOutputStream output = new HeadTailOutputStream(8, 12);

    output.write("abcdefghijkl".getBytes(StandardCharsets.UTF_8), 0, 12);
    assertFalse(output.isLimitExceeded());

    assertThrows(IOException.class, () -> output.write('m'));
    assertTrue(output.isLimitExceeded());
  }

  @Test
  void rejectsOutputWhenTheTransportLimitIsZero() {
    HeadTailOutputStream output = new HeadTailOutputStream(8, 0);
    byte[] source = "a".getBytes(StandardCharsets.UTF_8);

    assertThrows(IOException.class, () -> output.write('a'));
    assertThrows(IOException.class, () -> output.write(source, 0, source.length));
    assertEquals("", output.getOutput());
    assertTrue(output.isLimitExceeded());
  }

  @Test
  void rejectsInvalidLimits() {
    assertThrows(IllegalArgumentException.class, () -> new HeadTailOutputStream(0, 1));
    assertThrows(IllegalArgumentException.class, () -> new HeadTailOutputStream(-1, 1));
    assertThrows(IllegalArgumentException.class, () -> new HeadTailOutputStream(1, -1));
  }

  @Test
  void writesTheLowEightBitsOfASingleByteValue() throws Exception {
    HeadTailOutputStream output = new HeadTailOutputStream(8, Long.MAX_VALUE);

    output.write(0x161);

    assertEquals("a", output.getOutput());
  }

  @Test
  void reportsReplacementCharactersWhenTruncationSplitsUtf8Characters() throws Exception {
    HeadTailOutputStream output = new HeadTailOutputStream(8, Long.MAX_VALUE);

    output.write("abc€012345€def".getBytes(StandardCharsets.UTF_8));

    assertEquals("abc�\n[... 10 bytes omitted ...]\n�def", output.getOutput());
  }

  @Test
  void closeUnblocksWaitersAndIsIdempotent() throws Exception {
    HeadTailOutputStream output   = new HeadTailOutputStream(8, Long.MAX_VALUE);
    CountDownLatch        entered = new CountDownLatch(1);
    CountDownLatch        exited  = new CountDownLatch(1);
    Thread waiter = Thread.ofVirtual().start(() -> {
      entered.countDown();
      output.awaitClose();
      exited.countDown();
    });

    assertTrue(entered.await(1, TimeUnit.SECONDS));
    try {
      assertFalse(exited.await(50, TimeUnit.MILLISECONDS));
    } finally {
      output.close();
    }
    output.close();

    assertTrue(exited.await(1, TimeUnit.SECONDS));
    waiter.join();
  }

  @Test
  void closeIsATerminalSnapshot() throws Exception {
    HeadTailOutputStream output = new HeadTailOutputStream(8, Long.MAX_VALUE);
    output.write("before".getBytes(StandardCharsets.UTF_8));

    output.close();
    String snapshot = output.getOutput();

    assertThrows(IOException.class, () -> output.write('!'));
    assertThrows(
        IOException.class,
        () -> output.write("after".getBytes(StandardCharsets.UTF_8)));
    assertEquals(snapshot, output.getOutput());
  }

  @Test
  void serializesConcurrentBulkWritesWithoutLosingBytes() throws Exception {
    String firstText  = "a".repeat(1_000);
    String secondText = "b".repeat(1_000);
    HeadTailOutputStream output = new HeadTailOutputStream(2_000, 2_000);
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);

    try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
      Future<Void> first = executor.submit(() -> {
        ready.countDown();
        start.await();
        output.write(firstText.getBytes(StandardCharsets.UTF_8));
        return null;
      });
      Future<Void> second = executor.submit(() -> {
        ready.countDown();
        start.await();
        output.write(secondText.getBytes(StandardCharsets.UTF_8));
        return null;
      });

      assertTrue(ready.await(1, TimeUnit.SECONDS));
      start.countDown();
      assertNull(first.get(1, TimeUnit.SECONDS));
      assertNull(second.get(1, TimeUnit.SECONDS));
    }

    String result = output.getOutput();
    assertTrue(result.equals(firstText + secondText) || result.equals(secondText + firstText));
    assertFalse(output.isTruncated());
    assertFalse(output.isLimitExceeded());
  }
}
