package org.moxie.confer.proxy.controllers;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.WebApplicationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.moxie.confer.proxy.entities.WebsocketRequest;
import org.moxie.confer.proxy.services.ExternalProxyService;
import org.moxie.confer.proxy.websocket.WebsocketHandlerResponse;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExternalProxyFetchHandlerTest {

  @Mock
  private ExternalProxyService externalProxyService;

  private ObjectMapper        mapper;
  private ExternalProxyFetchHandler handler;

  @BeforeEach
  void setUp() throws Exception {
    mapper         = new ObjectMapper();
    handler        = new ExternalProxyFetchHandler();

    setField(handler, "externalProxyService", externalProxyService);
    setField(handler, "mapper", mapper);
  }

  @Test
  void handle_success_returnsHtml() {
    when(externalProxyService.fetchHtml(eq("https://shop.example.com/p"), any())).thenReturn("<html>ok</html>");

    WebsocketRequest request = createRequest("{\"url\": \"https://shop.example.com/p\"}");
    WebsocketHandlerResponse response = handler.handle(null, request);

    assertInstanceOf(WebsocketHandlerResponse.SingleResponse.class, response);
    WebsocketHandlerResponse.SingleResponse single = (WebsocketHandlerResponse.SingleResponse) response;
    assertEquals(200, single.statusCode());
    assertEquals("<html>ok</html>", single.body());
  }

  @Test
  void handle_forwardsClientHeaders() {
    when(externalProxyService.fetchHtml(eq("https://shop.example.com/p"),
                                        eq(Map.of("User-Agent", "CustomUA/1.0", "Accept-Language", "en-US"))))
        .thenReturn("<html>ok</html>");

    WebsocketRequest request = createRequest(
        "{\"url\": \"https://shop.example.com/p\", "
        + "\"headers\": {\"User-Agent\": \"CustomUA/1.0\", \"Accept-Language\": \"en-US\"}}");
    WebsocketHandlerResponse.SingleResponse single =
        (WebsocketHandlerResponse.SingleResponse) handler.handle(null, request);

    assertEquals(200, single.statusCode());
    assertEquals("<html>ok</html>", single.body());
  }

  @Test
  void handle_fetchReturnsNull_throws502() {
    when(externalProxyService.fetchHtml(eq("https://shop.example.com/p"), any())).thenReturn(null);

    WebsocketRequest request = createRequest("{\"url\": \"https://shop.example.com/p\"}");

    WebApplicationException exception = assertThrows(WebApplicationException.class,
        () -> handler.handle(null, request));
    assertEquals(502, exception.getResponse().getStatus());
  }

  @Test
  void handle_missingUrl_throws400() {
    WebsocketRequest request = createRequest("{}");

    WebApplicationException exception = assertThrows(WebApplicationException.class,
        () -> handler.handle(null, request));
    assertEquals(400, exception.getResponse().getStatus());
  }

  @Test
  void handle_blankUrl_throws400() {
    WebsocketRequest request = createRequest("{\"url\": \"   \"}");

    WebApplicationException exception = assertThrows(WebApplicationException.class,
        () -> handler.handle(null, request));
    assertEquals(400, exception.getResponse().getStatus());
  }

  @Test
  void handle_missingBody_throws400() {
    WebsocketRequest request = new WebsocketRequest(1L, "POST", "/v1/fetch/html", Optional.empty());

    WebApplicationException exception = assertThrows(WebApplicationException.class,
        () -> handler.handle(null, request));
    assertEquals(400, exception.getResponse().getStatus());
  }

  @Test
  void handle_invalidJson_throws400() {
    WebsocketRequest request = createRequest("not json");

    WebApplicationException exception = assertThrows(WebApplicationException.class,
        () -> handler.handle(null, request));
    assertEquals(400, exception.getResponse().getStatus());
  }

  private WebsocketRequest createRequest(String body) {
    return new WebsocketRequest(1L, "POST", "/v1/fetch/html", Optional.of(body));
  }

  private void setField(Object target, String fieldName, Object value) throws Exception {
    Field field = target.getClass().getDeclaredField(fieldName);
    field.setAccessible(true);
    field.set(target, value);
  }
}
