package org.moxie.confer.proxy.streaming;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;

/**
 * Tracks active streams for a single WebSocket connection.
 * Each WebSocket connection should have its own StreamRegistry instance.
 */
public class StreamRegistry {

  private static final Logger log = LoggerFactory.getLogger(StreamRegistry.class);

  private static final int MAX_ACTIVE_STREAMS    = 10;
  private static final int MAX_PENDING_STREAMS   = 16;
  private static final int MAX_CHUNKS_PER_STREAM = 256;

  private final Object lock = new Object();

  private final Map<Long, StreamContext>       streams       = new HashMap<>();
  private final Map<Long, Queue<PendingChunk>> pendingChunks = new LinkedHashMap<>() {
    @Override
    protected boolean removeEldestEntry(Map.Entry<Long, Queue<PendingChunk>> eldest) {
      if (size() > MAX_PENDING_STREAMS) {
        log.warn("Evicting pending chunks for stream {} (too many pending streams)", eldest.getKey());
        return true;
      }
      return false;
    }
  };

  private record PendingChunk(byte[] data, int sequenceNumber, boolean isFinal) {}

  /**
   * Create a new stream for the given request ID.
   * Any chunks that arrived before the stream was created will be flushed.
   *
   * @param requestId The request ID
   * @param sink      The output stream to write chunks to
   * @return The created StreamContext
   * @throws IOException if flushing pending chunks fails
   */
  public StreamContext createStream(long requestId, OutputStream sink) throws IOException {
    StreamContext ctx = new StreamContext(requestId, sink);
    Queue<PendingChunk> pending;

    synchronized (lock) {
      if (streams.size() >= MAX_ACTIVE_STREAMS) {
        throw new IOException("Too many active streams");
      }
      streams.put(requestId, ctx);
      pending = pendingChunks.remove(requestId);
    }

    if (pending != null) {
      for (PendingChunk chunk : pending) {
        ctx.write(chunk.data(), chunk.sequenceNumber(), chunk.isFinal());
      }
    }

    return ctx;
  }

  /**
   * Handle a chunk for a stream.
   * If the stream doesn't exist yet, the chunk is buffered until the stream is created.
   *
   * @param requestId      The request ID
   * @param data           The chunk data
   * @param sequenceNumber The sequence number for ordering
   * @param isFinal        True if this is the last chunk
   * @throws IOException if writing fails
   */
  public void handleChunk(long requestId, byte[] data, int sequenceNumber, boolean isFinal) throws IOException {
    StreamContext ctx;

    synchronized (lock) {
      ctx = streams.get(requestId);

      if (ctx == null) {
        Queue<PendingChunk> queue = pendingChunks.computeIfAbsent(requestId, k -> new LinkedList<>());

        if (queue.size() >= MAX_CHUNKS_PER_STREAM) {
          pendingChunks.remove(requestId);
          log.warn("Too many pending chunks for stream {}, dropping all", requestId);
          throw new IOException("Too many pending chunks");
        }

        queue.add(new PendingChunk(data, sequenceNumber, isFinal));
        return;
      }
    }

    ctx.write(data, sequenceNumber, isFinal);

    // Remove from registry when stream completes
    if (ctx.isCompleted()) {
      synchronized (lock) {
        streams.remove(requestId);
      }
    }
  }

  /**
   * Cancel a specific stream.
   */
  public void cancelStream(long requestId) {
    StreamContext ctx;

    synchronized (lock) {
      ctx = streams.remove(requestId);
      pendingChunks.remove(requestId);
    }

    if (ctx != null) {
      ctx.cancel();
    }
  }

  /**
   * Cancel all streams (e.g., when WebSocket connection closes).
   */
  public void cancelAll() {
    List<StreamContext> toCancel;

    synchronized (lock) {
      pendingChunks.clear();
      toCancel = new ArrayList<>(streams.values());
      streams.clear();
    }

    for (StreamContext ctx : toCancel) {
      if (!ctx.isCompleted()) {
        ctx.cancel();
      }
    }
  }

}
