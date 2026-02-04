package org.moxie.confer.proxy.streaming;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

class StreamContextTest {

  private ByteArrayOutputStream sink;
  private StreamContext context;

  @BeforeEach
  void setUp() {
    sink = new ByteArrayOutputStream();
    context = new StreamContext(1L, sink);
  }

  @Test
  void write_writesDataToSink() throws IOException {
    context.write("hello".getBytes(), 0, false);

    assertEquals("hello", sink.toString());
  }

  @Test
  void write_afterComplete_throwsIllegalState() throws IOException {
    context.write("data".getBytes(), 0, true);

    assertThrows(IllegalStateException.class, () ->
        context.write("more".getBytes(), 1, false));
  }

  @Test
  void write_outOfOrder_buffersAndWritesInSequence() throws IOException {
    // Send chunks out of order
    context.write("C".getBytes(), 2, true);
    context.write("A".getBytes(), 0, false);
    context.write("B".getBytes(), 1, false);

    assertEquals("ABC", sink.toString());
    assertTrue(context.isCompleted());
  }

  @Test
  void write_duplicateSequence_isIgnored() throws IOException {
    context.write("A".getBytes(), 0, false);
    context.write("A-duplicate".getBytes(), 0, false);
    context.write("B".getBytes(), 1, true);

    assertEquals("AB", sink.toString());
  }

  @Test
  void write_finalChunk_completesStream() throws IOException {
    context.write("done".getBytes(), 0, true);

    assertTrue(context.isCompleted());
  }

  @Test
  void complete_closeSink() throws IOException {
    context.write("data".getBytes(), 0, false);
    context.complete();

    assertTrue(context.isCompleted());
  }

  @Test
  void complete_calledTwice_isIdempotent() throws IOException {
    context.complete();
    context.complete();

    assertTrue(context.isCompleted());
  }

  @Test
  void cancel_closesStream() {
    context.cancel();

    assertTrue(context.isCompleted());
  }

  @Test
  void cancel_calledTwice_isIdempotent() {
    context.cancel();
    context.cancel();

    assertTrue(context.isCompleted());
  }

  @Test
  void cancel_afterComplete_doesNothing() throws IOException {
    context.complete();
    context.cancel();

    assertTrue(context.isCompleted());
  }

  @Test
  void getRequestId_returnsCorrectId() {
    assertEquals(1L, context.getRequestId());
  }

  @Test
  void complete_sinkCloseThrows_throwsIOException() {
    ByteArrayOutputStream failingSink = new ByteArrayOutputStream() {
      @Override
      public void close() throws IOException {
        throw new IOException("Close failed");
      }
    };
    StreamContext ctx = new StreamContext(2L, failingSink);

    assertThrows(IOException.class, ctx::complete);
    assertTrue(ctx.isCompleted());
  }

  @Test
  void cancel_sinkCloseThrows_stillCompletes() {
    ByteArrayOutputStream failingSink = new ByteArrayOutputStream() {
      @Override
      public void close() throws IOException {
        throw new IOException("Close failed");
      }
    };
    StreamContext ctx = new StreamContext(3L, failingSink);

    ctx.cancel();

    assertTrue(ctx.isCompleted());
  }

  @Test
  void write_exceedsMaxSize_throwsIOException() throws IOException {
    // 50 MB limit, write chunks until we exceed it
    byte[] chunk = new byte[1024 * 1024]; // 1 MB chunks

    // Write 50 chunks (50 MB) - should succeed
    for (int i = 0; i < 50; i++) {
      context.write(chunk, i, false);
    }

    // 51st chunk should fail
    IOException ex = assertThrows(IOException.class, () ->
        context.write(chunk, 50, false));
    assertTrue(ex.getMessage().contains("exceeded maximum size"));
  }
}
