package org.moxie.confer.proxy.websocket;

import org.moxie.confer.proxy.entities.WebsocketRequest;

@FunctionalInterface
public interface WebsocketHandler {
    WebsocketHandlerResponse handle(WebsocketRequest request) throws Exception;
}