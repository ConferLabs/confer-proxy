package org.moxie.confer.proxy.controllers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import jakarta.ws.rs.WebApplicationException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.moxie.confer.proxy.config.Config;
import org.moxie.confer.proxy.documents.DecryptedDocument;
import org.moxie.confer.proxy.documents.worker.DocumentWorkerConnection;
import org.moxie.confer.proxy.documents.worker.DocumentWorkerGateway;
import org.moxie.confer.proxy.documents.worker.DocumentWorkerInputStream;
import org.moxie.confer.proxy.documents.worker.DocumentWorkerOutputStream;
import org.moxie.confer.proxy.documents.worker.DocumentWorkerPayloadRole;
import org.moxie.confer.proxy.documents.worker.DocumentWorkerScheduler;
import org.moxie.confer.proxy.documents.worker.DocumentWorkerTestFrame;
import org.moxie.confer.proxy.documents.worker.DocumentWorkerTestFrameDecoder;
import org.moxie.confer.proxy.documents.worker.responses.DocumentExtractionMetadata;
import org.moxie.confer.proxy.documents.worker.responses.DocumentWorkerResponse;
import org.moxie.confer.proxy.documents.worker.responses.DocumentWorkerResponsePayload;
import org.moxie.confer.proxy.entities.WebsocketRequest;
import org.moxie.confer.proxy.streaming.StreamRegistry;
import org.moxie.confer.proxy.websocket.WebsocketHandlerResponse;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LegacyDocumentExtractionHandlerTest {

  private static final int CHUNK_SIZE = 32 * 1024;

  private static ValidatorFactory validatorFactory;

  private TestDocumentWorkerGateway       workerGateway;
  private TestConfig                      config;
  private ObjectMapper                    mapper;
  private LegacyDocumentExtractionHandler handler;
  private StreamRegistry                  streamRegistry;
  private ByteArrayInputStream             workerResponses;

  @BeforeAll
  static void createValidatorFactory() {
    validatorFactory = Validation.buildDefaultValidatorFactory();
  }

  @AfterAll
  static void closeValidatorFactory() {
    validatorFactory.close();
  }

  @BeforeEach
  void setUp() throws ReflectiveOperationException {
    workerGateway = new TestDocumentWorkerGateway();
    config = new TestConfig();
    mapper = new ObjectMapper();
    streamRegistry = new StreamRegistry();
    handler = new LegacyDocumentExtractionHandler();
    setField(handler, "mapper", mapper);
    setField(handler, "validator", validatorFactory.getValidator());
    setField(handler, "workers", new DocumentWorkerScheduler(config, workerGateway));
  }

  @Test
  void requestWithoutAChunkReturns400() {
    WebsocketRequest request = new WebsocketRequest(
        1,
        "POST",
        "/v1/document/extract",
        Optional.of(optionsBody(1)));

    WebApplicationException error = assertThrows(
        WebApplicationException.class,
        () -> handler.handle(request, streamRegistry));

    assertEquals(400, error.getResponse().getStatus());
  }

  @Test
  void invalidDocumentMetadataReturns400BeforeExtraction() {
    assertBadRequest(optionsBody(" ", 1));
    assertBadRequest(optionsBody(0));
    assertBadRequest(optionsBody(256L * 1024 * 1024 + 1));
  }

  @Test
  void streamsSourceAndReturnsTheDeployedClientJsonContract() throws Exception {
    byte[] source = new byte[CHUNK_SIZE * 2 + 17];
    Arrays.fill(source, (byte) 7);
    ByteArrayOutputStream workerRequests = new ByteArrayOutputStream();
    workerGateway.respondWith(connection(successResponse(), workerRequests));

    WebsocketHandlerResponse response = runWithChunks(source);
    WebsocketHandlerResponse.StreamingResponse streaming = assertInstanceOf(
        WebsocketHandlerResponse.StreamingResponse.class,
        response);
    assertEquals("artifact".length() + "legacy text".length(), workerResponses.available());
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    try (streaming) {
      streaming.writeTo(output);
    }
    assertEquals(0, workerResponses.available());

    DocumentWorkerTestFrame workerRequest = decodeRequest(workerRequests);
    assertArrayEquals(
        source,
        workerRequest.payloads().get(DocumentWorkerPayloadRole.SOURCE).content());
    assertEquals("application/json", streaming.headers().get("Content-Type"));
    assertEquals(output.size(), Integer.parseInt(streaming.headers().get("Content-Length")));

    JsonNode json = mapper.readTree(output.toByteArray());
    assertEquals("legacy text", json.path("document").path("md_content").textValue());
  }

  @Test
  void streamsAnExactlyLengthDelimitedJsonString() throws Exception {
    StringBuilder text = new StringBuilder();
    for (int character = 0; character < 0x20; character++) {
      text.append((char) character);
    }
    text.append(" quote=\" backslash=\\ slash=/ café 🩺");
    workerGateway.respondWith(connection(
        successResponse(text.toString()),
        new ByteArrayOutputStream()));

    WebsocketHandlerResponse.StreamingResponse streaming = assertInstanceOf(
        WebsocketHandlerResponse.StreamingResponse.class,
        handler.handle(legacyRequest(new byte[] {1}, 1, true), streamRegistry));
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    try (streaming) {
      streaming.writeTo(output);
    }

    assertEquals(output.size(), Long.parseLong(streaming.headers().get("Content-Length")));
    assertEquals(
        text.toString(),
        mapper.readTree(output.toByteArray()).path("document").path("md_content").textValue());
  }

  @Test
  void missingTextPayloadReturns502() throws Exception {
    workerGateway.respondWith(connection(
        DocumentWorkerResponse.success(
            new DocumentExtractionMetadata(0L, 0L, 0L),
            Map.of(
                DocumentWorkerPayloadRole.ARTIFACT,
                new DocumentWorkerResponsePayload(
                    DocumentWorkerPayloadRole.ARTIFACT.requiredMediaType(),
                    new byte[] {1}))),
        new ByteArrayOutputStream()));

    WebApplicationException error = assertThrows(
        WebApplicationException.class,
        () -> handler.handle(legacyRequest(new byte[] {1}, 1, true), streamRegistry));

    assertEquals(502, error.getResponse().getStatus());
  }

  @Test
  void mismatchedTextMetadataReturns502() throws Exception {
    workerGateway.respondWith(connection(
        DocumentWorkerResponse.success(
            new DocumentExtractionMetadata(3L, 3L, 3L),
            Map.of(
                DocumentWorkerPayloadRole.TEXT,
                new DocumentWorkerResponsePayload(
                    DocumentWorkerPayloadRole.TEXT.requiredMediaType(),
                    "text".getBytes(StandardCharsets.UTF_8)))),
        new ByteArrayOutputStream()));

    WebApplicationException error = assertThrows(
        WebApplicationException.class,
        () -> handler.handle(legacyRequest(new byte[] {1}, 1, true), streamRegistry));

    assertEquals(502, error.getResponse().getStatus());
  }

  @Test
  void workerRejectionReturns422() throws Exception {
    workerGateway.respondWith(connection(
        DocumentWorkerResponse.failure("invalid_request", "Invalid request"),
        new ByteArrayOutputStream()));

    WebApplicationException error = assertThrows(
        WebApplicationException.class,
        () -> handler.handle(legacyRequest(new byte[] {1}, 1, true), streamRegistry));

    assertEquals(422, error.getResponse().getStatus());
  }

  private WebsocketHandlerResponse runWithChunks(byte[] data) throws Exception {
    int chunkCount = (data.length + CHUNK_SIZE - 1) / CHUNK_SIZE;
    byte[][] chunks = new byte[chunkCount][];
    for (int index = 0; index < chunkCount; index++) {
      int start = index * CHUNK_SIZE;
      int end = Math.min(start + CHUNK_SIZE, data.length);
      chunks[index] = Arrays.copyOfRange(data, start, end);
    }

    WebsocketRequest initial = legacyRequest(chunks[0], data.length, chunkCount == 1);
    CompletableFuture<WebsocketHandlerResponse> response = CompletableFuture.supplyAsync(
        () -> handler.handle(initial, streamRegistry));
    for (int index = 1; index < chunks.length; index++) {
      streamRegistry.handleChunk(1, chunks[index], index, index == chunks.length - 1);
    }
    return response.get(10, TimeUnit.SECONDS);
  }

  private WebsocketRequest legacyRequest(byte[] data,
                                         long totalLength,
                                         boolean isFinal)
  {
    return new WebsocketRequest(
        1,
        Optional.of("POST"),
        Optional.of("/v1/document/extract"),
        Optional.of(optionsBody(totalLength)),
        Optional.of(new WebsocketRequest.StreamChunk(data, isFinal, 0)));
  }

  private String optionsBody(long totalLength) {
    return optionsBody("test.pdf", totalLength);
  }

  private String optionsBody(String filename, long totalLength) {
    return "{\"filename\":\"" + filename + "\""
        + ",\"content_type\":\"application/pdf\""
        + ",\"total_length\":" + totalLength
        + ",\"ocr\":false"
        + ",\"table_structure\":true"
        + ",\"include_images\":false"
        + ",\"image_export_mode\":\"placeholder\"}";
  }

  private void assertBadRequest(String body) {
    WebsocketRequest request = new WebsocketRequest(
        1,
        Optional.of("POST"),
        Optional.of("/v1/document/extract"),
        Optional.of(body),
        Optional.of(new WebsocketRequest.StreamChunk(new byte[] {1}, true, 0)));
    WebApplicationException error = assertThrows(
        WebApplicationException.class,
        () -> handler.handle(request, streamRegistry));

    assertEquals(400, error.getResponse().getStatus());
  }

  private DocumentWorkerConnection connection(DocumentWorkerResponse<?> response,
                                              ByteArrayOutputStream requests)
    throws IOException
  {
    ByteArrayOutputStream encoded = new ByteArrayOutputStream();
    new DocumentWorkerOutputStream(mapper, encoded).writeResponse(response);
    workerResponses = new ByteArrayInputStream(encoded.toByteArray());
    return new DocumentWorkerConnection(
        new DocumentWorkerInputStream(mapper, workerResponses),
        new DocumentWorkerOutputStream(mapper, requests),
        (objectKey, encryptionKey) -> new DecryptedDocument(
            new ByteArrayInputStream(new byte[0]),
            0),
        mapper,
        validatorFactory.getValidator());
  }

  private DocumentWorkerTestFrame decodeRequest(ByteArrayOutputStream requests) throws IOException {
    return new DocumentWorkerTestFrameDecoder(
        mapper,
        new ByteArrayInputStream(requests.toByteArray())).read();
  }

  private DocumentWorkerResponse<DocumentExtractionMetadata> successResponse() throws IOException {
    return successResponse("legacy text");
  }

  private DocumentWorkerResponse<DocumentExtractionMetadata> successResponse(String text)
    throws IOException
  {
    byte[] textBytes = text.getBytes(StandardCharsets.UTF_8);
    Map<DocumentWorkerPayloadRole, DocumentWorkerResponsePayload> payloads = new LinkedHashMap<>();
    payloads.put(
        DocumentWorkerPayloadRole.ARTIFACT,
        new DocumentWorkerResponsePayload(
            DocumentWorkerPayloadRole.ARTIFACT.requiredMediaType(),
            "artifact".getBytes(StandardCharsets.UTF_8)));
    payloads.put(
        DocumentWorkerPayloadRole.TEXT,
        new DocumentWorkerResponsePayload(
            DocumentWorkerPayloadRole.TEXT.requiredMediaType(),
            textBytes));
    return DocumentWorkerResponse.success(
        new DocumentExtractionMetadata(
            (long) textBytes.length,
            (long) new String(textBytes, StandardCharsets.UTF_8).length(),
            jsonEscapedTextBytes(textBytes)),
        payloads);
  }

  private static long jsonEscapedTextBytes(byte[] text) {
    long escapedBytes = text.length;
    for (byte character : text) {
      int value = Byte.toUnsignedInt(character);
      if (value == 0x08
          || value == 0x09
          || value == 0x0a
          || value == 0x0c
          || value == 0x0d
          || value == 0x22
          || value == 0x5c)
      {
        escapedBytes++;
      } else if (value < 0x20) {
        escapedBytes += 5;
      }
    }
    return escapedBytes;
  }

  private static void setField(Object target, String name, Object value)
    throws ReflectiveOperationException
  {
    Field field = target.getClass().getDeclaredField(name);
    field.setAccessible(true);
    field.set(target, value);
  }

  private static class TestConfig extends Config {

    @Override
    public int getDocumentWorkerMaxConnections() {
      return 8;
    }

    @Override
    public long getDocumentWorkerAcquireTimeoutSeconds() {
      return 1;
    }
  }

  private static class TestDocumentWorkerGateway implements DocumentWorkerGateway {

    private DocumentWorkerConnection connection;

    @Override
    public DocumentWorkerConnection connect() {
      return connection;
    }

    private void respondWith(DocumentWorkerConnection connection) {
      this.connection = connection;
    }
  }
}
