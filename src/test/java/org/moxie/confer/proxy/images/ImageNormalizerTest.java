package org.moxie.confer.proxy.images;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.SequenceInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImageNormalizerTest {

  private static final String   POOL_A = "a".repeat(32);
  private static final String   POOL_B = "b".repeat(32);
  private static final Duration LONG   = Duration.ofSeconds(10);

  private FakeNormalizerServer server;
  private ImageNormalizer      normalizer;

  @BeforeEach
  void setUp() throws IOException {
    server = new FakeNormalizerServer();
  }

  @AfterEach
  void tearDown() throws IOException {
    if (normalizer != null) {
      normalizer.close();
    }
    server.close();
  }

  @Test
  void streamsItsOwnPpmHeaderThenThePixels() throws Exception {
    normalizer = normalizer(LONG, LONG);

    ByteArrayOutputStream expected = new ByteArrayOutputStream();
    expected.write("P6\n1 1\n255\n".getBytes(StandardCharsets.US_ASCII));
    expected.write(new byte[] { 7, 8, 9 });
    assertArrayEquals(expected.toByteArray(), normalizeFully(POOL_A, "a"));
  }

  @Test
  void sendsAnImageAsItIsRead() throws Exception {
    normalizer = normalizer(LONG, LONG);
    StringBuilder large  = new StringBuilder();
    Random        random = new Random(1);
    while (large.length() < 300_000) {
      large.append((char) ('a' + random.nextInt(26)));
    }

    normalizeFully(POOL_A, large.toString());

    assertEquals(List.of(List.of(large.toString())), server.received());
  }

  @Test
  void usesOneConnectionPerPool() throws Exception {
    normalizer = normalizer(LONG, LONG);

    for (int index = 0; index < 5; index++) {
      normalizeFully(POOL_A, "a" + index);
    }
    normalizeConcurrently(POOL_A, 12);

    assertEquals(1, server.accepted());
  }

  @Test
  void neverSendsTwoPoolsImagesOnOneConnection() throws Exception {
    normalizer = normalizer(LONG, LONG);

    try (ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor()) {
      Future<?> a = threads.submit(() -> { normalizeConcurrently(POOL_A, 8); return null; });
      Future<?> b = threads.submit(() -> { normalizeConcurrently(POOL_B, 8); return null; });
      a.get(10, TimeUnit.SECONDS);
      b.get(10, TimeUnit.SECONDS);
    }

    for (List<String> images : server.received()) {
      assertTrue(images.stream().allMatch(name -> name.charAt(0) == images.getFirst().charAt(0)),
                 "one connection carried two pools' images: " + images);
    }
  }

  @Test
  void keepsAnotherPoolsConnectionThroughTheIdleGrace() throws Exception {
    normalizer = normalizer(LONG, LONG);

    normalizeFully(POOL_A, "a1");
    normalizeFully(POOL_B, "b1");
    normalizeFully(POOL_A, "a2");

    assertEquals(List.of(List.of("a1", "a2"), List.of("b1")), server.received());
    assertEquals(2, server.open());
  }

  @Test
  void closesAnotherPoolsConnectionIdlePastTheGraceWhenAConnectionIsCheckedOut() throws Exception {
    normalizer = normalizer(LONG, Duration.ofMillis(200));

    normalizeFully(POOL_A, "a1");
    Thread.sleep(300);
    normalizeFully(POOL_B, "b1");
    await(() -> server.open() == 1);
    assertEquals(1, normalizer.trackedConnections());

    normalizeFully(POOL_A, "a2");
    assertEquals(List.of(List.of("a1"), List.of("b1"), List.of("a2")), server.received());
  }

  @Test
  void keepsAnotherPoolsBusyConnectionWhenAConnectionIsCheckedOut() throws Exception {
    normalizer = normalizer(LONG, Duration.ZERO);
    CountDownLatch release = new CountDownLatch(1);
    server.behave((image, responder) -> {
      if (image.equals("held")) {
        release.await();
      }
      FakeNormalizerServer.ONE_PIXEL.respond(image, responder);
    });

    try (ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor()) {
      Future<byte[]> held = threads.submit(() -> normalizeFully(POOL_A, "held"));
      await(() -> server.received().size() == 1 && server.received().getFirst().size() == 1);

      normalizeFully(POOL_B, "b");
      assertEquals(2, normalizer.trackedConnections());
      assertEquals(2, server.open());

      release.countDown();
      held.get(5, TimeUnit.SECONDS);
    }
  }

  @Test
  void decodesAtMostFourOfAPoolsImagesAtOnce() throws Exception {
    normalizer = normalizer(LONG, LONG);
    CountDownLatch gate = new CountDownLatch(1);
    server.behave((image, responder) -> {
      gate.await();
      FakeNormalizerServer.ONE_PIXEL.respond(image, responder);
    });

    try (ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor()) {
      List<Future<byte[]>> results = new ArrayList<>();
      for (int index = 0; index < 12; index++) {
        String name = "a" + index;
        results.add(threads.submit(() -> normalizeFully(POOL_A, name)));
      }

      await(() -> server.peakInFlight() == NormalizerConnection.MAX_IN_FLIGHT);
      Thread.sleep(200);
      assertEquals(NormalizerConnection.MAX_IN_FLIGHT, server.peakInFlight(), "a fifth image started");

      gate.countDown();
      for (Future<byte[]> result : results) {
        result.get(5, TimeUnit.SECONDS);
      }
    }

    assertEquals(NormalizerConnection.MAX_IN_FLIGHT, server.peakInFlight());
    assertEquals(1, server.accepted());
  }

  @Test
  void deliversInterleavedResponsesToTheirOwnImages() throws Exception {
    normalizer = normalizer(LONG, LONG);
    CountDownLatch allArrived = new CountDownLatch(4);
    server.behave((image, responder) -> {
      allArrived.countDown();
      allArrived.await();

      byte    fill   = (byte) image.charAt(0);
      byte[]  pixels = new byte[256 * 256 * 3];
      java.util.Arrays.fill(pixels, fill);
      responder.header(256, 256);
      for (int offset = 0; offset < pixels.length; offset += 1000) {
        responder.pixels(java.util.Arrays.copyOfRange(pixels, offset, Math.min(pixels.length, offset + 1000)));
        Thread.yield();
      }
      responder.done();
    });

    try (ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor()) {
      List<Future<byte[]>> results = new ArrayList<>();
      for (String name : List.of("w", "x", "y", "z")) {
        results.add(threads.submit(() -> normalizeFully(POOL_A, name)));
      }

      for (int index = 0; index < 4; index++) {
        byte[] ppm      = results.get(index).get(5, TimeUnit.SECONDS);
        byte   expected = (byte) "wxyz".charAt(index);
        byte[] header   = "P6\n256 256\n255\n".getBytes(StandardCharsets.US_ASCII);
        assertEquals(header.length + 256 * 256 * 3, ppm.length);
        for (int offset = header.length; offset < ppm.length; offset++) {
          assertEquals(expected, ppm[offset], "a pixel from another image at " + offset);
        }
      }
    }
  }

  @Test
  void failsOnlyTheImageTheNormalizerRejects() throws Exception {
    normalizer = normalizer(LONG, LONG);
    server.behave((image, responder) -> {
      if (image.equals("bad")) {
        responder.failed();
      } else {
        FakeNormalizerServer.ONE_PIXEL.respond(image, responder);
      }
    });

    normalizeFully(POOL_A, "first");
    assertThrows(InvalidNormalizedImageException.class, () -> normalizeFully(POOL_A, "bad"));
    normalizeFully(POOL_A, "after");

    assertEquals(List.of(List.of("first", "bad", "after")), server.received());
  }

  @Test
  void carriesOnAfterAnImageThatCouldNotBeRead() throws Exception {
    normalizer = normalizer(LONG, LONG);
    InputStream failing = new SequenceInputStream(new ByteArrayInputStream("partial".getBytes(StandardCharsets.US_ASCII)), new InputStream() {
      @Override
      public int read() throws IOException {
        throw new IOException("Encrypted document authentication failed");
      }
    });

    assertThrows(IOException.class, () -> normalizer.normalize(POOL_A, failing));
    normalizeFully(POOL_A, "after");

    assertEquals(List.of(List.of("partial", "after")), server.received());
  }

  @Test
  void discardsTheRestOfAnAbandonedImage() throws Exception {
    normalizer = normalizer(LONG, LONG);
    server.behave((image, responder) -> {
      responder.header(64, 64);
      for (int chunk = 0; chunk < 12; chunk++) {
        responder.pixels(new byte[1024]);
      }
      responder.done();
    });

    normalizer.normalize(POOL_A, image("abandoned")).close();
    byte[] ppm = normalizeFully(POOL_A, "after");

    assertEquals("P6\n64 64\n255\n".length() + 64 * 64 * 3, ppm.length);
    assertEquals(1, server.accepted());
  }

  @Test
  void failsAnImageWhosePixelsAreCutShort() throws Exception {
    normalizer = normalizer(LONG, LONG);
    server.behave((image, responder) -> {
      responder.header(2, 1);
      responder.pixels(new byte[3]);
      responder.done();
    });

    try (NormalizedImageStream stream = normalizer.normalize(POOL_A, image("short"))) {
      assertThrows(IOException.class, () -> stream.writeTo(OutputStream.nullOutputStream()));
    }
  }

  @Test
  void endsTheConnectionOnAProtocolViolation() throws Exception {
    normalizer = normalizer(LONG, LONG);
    server.behave((image, responder) -> {
      if (image.equals("garbled")) {
        responder.message(99, new byte[0]);
      } else {
        FakeNormalizerServer.ONE_PIXEL.respond(image, responder);
      }
    });

    assertThrows(IOException.class, () -> normalizeFully(POOL_A, "garbled"));
    normalizeFully(POOL_A, "after");

    assertEquals(List.of(List.of("garbled"), List.of("after")), server.received());
  }

  @Test
  void failsAnImageThatMissesItsDeadlineAndEndsItsConnection() throws Exception {
    normalizer = normalizer(Duration.ofMillis(300), LONG);
    server.behave((image, responder) -> {
      if (image.equals("slow")) {
        Thread.sleep(2_000);
      }
      FakeNormalizerServer.ONE_PIXEL.respond(image, responder);
    });

    long started = System.nanoTime();
    assertThrows(IOException.class, () -> normalizer.normalize(POOL_A, image("slow")));
    assertTrue(Duration.ofNanos(System.nanoTime() - started).compareTo(Duration.ofSeconds(1)) < 0);

    normalizeFully(POOL_A, "after");
    assertEquals(2, server.accepted());
  }

  @Test
  void endsTheConnectionOfAStreamThatIsNeverReadOrClosed() throws Exception {
    normalizer = normalizer(Duration.ofMillis(300), LONG);

    // Its unread pixels hold up the connection's reader until the deadline ends the connection,
    // which ends the stream too, so the pool drops the connection without waiting out the grace.
    normalizer.normalize(POOL_A, image("forgotten"));
    await(() -> normalizer.trackedConnections() == 0);

    normalizeFully(POOL_B, "b");
    assertEquals(1, normalizer.trackedConnections());

    normalizeFully(POOL_A, "after");
    assertEquals(3, server.accepted());
  }

  @Test
  void failsWhenTheNormalizerIsUnavailable() throws Exception {
    normalizer = normalizer(LONG, LONG);
    server.close();

    assertThrows(IOException.class, () -> normalizer.normalize(POOL_A, image("a")));
    assertEquals(0, normalizer.trackedConnections());
  }

  private ImageNormalizer normalizer(Duration imageDeadline, Duration idleGrace) {
    return new ImageNormalizer(server.socket(), imageDeadline, idleGrace);
  }

  /** The PPM vLLM would receive, read to the end. */
  private byte[] normalizeFully(String pool, String name) throws Exception {
    try (NormalizedImageStream stream = normalizer.normalize(pool, image(name))) {
      ByteArrayOutputStream ppm = new ByteArrayOutputStream();
      stream.writeTo(ppm);
      return ppm.toByteArray();
    }
  }

  private void normalizeConcurrently(String pool, int count) throws Exception {
    try (ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor()) {
      List<Future<byte[]>> results = new ArrayList<>();
      for (int index = 0; index < count; index++) {
        String name = pool.charAt(0) + "-concurrent-" + index;
        results.add(threads.submit(() -> {
          byte[] ppm = normalizeFully(pool, name);
          Thread.sleep(20);
          return ppm;
        }));
      }
      for (Future<byte[]> result : results) {
        result.get(10, TimeUnit.SECONDS);
      }
    }
  }

  private static InputStream image(String name) {
    return new ByteArrayInputStream(name.getBytes(StandardCharsets.US_ASCII));
  }

  private static void await(BooleanSupplier condition) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (!condition.getAsBoolean()) {
      if (System.nanoTime() > deadline) {
        throw new AssertionError("condition not reached within 5 seconds");
      }
      Thread.sleep(10);
    }
  }
}
