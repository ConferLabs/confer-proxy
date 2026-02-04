package org.moxie.confer.proxy.websocket;

import org.moxie.confer.proxy.entities.WebsocketRequest;
import org.moxie.confer.proxy.streaming.StreamRegistry;

@FunctionalInterface
public interface WebsocketHandler {
  WebsocketHandlerResponse handle(WebsocketRequest request, StreamRegistry streamRegistry) throws Exception;
}