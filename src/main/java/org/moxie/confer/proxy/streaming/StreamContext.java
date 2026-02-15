package org.moxie.confer.proxy.streaming;

import java.io.IOException;
import java.io.OutputStream;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Manages the state of a single streaming upload.
 * Receives chunks and writes them to a sink (e.g., PipedOutputStream).
 */
public class StreamContext {

  private static final int  MAX_OUT_OF_ORDER_CHUNKS = 64;
  private static final long MAX_TOTAL_BYTES         = 50 * 1024 * 1024; // 50 MB

  private final SortedMap<Integer, PendingChunk> pendingChunks = new TreeMap<>();
  private final ReentrantLock                    lock          = new ReentrantLock();

  private final long         requestId;
  private final OutputStream sink;

  private volatile boolean completed         = false;
  private          int     nextExpectedSeq   = 0;
  private          long    totalBytesWritten = 0;

  private record PendingChunk(byte[] data, boolean isFinal) {}

  public StreamContext(long requestId, OutputStream sink) {
    this.requestId = requestId;
    this.sink      = sink;
  }

  /**
   * Write a chunk of data to the stream with sequence number for ordering.
   * Chunks arriving out of order are buffered and written when their turn comes.
   *
   * @param data           The chunk data
   * @param sequenceNumber The sequence number (0-indexed)
   * @param isFinal        True if this is the last chunk
   * @throws IOException           if writing fails
   * @throws IllegalStateException if stream is already completed
   */
  public void write(byte[] data, int sequenceNumber, boolean isFinal) throws IOException {
    lock.lock();

    try {
      if (completed) {
        throw new IllegalStateException("Stream " + requestId + " is already completed");
      }

      if (sequenceNumber > nextExpectedSeq) {
        // Buffer out-of-order chunk
        if (pendingChunks.size() >= MAX_OUT_OF_ORDER_CHUNKS) {
          throw new IOException("Too many out-of-order chunks for stream " + requestId);
        }
        pendingChunks.put(sequenceNumber, new PendingChunk(data, isFinal));
        return;
      }

      if (sequenceNumber < nextExpectedSeq) {
        // Ignore duplicate
        return;
      }

      PendingChunk current = new PendingChunk(data, isFinal);

      do {
        totalBytesWritten += current.data().length;
        if (totalBytesWritten > MAX_TOTAL_BYTES) {
          throw new IOException("Stream " + requestId + " exceeded maximum size of " + MAX_TOTAL_BYTES + " bytes");
        }
        sink.write(current.data());
        nextExpectedSeq++;

        if (current.isFinal()) {
          complete();
          return;
        }

        current = pendingChunks.remove(nextExpectedSeq);
      } while (current != null);
    } finally {
      lock.unlock();
    }
  }

  /**
   * Mark the stream as complete and close the sink.
   */
  public void complete() throws IOException {
    lock.lock();

    try {
      if (completed) {
        return;
      }

      completed = true;
      sink.close();
    } finally {
      lock.unlock();
    }
  }

  /**
   * Cancel the stream and close the sink.
   */
  public void cancel() {
    lock.lock();

    try {
      if (completed) {
        return;
      }

      completed = true;
      try {
        sink.close();
      } catch (IOException ignored) {
        // Best effort close on cancel
      }
    } finally {
      lock.unlock();
    }
  }

  /**
   * Returns true if the stream has been completed or cancelled.
   */
  public boolean isCompleted() {
    return completed;
  }

  public long getRequestId() {
    return requestId;
  }
}
