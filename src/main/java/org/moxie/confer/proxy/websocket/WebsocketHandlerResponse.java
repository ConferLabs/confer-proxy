package org.moxie.confer.proxy.websocket;

import jakarta.ws.rs.core.StreamingOutput;

import java.util.Map;

public sealed interface WebsocketHandlerResponse
    permits WebsocketHandlerResponse.SingleResponse, WebsocketHandlerResponse.StreamingResponse {

    record SingleResponse(int statusCode, String body) implements WebsocketHandlerResponse {}
    record StreamingResponse(Map<String, String> headers, StreamingOutput stream) implements WebsocketHandlerResponse {
        public StreamingResponse(StreamingOutput stream) {
            this(Map.of(), stream);
        }
    }
}