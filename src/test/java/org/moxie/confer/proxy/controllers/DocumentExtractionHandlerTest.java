package org.moxie.confer.proxy.controllers;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.WebApplicationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.moxie.confer.proxy.config.Config;
import org.moxie.confer.proxy.entities.WebsocketRequest;
import org.moxie.confer.proxy.services.DoclingHttpClient;
import org.moxie.confer.proxy.streaming.StreamRegistry;
import org.moxie.confer.proxy.websocket.WebsocketHandlerResponse;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.net.http.HttpHeaders;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DocumentExtractionHandlerTest {

  private static final int CHUNK_SIZE = 32 * 1024; // 32KB chunks

  @Mock
  private DoclingHttpClient doclingClient;

  @Mock
  private Config config;

  private ObjectMapper mapper;
  private DocumentExtractionHandler handler;
  private StreamRegistry streamRegistry;

  @BeforeEach
  void setUp() throws Exception {
    mapper = new ObjectMapper();
    streamRegistry = new StreamRegistry();

    handler = new DocumentExtractionHandler();

    setField(handler, "mapper", mapper);
    setField(handler, "doclingClient", doclingClient);
    setField(handler, "config", config);
  }

  private void setField(Object target, String fieldName, Object value) throws Exception {
    Field field = target.getClass().getDeclaredField(fieldName);
    field.setAccessible(true);
    field.set(target, value);
  }

  @SuppressWarnings("unchecked")
  private HttpResponse<InputStream> mockDoclingResponse(int statusCode, String body) {
    HttpResponse<InputStream> response = mock(HttpResponse.class);
    when(response.statusCode()).thenReturn(statusCode);
    // Only stub body and headers for successful responses (error paths close response body)
    if (statusCode == 200) {
      when(response.body()).thenReturn(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
      when(response.headers()).thenReturn(HttpHeaders.of(
          Map.of("Content-Length", List.of(String.valueOf(body.length())),
                 "Content-Type", List.of("application/json")),
          (a, b) -> true
      ));
    } else {
      // Error paths close the response body
      when(response.body()).thenReturn(new ByteArrayInputStream(new byte[0]));
    }
    return response;
  }

  /**
   * Makes the mock doclingClient drain the InputStream argument (first arg)
   * on a virtual thread before returning the response future. This simulates
   * sendAsync reading the request body from the pipe, preventing the pipe
   * buffer from filling up and blocking writers.
   */
  private void mockConvertFileDraining(HttpResponse<InputStream> response) throws Exception {
    when(doclingClient.convertFile(any(InputStream.class), anyString(), anyString(), any()))
        .thenAnswer(invocation -> {
          InputStream input = invocation.getArgument(0);
          Thread.startVirtualThread(() -> {
            try { input.readAllBytes(); } catch (IOException ignored) {}
          });
          return CompletableFuture.completedFuture(response);
        });
  }

  /**
   * Helper to run handler with chunked data on separate threads.
   */
  private WebsocketHandlerResponse runHandlerWithChunks(
      String filename, String contentType, byte[] data) throws Exception {

    int numChunks = (data.length + CHUNK_SIZE - 1) / CHUNK_SIZE;
    byte[][] chunks = new byte[numChunks][];
    for (int i = 0; i < numChunks; i++) {
      int start = i * CHUNK_SIZE;
      int end = Math.min(start + CHUNK_SIZE, data.length);
      chunks[i] = new byte[end - start];
      System.arraycopy(data, start, chunks[i], 0, end - start);
    }

    String body;
    if (contentType != null) {
      body = String.format("{\"filename\": \"%s\", \"content_type\": \"%s\"}", filename, contentType);
    } else {
      body = String.format("{\"filename\": \"%s\"}", filename);
    }
    WebsocketRequest request = new WebsocketRequest(
        1L,
        Optional.of("POST"),
        Optional.of("/v1/document/extract"),
        Optional.of(body),
        Optional.of(new WebsocketRequest.StreamChunk(chunks[0], numChunks == 1, 0))
    );

    // Run handler on background thread
    CompletableFuture<WebsocketHandlerResponse> handlerFuture =
        CompletableFuture.supplyAsync(() -> handler.handle(request, streamRegistry));

    // Give handler time to set up pipe
    Thread.sleep(10);

    // Write remaining chunks
    for (int i = 1; i < numChunks; i++) {
      streamRegistry.handleChunk(1L, chunks[i], i, i == numChunks - 1);
    }

    return handlerFuture.get(10, TimeUnit.SECONDS);
  }

  @Test
  void handle_doclingDisabled_throws503() {
    when(config.isDoclingEnabled()).thenReturn(false);

    WebsocketRequest request = new WebsocketRequest(
        1L,
        Optional.of("POST"),
        Optional.of("/v1/document/extract"),
        Optional.of("{\"filename\": \"test.pdf\", \"content_type\": \"application/pdf\"}"),
        Optional.of(new WebsocketRequest.StreamChunk("data".getBytes(), true, 0))
    );

    WebApplicationException exception = assertThrows(WebApplicationException.class,
        () -> handler.handle(request, streamRegistry));

    assertEquals(503, exception.getResponse().getStatus());
  }

  @Test
  void handle_missingChunk_throws400() {
    when(config.isDoclingEnabled()).thenReturn(true);

    WebsocketRequest request = new WebsocketRequest(1L, "POST", "/v1/document/extract",
        Optional.of("{\"filename\": \"test.pdf\"}"));

    WebApplicationException exception = assertThrows(WebApplicationException.class,
        () -> handler.handle(request, streamRegistry));

    assertEquals(400, exception.getResponse().getStatus());
    assertTrue(exception.getMessage().contains("Streaming required"));
  }

  @Test
  void handle_missingBody_throws400() {
    when(config.isDoclingEnabled()).thenReturn(true);

    WebsocketRequest request = new WebsocketRequest(
        1L,
        Optional.of("POST"),
        Optional.of("/v1/document/extract"),
        Optional.empty(),
        Optional.of(new WebsocketRequest.StreamChunk("data".getBytes(), true, 0))
    );

    WebApplicationException exception = assertThrows(WebApplicationException.class,
        () -> handler.handle(request, streamRegistry));

    assertEquals(400, exception.getResponse().getStatus());
    assertTrue(exception.getMessage().contains("Request body"));
  }

  @Test
  void handle_invalidJson_throws400() {
    when(config.isDoclingEnabled()).thenReturn(true);

    WebsocketRequest request = new WebsocketRequest(
        1L,
        Optional.of("POST"),
        Optional.of("/v1/document/extract"),
        Optional.of("not valid json"),
        Optional.of(new WebsocketRequest.StreamChunk("data".getBytes(), true, 0))
    );

    WebApplicationException exception = assertThrows(WebApplicationException.class,
        () -> handler.handle(request, streamRegistry));

    assertEquals(400, exception.getResponse().getStatus());
    assertTrue(exception.getMessage().contains("Invalid request body"));
  }

  @Test
  void handle_missingFilename_throws400() {
    when(config.isDoclingEnabled()).thenReturn(true);

    WebsocketRequest request = new WebsocketRequest(
        1L,
        Optional.of("POST"),
        Optional.of("/v1/document/extract"),
        Optional.of("{\"content_type\": \"application/pdf\"}"),
        Optional.of(new WebsocketRequest.StreamChunk("data".getBytes(), true, 0))
    );

    WebApplicationException exception = assertThrows(WebApplicationException.class,
        () -> handler.handle(request, streamRegistry));

    assertEquals(400, exception.getResponse().getStatus());
    assertTrue(exception.getMessage().contains("filename is required"));
  }

  @Test
  void handle_blankFilename_throws400() {
    when(config.isDoclingEnabled()).thenReturn(true);

    WebsocketRequest request = new WebsocketRequest(
        1L,
        Optional.of("POST"),
        Optional.of("/v1/document/extract"),
        Optional.of("{\"filename\": \"   \"}"),
        Optional.of(new WebsocketRequest.StreamChunk("data".getBytes(), true, 0))
    );

    WebApplicationException exception = assertThrows(WebApplicationException.class,
        () -> handler.handle(request, streamRegistry));

    assertEquals(400, exception.getResponse().getStatus());
    assertTrue(exception.getMessage().contains("filename is required"));
  }

  @Test
  void handle_successfulExtraction_streamsDoclingResponse() throws Exception {
    when(config.isDoclingEnabled()).thenReturn(true);

    String doclingJson = "{\"document\":{\"md_content\":\"# Extracted Content\"}}";
    HttpResponse<InputStream> mockResponse = mockDoclingResponse(200, doclingJson);
    mockConvertFileDraining(mockResponse);

    WebsocketHandlerResponse response = runHandlerWithChunks("test.pdf", "application/pdf", "PDF content".getBytes());

    assertInstanceOf(WebsocketHandlerResponse.StreamingResponse.class, response);

    WebsocketHandlerResponse.StreamingResponse streamingResponse = (WebsocketHandlerResponse.StreamingResponse) response;
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    streamingResponse.stream().write(output);

    String jsonOutput = output.toString(StandardCharsets.UTF_8);
    assertTrue(jsonOutput.contains("Extracted Content"));
  }

  @Test
  void handle_successfulExtraction_includesContentLengthHeader() throws Exception {
    when(config.isDoclingEnabled()).thenReturn(true);

    String doclingJson = "{\"document\":{\"md_content\":\"# Test\"}}";
    HttpResponse<InputStream> mockResponse = mockDoclingResponse(200, doclingJson);
    mockConvertFileDraining(mockResponse);

    WebsocketHandlerResponse response = runHandlerWithChunks("test.pdf", "application/pdf", "PDF content".getBytes());

    assertInstanceOf(WebsocketHandlerResponse.StreamingResponse.class, response);
    WebsocketHandlerResponse.StreamingResponse streamingResponse = (WebsocketHandlerResponse.StreamingResponse) response;

    String contentLength = streamingResponse.headers().get("Content-Length");
    assertNotNull(contentLength, "Content-Length header must be present");

    ByteArrayOutputStream output = new ByteArrayOutputStream();
    streamingResponse.stream().write(output);

    assertEquals(Integer.parseInt(contentLength), output.size(),
        "Content-Length header must match actual response body size");
  }

  @Test
  void handle_doclingReturnsError_throwsWithStatusCode() throws Exception {
    when(config.isDoclingEnabled()).thenReturn(true);

    HttpResponse<InputStream> mockResponse = mockDoclingResponse(500, "Internal error");
    mockConvertFileDraining(mockResponse);

    java.util.concurrent.ExecutionException execException = assertThrows(
        java.util.concurrent.ExecutionException.class,
        () -> runHandlerWithChunks("test.pdf", "application/pdf", "PDF content".getBytes()));

    assertInstanceOf(WebApplicationException.class, execException.getCause());
    WebApplicationException exception = (WebApplicationException) execException.getCause();
    assertEquals(500, exception.getResponse().getStatus());
  }

  @Test
  void handle_doclingThrowsIOException_throwsError() throws Exception {
    when(config.isDoclingEnabled()).thenReturn(true);

    when(doclingClient.convertFile(any(InputStream.class), anyString(), anyString(), any()))
        .thenAnswer(invocation -> {
          InputStream input = invocation.getArgument(0);
          Thread.startVirtualThread(() -> {
            try { input.readAllBytes(); } catch (IOException ignored) {}
          });
          return CompletableFuture.failedFuture(new IOException("Connection refused"));
        });

    java.util.concurrent.ExecutionException execException = assertThrows(
        java.util.concurrent.ExecutionException.class,
        () -> runHandlerWithChunks("test.pdf", "application/pdf", "PDF content".getBytes()));

    assertInstanceOf(WebApplicationException.class, execException.getCause());
    WebApplicationException exception = (WebApplicationException) execException.getCause();
    int status = exception.getResponse().getStatus();
    // 502 if the chunk write succeeds and responseFuture.get() throws ExecutionException,
    // 500 if the pipe closes first (from whenComplete) and the chunk write fails
    assertTrue(status == 500 || status == 502,
        "Expected 500 or 502 but got " + status);
  }

  @Test
  void handle_httpFailureDuringLargeChunkWrite_doesNotDeadlock() throws Exception {
    when(config.isDoclingEnabled()).thenReturn(true);

    // Return a future that fails after a delay — simulates the HTTP client
    // failing after sendAsync has started but before the pipe is fully drained.
    // Without the whenComplete error handler, the chunk write would block
    // forever on a full pipe buffer with no reader.
    when(doclingClient.convertFile(any(InputStream.class), anyString(), anyString(), any()))
        .thenAnswer(invocation -> {
          CompletableFuture<HttpResponse<InputStream>> future = new CompletableFuture<>();
          Thread.startVirtualThread(() -> {
            try { Thread.sleep(50); } catch (InterruptedException ignored) {}
            future.completeExceptionally(new IOException("Connection reset"));
          });
          return future;
        });

    // Use data larger than the NIO pipe buffer (~64KB) in a single chunk
    // so the first chunk write blocks inside handle()
    byte[] largeChunk = new byte[256 * 1024];
    String body = "{\"filename\": \"large.pdf\", \"content_type\": \"application/pdf\"}";
    WebsocketRequest request = new WebsocketRequest(
        1L,
        Optional.of("POST"),
        Optional.of("/v1/document/extract"),
        Optional.of(body),
        Optional.of(new WebsocketRequest.StreamChunk(largeChunk, true, 0))
    );

    // handler.handle() must complete (with an exception) within the timeout,
    // not deadlock on the blocked pipe write
    CompletableFuture<WebsocketHandlerResponse> handlerFuture =
        CompletableFuture.supplyAsync(() -> handler.handle(request, streamRegistry));

    assertThrows(java.util.concurrent.ExecutionException.class,
        () -> handlerFuture.get(5, TimeUnit.SECONDS));
  }

  @Test
  void handle_defaultContentType_usesOctetStream() throws Exception {
    when(config.isDoclingEnabled()).thenReturn(true);

    String doclingJson = "{\"document\":{\"md_content\":\"content\"}}";
    HttpResponse<InputStream> mockResponse = mockDoclingResponse(200, doclingJson);
    mockConvertFileDraining(mockResponse);

    WebsocketHandlerResponse response = runHandlerWithChunks("test.bin", null, "binary data".getBytes());

    // Consume response to avoid resource leak
    WebsocketHandlerResponse.StreamingResponse streamingResponse = (WebsocketHandlerResponse.StreamingResponse) response;
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    streamingResponse.stream().write(output);

    verify(doclingClient).convertFile(any(InputStream.class), eq("test.bin"), eq("application/octet-stream"), any());
  }

  @Test
  void handle_multipleChunks_assemblesCorrectly() throws Exception {
    when(config.isDoclingEnabled()).thenReturn(true);

    String doclingJson = "{\"document\":{\"md_content\":\"extracted\"}}";
    HttpResponse<InputStream> mockResponse = mockDoclingResponse(200, doclingJson);
    mockConvertFileDraining(mockResponse);

    byte[] largeData = new byte[CHUNK_SIZE * 3];
    for (int i = 0; i < largeData.length; i++) {
      largeData[i] = (byte) (i % 256);
    }

    WebsocketHandlerResponse response = runHandlerWithChunks("large.pdf", "application/pdf", largeData);
    WebsocketHandlerResponse.StreamingResponse streamingResponse = (WebsocketHandlerResponse.StreamingResponse) response;

    ByteArrayOutputStream output = new ByteArrayOutputStream();
    streamingResponse.stream().write(output);

    String jsonOutput = output.toString(StandardCharsets.UTF_8);
    assertTrue(jsonOutput.contains("extracted"));
  }

  @Test
  void handle_ocrOption_passedToDocling() throws Exception {
    when(config.isDoclingEnabled()).thenReturn(true);

    String doclingJson = "{\"document\":{\"md_content\":\"ocr result\"}}";
    HttpResponse<InputStream> mockResponse = mockDoclingResponse(200, doclingJson);

    org.mockito.ArgumentCaptor<DoclingHttpClient.ConvertOptions> optionsCaptor =
        org.mockito.ArgumentCaptor.forClass(DoclingHttpClient.ConvertOptions.class);
    when(doclingClient.convertFile(any(InputStream.class), anyString(), anyString(), optionsCaptor.capture()))
        .thenAnswer(invocation -> {
          InputStream input = invocation.getArgument(0);
          Thread.startVirtualThread(() -> {
            try { input.readAllBytes(); } catch (IOException ignored) {}
          });
          return CompletableFuture.completedFuture(mockResponse);
        });

    String body = "{\"filename\": \"scan.pdf\", \"content_type\": \"application/pdf\", \"ocr\": true}";
    WebsocketRequest request = new WebsocketRequest(
        1L,
        Optional.of("POST"),
        Optional.of("/v1/document/extract"),
        Optional.of(body),
        Optional.of(new WebsocketRequest.StreamChunk("scanned content".getBytes(), true, 0))
    );

    CompletableFuture<WebsocketHandlerResponse> handlerFuture =
        CompletableFuture.supplyAsync(() -> handler.handle(request, streamRegistry));

    WebsocketHandlerResponse response = handlerFuture.get(10, TimeUnit.SECONDS);

    // Consume response to avoid resource leak
    WebsocketHandlerResponse.StreamingResponse streamingResponse = (WebsocketHandlerResponse.StreamingResponse) response;
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    streamingResponse.stream().write(output);

    DoclingHttpClient.ConvertOptions capturedOptions = optionsCaptor.getValue();
    assertEquals(true, capturedOptions.ocr());
  }

  @Test
  void handle_tableStructureOption_passedToDocling() throws Exception {
    when(config.isDoclingEnabled()).thenReturn(true);

    String doclingJson = "{\"document\":{\"md_content\":\"table content\"}}";
    HttpResponse<InputStream> mockResponse = mockDoclingResponse(200, doclingJson);

    org.mockito.ArgumentCaptor<DoclingHttpClient.ConvertOptions> optionsCaptor =
        org.mockito.ArgumentCaptor.forClass(DoclingHttpClient.ConvertOptions.class);
    when(doclingClient.convertFile(any(InputStream.class), anyString(), anyString(), optionsCaptor.capture()))
        .thenAnswer(invocation -> {
          InputStream input = invocation.getArgument(0);
          Thread.startVirtualThread(() -> {
            try { input.readAllBytes(); } catch (IOException ignored) {}
          });
          return CompletableFuture.completedFuture(mockResponse);
        });

    String body = "{\"filename\": \"doc.pdf\", \"content_type\": \"application/pdf\", \"table_structure\": true}";
    WebsocketRequest request = new WebsocketRequest(
        1L,
        Optional.of("POST"),
        Optional.of("/v1/document/extract"),
        Optional.of(body),
        Optional.of(new WebsocketRequest.StreamChunk("content".getBytes(), true, 0))
    );

    CompletableFuture<WebsocketHandlerResponse> handlerFuture =
        CompletableFuture.supplyAsync(() -> handler.handle(request, streamRegistry));

    WebsocketHandlerResponse response = handlerFuture.get(10, TimeUnit.SECONDS);

    // Consume response to avoid resource leak
    WebsocketHandlerResponse.StreamingResponse streamingResponse = (WebsocketHandlerResponse.StreamingResponse) response;
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    streamingResponse.stream().write(output);

    DoclingHttpClient.ConvertOptions capturedOptions = optionsCaptor.getValue();
    assertEquals(true, capturedOptions.tableStructure());
  }

  @Test
  void handle_tooManyPendingChunksPerStream_throwsError() throws Exception {
    for (int i = 0; i < 256; i++) {
      streamRegistry.handleChunk(999L, ("chunk" + i).getBytes(), i, false);
    }

    assertThrows(IOException.class, () ->
        streamRegistry.handleChunk(999L, "overflow".getBytes(), 256, true));
  }

  @Test
  void handle_tooManyPendingStreams_evictsOldest() throws Exception {
    for (long i = 1; i <= 16; i++) {
      streamRegistry.handleChunk(i, "data".getBytes(), 0, false);
    }

    streamRegistry.handleChunk(17L, "data".getBytes(), 0, false);

    ByteArrayOutputStream sink = new ByteArrayOutputStream();
    streamRegistry.createStream(1L, sink);

    assertEquals(0, sink.size());
  }
}
