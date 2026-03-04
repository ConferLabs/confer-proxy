package org.moxie.confer.proxy.controllers;

import ai.onnxruntime.OrtException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.WebApplicationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.moxie.confer.proxy.entities.WebsocketRequest;
import org.moxie.confer.proxy.services.EmbeddingService;
import org.moxie.confer.proxy.streaming.StreamRegistry;
import org.moxie.confer.proxy.websocket.WebsocketHandlerResponse;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EmbeddingHandlerTest {

  @Mock
  private EmbeddingService embeddingService;

  private ObjectMapper mapper;
  private EmbeddingHandler handler;
  private StreamRegistry streamRegistry;

  @BeforeEach
  void setUp() throws Exception {
    mapper = new ObjectMapper();
    streamRegistry = new StreamRegistry();

    handler = new EmbeddingHandler();

    setField(handler, "embeddingService", embeddingService);
    setField(handler, "mapper", mapper);
  }

  @Test
  void handle_singleText_returnsEmbedding() throws Exception {
    float[] embedding = new float[]{0.1f, 0.2f, 0.3f};
    when(embeddingService.embedBatch(List.of("hello world"))).thenReturn(List.of(embedding));
    when(embeddingService.getDimension()).thenReturn(768);

    WebsocketRequest request = createRequest("{\"texts\": [\"hello world\"]}");
    WebsocketHandlerResponse response = handler.handle(request, streamRegistry);

    assertInstanceOf(WebsocketHandlerResponse.SingleResponse.class, response);
    WebsocketHandlerResponse.SingleResponse single = (WebsocketHandlerResponse.SingleResponse) response;
    assertEquals(200, single.statusCode());
    assertTrue(single.body().contains("\"dimension\":768"));
    assertTrue(single.body().contains("embeddings"));
  }

  @Test
  void handle_multiplTexts_returnsMultipleEmbeddings() throws Exception {
    List<String> texts = List.of("fact one", "fact two");
    float[] emb1 = new float[]{0.1f, 0.2f};
    float[] emb2 = new float[]{0.3f, 0.4f};
    when(embeddingService.embedBatch(texts)).thenReturn(List.of(emb1, emb2));
    when(embeddingService.getDimension()).thenReturn(768);

    WebsocketRequest request = createRequest("{\"texts\": [\"fact one\", \"fact two\"]}");
    WebsocketHandlerResponse.SingleResponse single =
        (WebsocketHandlerResponse.SingleResponse) handler.handle(request, streamRegistry);

    assertEquals(200, single.statusCode());

    // Deserialize and verify two embeddings came back
    com.fasterxml.jackson.databind.JsonNode json = mapper.readTree(single.body());
    assertEquals(2, json.get("embeddings").size());
  }

  @Test
  void handle_isQueryTrue_usesQueryBatch() throws Exception {
    float[] embedding = new float[]{0.5f, 0.6f};
    when(embeddingService.embedQueryBatch(List.of("search term"))).thenReturn(List.of(embedding));
    when(embeddingService.getDimension()).thenReturn(768);

    WebsocketRequest request = createRequest("{\"texts\": [\"search term\"], \"isQuery\": true}");
    WebsocketHandlerResponse.SingleResponse single =
        (WebsocketHandlerResponse.SingleResponse) handler.handle(request, streamRegistry);

    assertEquals(200, single.statusCode());
  }

  @Test
  void handle_isQueryFalse_usesDocumentBatch() throws Exception {
    float[] embedding = new float[]{0.7f, 0.8f};
    when(embeddingService.embedBatch(List.of("a fact"))).thenReturn(List.of(embedding));
    when(embeddingService.getDimension()).thenReturn(768);

    WebsocketRequest request = createRequest("{\"texts\": [\"a fact\"], \"isQuery\": false}");
    WebsocketHandlerResponse.SingleResponse single =
        (WebsocketHandlerResponse.SingleResponse) handler.handle(request, streamRegistry);

    assertEquals(200, single.statusCode());
  }

  @Test
  void handle_isQueryOmitted_usesDocumentBatch() throws Exception {
    float[] embedding = new float[]{0.9f, 1.0f};
    when(embeddingService.embedBatch(List.of("a fact"))).thenReturn(List.of(embedding));
    when(embeddingService.getDimension()).thenReturn(768);

    WebsocketRequest request = createRequest("{\"texts\": [\"a fact\"]}");
    WebsocketHandlerResponse.SingleResponse single =
        (WebsocketHandlerResponse.SingleResponse) handler.handle(request, streamRegistry);

    assertEquals(200, single.statusCode());
  }

  @Test
  void handle_emptyTexts_throws400() {
    WebsocketRequest request = createRequest("{\"texts\": []}");

    WebApplicationException exception = assertThrows(WebApplicationException.class,
        () -> handler.handle(request, streamRegistry));
    assertEquals(400, exception.getResponse().getStatus());
  }

  @Test
  void handle_nullTexts_throws400() {
    WebsocketRequest request = createRequest("{\"isQuery\": true}");

    WebApplicationException exception = assertThrows(WebApplicationException.class,
        () -> handler.handle(request, streamRegistry));
    assertEquals(400, exception.getResponse().getStatus());
  }

  @Test
  void handle_missingBody_throws400() {
    WebsocketRequest request = new WebsocketRequest(1L, "POST", "/v1/embeddings", Optional.empty());

    WebApplicationException exception = assertThrows(WebApplicationException.class,
        () -> handler.handle(request, streamRegistry));
    assertEquals(400, exception.getResponse().getStatus());
  }

  @Test
  void handle_invalidJson_throws400() {
    WebsocketRequest request = createRequest("not json at all");

    WebApplicationException exception = assertThrows(WebApplicationException.class,
        () -> handler.handle(request, streamRegistry));
    assertEquals(400, exception.getResponse().getStatus());
  }

  @Test
  void handle_exceedsBatchSize_throws400() {
    List<String> texts = new java.util.ArrayList<>();
    for (int i = 0; i < 65; i++) {
      texts.add("text " + i);
    }
    String json = "{\"texts\": [" + texts.stream().map(t -> "\"" + t + "\"").collect(java.util.stream.Collectors.joining(",")) + "]}";
    WebsocketRequest request = createRequest(json);

    WebApplicationException exception = assertThrows(WebApplicationException.class,
        () -> handler.handle(request, streamRegistry));
    assertEquals(400, exception.getResponse().getStatus());
  }

  @Test
  void handle_ortException_throws500() throws Exception {
    when(embeddingService.embedBatch(anyList())).thenThrow(new OrtException("inference failed"));

    WebsocketRequest request = createRequest("{\"texts\": [\"hello\"]}");

    WebApplicationException exception = assertThrows(WebApplicationException.class,
        () -> handler.handle(request, streamRegistry));
    assertEquals(500, exception.getResponse().getStatus());
  }

  private WebsocketRequest createRequest(String body) {
    return new WebsocketRequest(1L, "POST", "/v1/embeddings", Optional.of(body));
  }

  private void setField(Object target, String fieldName, Object value) throws Exception {
    Field field = target.getClass().getDeclaredField(fieldName);
    field.setAccessible(true);
    field.set(target, value);
  }
}
