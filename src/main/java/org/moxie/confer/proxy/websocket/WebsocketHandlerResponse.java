package org.moxie.confer.proxy.websocket;

import jakarta.ws.rs.core.StreamingOutput;

public sealed interface WebsocketHandlerResponse 
    permits WebsocketHandlerResponse.SingleResponse, WebsocketHandlerResponse.StreamingResponse {
    
    record SingleResponse(int statusCode, String body) implements WebsocketHandlerResponse {}
    record StreamingResponse(StreamingOutput stream) implements WebsocketHandlerResponse {}
}