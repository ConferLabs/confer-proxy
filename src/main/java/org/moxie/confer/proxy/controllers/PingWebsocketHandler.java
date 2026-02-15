package org.moxie.confer.proxy.controllers;

import jakarta.enterprise.context.ApplicationScoped;
import org.moxie.confer.proxy.entities.WebsocketRequest;
import org.moxie.confer.proxy.streaming.StreamRegistry;
import org.moxie.confer.proxy.websocket.WebsocketHandler;
import org.moxie.confer.proxy.websocket.WebsocketHandlerResponse;

@ApplicationScoped
public class PingWebsocketHandler implements WebsocketHandler {

  @Override
  public WebsocketHandlerResponse handle(WebsocketRequest request, StreamRegistry streamRegistry) {
    return new WebsocketHandlerResponse.SingleResponse(200, "PONG");
  }
}
