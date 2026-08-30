package org.moxie.confer.proxy.controllers;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import org.moxie.confer.proxy.entities.FetchHtmlRequest;
import org.moxie.confer.proxy.entities.WebsocketRequest;
import org.moxie.confer.proxy.services.ExternalProxyService;
import org.moxie.confer.proxy.websocket.WebsocketConnectionContext;
import org.moxie.confer.proxy.websocket.WebsocketHandler;
import org.moxie.confer.proxy.websocket.WebsocketHandlerResponse;

/**
 * Fetches a page's raw HTML through an external proxy for a web client (which cannot fetch
 * a cross-origin merchant page directly). The handler is a thin relay — it does
 * no parsing; the client extracts product fields from the returned HTML.
 */
@ApplicationScoped
public class ExternalProxyFetchHandler implements WebsocketHandler {

  @Inject
  ExternalProxyService externalProxyService;

  @Inject
  ObjectMapper mapper;

  @Override
  public WebsocketHandlerResponse handle(WebsocketConnectionContext context,
                                         WebsocketRequest           request)
  {
    FetchHtmlRequest fetchRequest = parseRequest(request);

    if (fetchRequest.url() == null || fetchRequest.url().isBlank()) {
      throw new WebApplicationException("url is required", 400);
    }

    String html = externalProxyService.fetchHtml(fetchRequest.url(), fetchRequest.headers());

    if (html == null) {
      throw new WebApplicationException("Failed to fetch page", 502);
    }

    return new WebsocketHandlerResponse.SingleResponse(200, html);
  }

  private FetchHtmlRequest parseRequest(WebsocketRequest request) {
    if (request.body().isEmpty()) {
      throw new WebApplicationException("Request body is required", 400);
    }

    try {
      return mapper.readValue(request.body().get(), FetchHtmlRequest.class);
    } catch (JsonProcessingException e) {
      throw new WebApplicationException("Invalid FetchHtmlRequest body", 400);
    }
  }
}
