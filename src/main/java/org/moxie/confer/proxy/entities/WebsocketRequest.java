package org.moxie.confer.proxy.entities;

import java.util.Optional;

/**
 * Domain model for websocket requests.
 *
 * Message interpretation:
 *   - verb + path + body (no chunk): Traditional single-message request
 *   - verb + path + body + chunk: Start streaming request, body has metadata, chunk has first data
 *   - id + chunk (no verb/path): Continuation chunk for in-flight stream
 */
public record WebsocketRequest(
    long id,
    Optional<String> verb,
    Optional<String> path,
    Optional<String> body,
    Optional<StreamChunk> chunk
) {

  /**
   * A chunk of streaming data.
   *
   * @param data           The chunk data
   * @param isFinal        True if this is the last chunk
   * @param sequenceNumber Sequence number for ordering (0-indexed)
   */
  public record StreamChunk(byte[] data, boolean isFinal, int sequenceNumber) {}

  /**
   * Convenience constructor for non-streaming requests.
   */
  public WebsocketRequest(long id, String verb, String path, Optional<String> body) {
    this(id, Optional.of(verb), Optional.of(path), body, Optional.empty());
  }

  /**
   * Returns true if this is a continuation chunk (has chunk but no verb/path).
   */
  public boolean isStreamContinuation() {
    return verb.isEmpty() && path.isEmpty() && chunk.isPresent();
  }

  /**
   * Convert from protobuf WebsocketRequest to domain model.
   * Validates that required fields are present based on message type.
   *
   * @throws IllegalArgumentException if required fields are missing
   */
  public static WebsocketRequest fromProtobuf(confer.NoiseTransport.WebsocketRequest proto) {
    if (!proto.hasId()) {
      throw new IllegalArgumentException("WebsocketRequest missing required field: id");
    }

    Optional<String> verb = proto.hasVerb() && !proto.getVerb().isEmpty()
        ? Optional.of(proto.getVerb())
        : Optional.empty();
    Optional<String> path = proto.hasPath() && !proto.getPath().isEmpty()
        ? Optional.of(proto.getPath())
        : Optional.empty();
    Optional<String> body = proto.hasBody()
        ? Optional.of(proto.getBody().toStringUtf8())
        : Optional.empty();
    Optional<StreamChunk> chunk = Optional.empty();
    if (proto.hasChunk()) {
      int seq = proto.getChunk().getSeq();
      if (seq < 0) {
        throw new IllegalArgumentException("Chunk sequence number must be non-negative");
      }
      chunk = Optional.of(new StreamChunk(
          proto.getChunk().getData().toByteArray(),
          proto.getChunk().getFinal(),
          seq));
    }

    // Validate: must be either traditional request, stream start, or stream continuation
    boolean hasVerb = verb.isPresent();
    boolean hasPath = path.isPresent();
    boolean hasChunk = chunk.isPresent();

    if (hasVerb != hasPath) {
      throw new IllegalArgumentException("WebsocketRequest must have both verb and path, or neither");
    }

    if (!hasVerb && !hasChunk) {
      throw new IllegalArgumentException("WebsocketRequest must have verb/path or chunk");
    }

    return new WebsocketRequest(proto.getId(), verb, path, body, chunk);
  }
}
