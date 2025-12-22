package org.moxie.confer.proxy.entities;

import com.google.protobuf.ByteString;

/**
 * Domain model for websocket responses.
 * This is serialized to protobuf for efficient transmission.
 */
public record WebsocketResponse(long id, int status, String body) {
    /**
     * Convert to protobuf WebsocketResponse for serialization.
     */
    public confer.NoiseTransport.WebsocketResponse toProtobuf() {
        confer.NoiseTransport.WebsocketResponse.Builder builder =
            confer.NoiseTransport.WebsocketResponse.newBuilder()
                .setId(id)
                .setStatus(status);

        if (body != null && !body.isEmpty()) {
            builder.setBody(ByteString.copyFromUtf8(body));
        }

        return builder.build();
    }
}
