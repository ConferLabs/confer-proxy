package org.moxie.confer.proxy.entities;

import com.google.protobuf.ByteString;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Domain model for websocket responses.
 * This is serialized to protobuf for efficient transmission.
 */
public record WebsocketResponse(long id, int status, ByteString body, Map<String, String> headers) {

    public WebsocketResponse(long id, int status, String body) {
        this(id, status, body != null ? ByteString.copyFromUtf8(body) : ByteString.EMPTY, Map.of());
    }

    public WebsocketResponse(long id, int status, String body, Map<String, String> headers) {
        this(id, status, body != null ? ByteString.copyFromUtf8(body) : ByteString.EMPTY, headers);
    }

    public WebsocketResponse(long id, int status, byte[] body, int offset, int length, Map<String, String> headers) {
        this(id, status, ByteString.copyFrom(body, offset, length), headers);
    }

    /**
     * Convert to protobuf WebsocketResponse for serialization.
     */
    public confer.NoiseTransport.WebsocketResponse toProtobuf() {
        confer.NoiseTransport.WebsocketResponse.Builder builder =
            confer.NoiseTransport.WebsocketResponse.newBuilder()
                .setId(id)
                .setStatus(status);

        if (body != null && !body.isEmpty()) {
            builder.setBody(body);
        }

        if (headers != null && !headers.isEmpty()) {
            builder.putAllHeaders(headers);
        }

        return builder.build();
    }
}
