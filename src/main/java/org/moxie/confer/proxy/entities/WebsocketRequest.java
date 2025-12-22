package org.moxie.confer.proxy.entities;

import java.util.Optional;

/**
 * Domain model for websocket requests.
 */
public record WebsocketRequest(
    long id,
    String verb,
    String path,
    Optional<String> body
) {
    /**
     * Convert from protobuf WebsocketRequest to domain model.
     * Validates that required fields are present.
     *
     * @throws IllegalArgumentException if required fields are missing
     */
    public static WebsocketRequest fromProtobuf(confer.NoiseTransport.WebsocketRequest proto) {
        if (!proto.hasId()) {
            throw new IllegalArgumentException("WebsocketRequest missing required field: id");
        }
        if (!proto.hasVerb() || proto.getVerb().isEmpty()) {
            throw new IllegalArgumentException("WebsocketRequest missing required field: verb");
        }
        if (!proto.hasPath() || proto.getPath().isEmpty()) {
            throw new IllegalArgumentException("WebsocketRequest missing required field: path");
        }

        return new WebsocketRequest(
            proto.getId(),
            proto.getVerb(),
            proto.getPath(),
            proto.hasBody() ? Optional.of(proto.getBody().toStringUtf8()) : Optional.empty()
        );
    }
}
