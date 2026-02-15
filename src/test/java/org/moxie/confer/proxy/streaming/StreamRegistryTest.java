package org.moxie.confer.proxy.streaming;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

class StreamRegistryTest {

  private StreamRegistry registry;

  @BeforeEach
  void setUp() {
    registry = new StreamRegistry();
  }

  @Test
  void createStream_returnsContext() throws IOException {
    ByteArrayOutputStream sink = new ByteArrayOutputStream();

    StreamContext ctx = registry.createStream(1L, sink);

    assertNotNull(ctx);
    assertEquals(1L, ctx.getRequestId());
  }

  @Test
  void createStream_flushesPendingChunks() throws IOException {
    // Buffer chunks before stream exists
    registry.handleChunk(1L, "A".getBytes(), 0, false);
    registry.handleChunk(1L, "B".getBytes(), 1, true);

    // Create stream - should flush pending chunks
    ByteArrayOutputStream sink = new ByteArrayOutputStream();
    StreamContext ctx = registry.createStream(1L, sink);

    assertEquals("AB", sink.toString());
    assertTrue(ctx.isCompleted());
  }

  @Test
  void handleChunk_writesToExistingStream() throws IOException {
    ByteArrayOutputStream sink = new ByteArrayOutputStream();
    registry.createStream(1L, sink);

    registry.handleChunk(1L, "hello".getBytes(), 0, false);

    assertEquals("hello", sink.toString());
  }

  @Test
  void handleChunk_buffersWhenStreamNotExists() throws IOException {
    // This should not throw - chunks are buffered
    registry.handleChunk(1L, "buffered".getBytes(), 0, true);

    // Later create the stream
    ByteArrayOutputStream sink = new ByteArrayOutputStream();
    registry.createStream(1L, sink);

    assertEquals("buffered", sink.toString());
  }

  @Test
  void handleChunk_tooManyChunks_throwsIOException() throws IOException {
    // Fill up to the limit (256 chunks)
    for (int i = 0; i < 256; i++) {
      registry.handleChunk(1L, "x".getBytes(), i, false);
    }

    // The 257th should throw
    assertThrows(IOException.class, () ->
        registry.handleChunk(1L, "overflow".getBytes(), 256, true));
  }

  @Test
  void handleChunk_tooManyStreams_evictsOldest() throws IOException {
    // Create 16 pending streams (the limit)
    for (long i = 1; i <= 16; i++) {
      registry.handleChunk(i, "data".getBytes(), 0, false);
    }

    // Adding 17th evicts stream 1
    registry.handleChunk(17L, "data".getBytes(), 0, false);

    // Stream 1's pending chunks were evicted
    ByteArrayOutputStream sink = new ByteArrayOutputStream();
    registry.createStream(1L, sink);
    assertEquals(0, sink.size());

    // Stream 17's chunks are still there
    ByteArrayOutputStream sink17 = new ByteArrayOutputStream();
    registry.createStream(17L, sink17);
    assertEquals("data", sink17.toString());
  }

  @Test
  void cancelStream_cancelsAndRemoves() throws IOException {
    ByteArrayOutputStream sink = new ByteArrayOutputStream();
    StreamContext ctx = registry.createStream(1L, sink);

    registry.cancelStream(1L);

    assertTrue(ctx.isCompleted());
  }

  @Test
  void cancelStream_unknownStream_doesNothing() {
    // Should not throw
    registry.cancelStream(999L);
  }

  @Test
  void cancelAll_cancelsAllStreams() throws IOException {
    ByteArrayOutputStream sink1 = new ByteArrayOutputStream();
    ByteArrayOutputStream sink2 = new ByteArrayOutputStream();
    StreamContext ctx1 = registry.createStream(1L, sink1);
    StreamContext ctx2 = registry.createStream(2L, sink2);

    registry.cancelAll();

    assertTrue(ctx1.isCompleted());
    assertTrue(ctx2.isCompleted());
  }

  @Test
  void cancelAll_clearsPendingChunks() throws IOException {
    // Buffer some chunks
    registry.handleChunk(1L, "pending".getBytes(), 0, false);

    registry.cancelAll();

    // Creating stream should have no pending chunks
    ByteArrayOutputStream sink = new ByteArrayOutputStream();
    registry.createStream(1L, sink);
    assertEquals(0, sink.size());
  }

  @Test
  void cancelAll_skipsAlreadyCompletedStreams() throws IOException {
    ByteArrayOutputStream sink = new ByteArrayOutputStream();
    StreamContext ctx = registry.createStream(1L, sink);

    // Complete the stream before cancelAll
    ctx.complete();

    // Should not throw or fail
    registry.cancelAll();

    assertTrue(ctx.isCompleted());
  }

  @Test
  void createStream_tooManyActiveStreams_throwsIOException() throws IOException {
    // Create 10 streams (the limit)
    for (long i = 1; i <= 10; i++) {
      registry.createStream(i, new ByteArrayOutputStream());
    }

    // The 11th should throw
    assertThrows(IOException.class, () ->
        registry.createStream(11L, new ByteArrayOutputStream()));
  }

  @Test
  void handleChunk_finalChunk_removesStreamFromRegistry() throws IOException {
    ByteArrayOutputStream sink = new ByteArrayOutputStream();
    registry.createStream(1L, sink);

    // Write final chunk
    registry.handleChunk(1L, "done".getBytes(), 0, true);

    // Stream should be removed - creating a new stream with same ID should work
    ByteArrayOutputStream newSink = new ByteArrayOutputStream();
    StreamContext newCtx = registry.createStream(1L, newSink);
    assertNotNull(newCtx);
  }

  @Test
  void handleChunk_nonFinalChunk_keepsStreamInRegistry() throws IOException {
    ByteArrayOutputStream sink = new ByteArrayOutputStream();
    registry.createStream(1L, sink);

    // Write non-final chunk
    registry.handleChunk(1L, "data".getBytes(), 0, false);

    // Stream should still exist - creating stream with same ID should fail due to limit
    // Fill up to 9 more streams
    for (long i = 2; i <= 10; i++) {
      registry.createStream(i, new ByteArrayOutputStream());
    }

    // The 11th should throw (stream 1 still active)
    assertThrows(IOException.class, () ->
        registry.createStream(11L, new ByteArrayOutputStream()));
  }

  @Test
  void handleChunk_completedStream_allowsNewStreamWithSameId() throws IOException {
    // Create and complete stream
    ByteArrayOutputStream sink1 = new ByteArrayOutputStream();
    registry.createStream(1L, sink1);
    registry.handleChunk(1L, "done".getBytes(), 0, true);

    assertEquals("done", sink1.toString());

    // Should be able to create new stream with same ID
    ByteArrayOutputStream sink2 = new ByteArrayOutputStream();
    StreamContext ctx2 = registry.createStream(1L, sink2);
    registry.handleChunk(1L, "new data".getBytes(), 0, true);

    assertEquals("new data", sink2.toString());
  }
}
