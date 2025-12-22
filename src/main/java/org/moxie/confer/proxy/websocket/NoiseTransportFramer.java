package org.moxie.confer.proxy.websocket;

import com.google.protobuf.ByteString;
import confer.NoiseTransport.NoiseTransportFrame;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * NoiseTransportFramer handles chunking and reassembly of large messages
 * using the NoiseTransportFrame protobuf format. This provides a clean
 * framing layer between application messages and the Noise encrypted transport.
 */
public class NoiseTransportFramer {
  // Max Noise payload: 65535 - 16 (auth tag) = 65519 bytes
  // NoiseTransportFrame overhead: ~30 bytes (chunk_id + indices + protobuf framing)
  private static final int MAX_NOISE_PAYLOAD = 65519;
  private static final int FRAME_OVERHEAD = 30;
  private static final int MAX_CHUNK_PAYLOAD = MAX_NOISE_PAYLOAD - FRAME_OVERHEAD;

  /**
   * Split a message into frames that fit within the Noise payload limit.
   * Returns an array of encoded frames ready to send over the transport.
   */
  public static List<byte[]> encodeFrames(byte[] messageBytes) {
    // Generate random 64-bit chunk ID
    long chunkId = ThreadLocalRandom.current().nextLong();

    if (messageBytes.length <= MAX_CHUNK_PAYLOAD) {
      // Single frame
      NoiseTransportFrame frame = NoiseTransportFrame.newBuilder()
          .setChunkId(chunkId)
          .setChunkIndex(0)
          .setTotalChunks(1)
          .setPayload(ByteString.copyFrom(messageBytes))
          .build();

      List<byte[]> result = new ArrayList<>(1);
      result.add(frame.toByteArray());
      return result;
    }

    // Multiple frames needed
    int totalChunks = (int) Math.ceil((double) messageBytes.length / MAX_CHUNK_PAYLOAD);
    List<byte[]> frames = new ArrayList<>(totalChunks);

    for (int i = 0; i < totalChunks; i++) {
      int start = i * MAX_CHUNK_PAYLOAD;
      int end = Math.min(start + MAX_CHUNK_PAYLOAD, messageBytes.length);
      int length = end - start;

      byte[] chunk = new byte[length];
      System.arraycopy(messageBytes, start, chunk, 0, length);

      NoiseTransportFrame frame = NoiseTransportFrame.newBuilder()
          .setChunkId(chunkId)
          .setChunkIndex(i)
          .setTotalChunks(totalChunks)
          .setPayload(ByteString.copyFrom(chunk))
          .build();

      frames.add(frame.toByteArray());
    }

    return frames;
  }

  /**
   * Decode a frame from transport bytes.
   */
  public static NoiseTransportFrame decodeFrame(byte[] frameBytes) throws com.google.protobuf.InvalidProtocolBufferException {
    return NoiseTransportFrame.parseFrom(frameBytes);
  }

  /**
   * MessageAssembly tracks the chunks for a single message being assembled.
   */
  private static class MessageAssembly {
    private final Map<Integer, byte[]> chunks = new HashMap<>();
    private final int totalChunks;

    MessageAssembly(int totalChunks) {
      this.totalChunks = totalChunks;
    }

    boolean addChunk(int chunkIndex, byte[] payload) {
      chunks.put(chunkIndex, payload);
      return isComplete();
    }

    boolean isComplete() {
      return chunks.size() == totalChunks;
    }

    byte[] assemble() {
      // Calculate total length
      int totalLength = 0;
      for (int i = 0; i < totalChunks; i++) {
        byte[] chunk = chunks.get(i);
        if (chunk == null) {
          throw new IllegalStateException("Missing chunk " + i + " of " + totalChunks);
        }
        totalLength += chunk.length;
      }

      // Concatenate chunks
      byte[] result = new byte[totalLength];
      int offset = 0;
      for (int i = 0; i < totalChunks; i++) {
        byte[] chunk = chunks.get(i);
        System.arraycopy(chunk, 0, result, offset, chunk.length);
        offset += chunk.length;
      }

      return result;
    }
  }

  /**
   * FrameAssembler handles collecting and reassembling frames from multiple
   * concurrent messages into complete messages.
   */
  public static class FrameAssembler {
    private final Map<String, MessageAssembly> assemblies = new HashMap<>();

    /**
     * Process a decoded frame. Returns the complete message if all chunks are received,
     * null otherwise.
     */
    public byte[] processFrame(NoiseTransportFrame frame) {
      // Convert int64 to string for Map key
      String chunkId = String.valueOf(frame.getChunkId());

      // Get or create assembly for this message
      MessageAssembly assembly = assemblies.get(chunkId);
      if (assembly == null) {
        assembly = new MessageAssembly(frame.getTotalChunks());
        assemblies.put(chunkId, assembly);
      }

      // Add chunk
      boolean isComplete = assembly.addChunk(frame.getChunkIndex(), frame.getPayload().toByteArray());

      if (isComplete) {
        byte[] message = assembly.assemble();
        assemblies.remove(chunkId); // Clean up
        return message;
      }

      return null;
    }

    /**
     * Get the number of messages currently being assembled.
     */
    public int getPendingCount() {
      return assemblies.size();
    }
  }
}
