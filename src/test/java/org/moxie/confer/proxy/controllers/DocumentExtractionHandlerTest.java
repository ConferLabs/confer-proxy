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
import org.moxie.confer.proxy.documents.DocumentNotFoundException;
import org.moxie.confer.proxy.documents.DocumentStorageGateway;
import org.moxie.confer.proxy.documents.DocumentStorageWriter;
import org.moxie.confer.proxy.documents.extraction.StoredDocumentExtractor;
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
import org.moxie.confer.proxy.websocket.WebsocketHandlerResponse;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StreamCorruptedException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocumentExtractionHandlerTest {

  private static final int CHUNK_SIZE = 32 * 1024;

  private static ValidatorFactory validatorFactory;

  private TestDocumentWorkerGateway workerGateway;
  private RecordingStorage          storage;
  private TestConfig                config;
  private ObjectMapper              mapper;
  private DocumentExtractionHandler handler;

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
    storage = new RecordingStorage();
    config = new TestConfig();
    mapper = new ObjectMapper();
    handler = new DocumentExtractionHandler();
    setField(handler, "mapper", mapper);
    setField(handler, "validator", validatorFactory.getValidator());
    setField(handler, "extractor", new StoredDocumentExtractor(
        storage,
        storage,
        new DocumentWorkerScheduler(config, workerGateway)));
  }

  @Test
  void storedExtractionDoesNotAcceptFileData() {
    WebsocketRequest request = new WebsocketRequest(
        1,
        Optional.of("POST"),
        Optional.of("/v2/document/extract"),
        storedRequest("test.pdf", "application/pdf", 1).body(),
        Optional.of(new WebsocketRequest.StreamChunk(new byte[] {1}, true, 0)));

    WebApplicationException error = assertThrows(
        WebApplicationException.class,
        () -> handler.handle(null, request));

    assertEquals(400, error.getResponse().getStatus());
  }

  @Test
  void incompleteStoredReferenceReturns400() {
    WebsocketRequest request = new WebsocketRequest(
        1,
        "POST",
        "/v2/document/extract",
        Optional.of("""
            {"filename":"test.pdf","content_type":"application/pdf","total_length":1,
             "source_object_key":"source/object"}
            """));

    WebApplicationException error = assertThrows(
        WebApplicationException.class,
        () -> handler.handle(null, request));

    assertEquals(400, error.getResponse().getStatus());
  }

  @Test
  void legacyOfficeFormatReturns415() {
    WebApplicationException error = assertThrows(
        WebApplicationException.class,
        () -> handler.handle(null, storedRequest("test.doc", "application/msword", 1)));

    assertEquals(415, error.getResponse().getStatus());
  }

  @Test
  void invalidDocumentMetadataReturns400BeforeExtraction() {
    assertBadRequest(storedRequest(" ", "application/pdf", 1));
    assertBadRequest(storedRequest("test.pdf", "application/pdf", 0));
    assertBadRequest(storedRequest("test.pdf", "application/pdf", 256L * 1024 * 1024 + 1));
  }

  @Test
  void readsStoredSourceAndPersistsDerivedObjectsBeforeReturningText() throws Exception {
    byte[] source = new byte[CHUNK_SIZE * 3 + 17];
    for (int index = 0; index < source.length; index++) {
      source[index] = (byte) index;
    }
    storage.put("source/object", source);
    ByteArrayOutputStream requests = new ByteArrayOutputStream();
    workerGateway.respondWith(connection(successResponse(), requests));

    WebsocketHandlerResponse handlerResponse = handler.handle(
        null,
        storedRequest("test.pdf", "application/pdf", source.length));
    WebsocketHandlerResponse.StreamingResponse streaming = assertInstanceOf(
        WebsocketHandlerResponse.StreamingResponse.class,
        handlerResponse);
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    try (streaming) {
      streaming.writeTo(output);
    }

    DocumentWorkerTestFrame received = decodeRequest(requests);
    assertArrayEquals(
        source,
        received.payloads().get(DocumentWorkerPayloadRole.SOURCE).content());
    assertEquals("source/object", storage.openedObjectKey());
    assertEquals(output.size(), Integer.parseInt(streaming.headers().get("Content-Length")));
    assertEquals(
        "application/vnd.confer.document-extraction+json",
        streaming.headers().get("Content-Type"));
    JsonNode result = mapper.readTree(output.toByteArray());
    assertEquals(4, result.get("text_length").intValue());
    assertEquals("text", result.get("markdown").textValue());
    assertArrayEquals(
        "artifact".getBytes(StandardCharsets.UTF_8),
        storage.object("source/object.artifact"));
    assertArrayEquals(
        "text".getBytes(StandardCharsets.UTF_8),
        storage.object("source/object.txt"));
    assertTrue(storage.receivedBufferedInput("source/object.txt"));
  }

  @Test
  void omitsLargeTextFromTheExtractionResponse() throws Exception {
    byte[] source = new byte[] {1};
    String text = "x".repeat(256 * 1024 + 1);
    storage.put("source/object", source);
    workerGateway.respondWith(connection(
        successResponse(text),
        new ByteArrayOutputStream()));

    WebsocketHandlerResponse.StreamingResponse response = assertInstanceOf(
        WebsocketHandlerResponse.StreamingResponse.class,
        handler.handle(
            null,
            storedRequest("test.pdf", "application/pdf", source.length)));
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    try (response) {
      response.writeTo(output);
    }

    JsonNode result = mapper.readTree(output.toByteArray());
    assertEquals(text.length(), result.get("text_length").intValue());
    assertFalse(result.has("markdown"));
    assertArrayEquals(
        text.getBytes(StandardCharsets.UTF_8),
        storage.object("source/object.txt"));
  }

  @Test
  void usesTheClientInlineTextLimit() throws Exception {
    byte[] source = new byte[] {1};
    String text = "x".repeat(256 * 1024 + 1);
    storage.put("source/object", source);
    workerGateway.respondWith(connection(
        successResponse(text),
        new ByteArrayOutputStream()));

    WebsocketHandlerResponse.StreamingResponse response = assertInstanceOf(
        WebsocketHandlerResponse.StreamingResponse.class,
        handler.handle(
            null,
            storedRequest("test.pdf", "application/pdf", source.length, text.length())));
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    try (response) {
      response.writeTo(output);
    }

    JsonNode result = mapper.readTree(output.toByteArray());
    assertEquals(text.length(), result.get("text_length").intValue());
    assertEquals(text, result.get("markdown").textValue());
  }

  @Test
  void zeroClientLimitDisablesInlineText() throws Exception {
    byte[] source = new byte[] {1};
    storage.put("source/object", source);
    workerGateway.respondWith(connection(successResponse(), new ByteArrayOutputStream()));

    WebsocketHandlerResponse.StreamingResponse response = assertInstanceOf(
        WebsocketHandlerResponse.StreamingResponse.class,
        handler.handle(
            null,
            storedRequest("test.pdf", "application/pdf", source.length, 0)));
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    try (response) {
      response.writeTo(output);
    }

    JsonNode result = mapper.readTree(output.toByteArray());
    assertEquals(4, result.get("text_length").intValue());
    assertFalse(result.has("markdown"));
    assertFalse(storage.receivedBufferedInput("source/object.txt"));
  }

  @Test
  void negativeClientLimitReturns400BeforeExtraction() {
    WebApplicationException error = assertThrows(
        WebApplicationException.class,
        () -> handler.handle(
            null,
            storedRequest("test.pdf", "application/pdf", 1, -1)));

    assertEquals(400, error.getResponse().getStatus());
  }

  @Test
  void clientLimitBeyondLongRangeReturns400BeforeExtraction() {
    WebApplicationException error = assertThrows(
        WebApplicationException.class,
        () -> handler.handle(
            null,
            storedRequestWithInlineLimit(
                "test.pdf",
                "application/pdf",
                1,
                "9223372036854775808")));

    assertEquals(400, error.getResponse().getStatus());
  }

  @Test
  void clientLimitBeyondIntegerRangeIsAcceptedAndClamped() throws Exception {
    byte[] source = new byte[] {1};
    String text = "x".repeat(300_000);
    storage.put("source/object", source);
    workerGateway.respondWith(connection(successResponse(text), new ByteArrayOutputStream()));

    WebsocketHandlerResponse.StreamingResponse response = assertInstanceOf(
        WebsocketHandlerResponse.StreamingResponse.class,
        handler.handle(
            null,
            storedRequest(
                "test.pdf",
                "application/pdf",
                source.length,
                2_147_483_648L)));
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    try (response) {
      response.writeTo(output);
    }

    assertEquals(text, mapper.readTree(output.toByteArray()).get("markdown").textValue());
  }

  @Test
  void reportsJavascriptCharacterLengthAndStreamsValidJson() throws Exception {
    byte[] source = new byte[] {1};
    String text = "quote=\" newline=\n café 🩺";
    storage.put("source/object", source);
    workerGateway.respondWith(connection(successResponse(text), new ByteArrayOutputStream()));

    WebsocketHandlerResponse.StreamingResponse response = assertInstanceOf(
        WebsocketHandlerResponse.StreamingResponse.class,
        handler.handle(
            null,
            storedRequest("test.pdf", "application/pdf", source.length)));
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    try (response) {
      response.writeTo(output);
    }

    JsonNode result = mapper.readTree(output.toByteArray());
    assertEquals(text.length(), result.get("text_length").longValue());
    assertEquals(text, result.get("markdown").textValue());
    assertEquals(output.size(), Long.parseLong(response.headers().get("Content-Length")));
  }

  @Test
  void modernOfficeFormatIsSentToWorker() throws Exception {
    storage.put("source/object", new byte[] {1});
    ByteArrayOutputStream requests = new ByteArrayOutputStream();
    workerGateway.respondWith(connection(successResponse(), requests));

    WebsocketHandlerResponse response = handler.handle(
        null,
        storedRequest(
            "test.docx",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            1));

    WebsocketHandlerResponse.StreamingResponse streaming = assertInstanceOf(
        WebsocketHandlerResponse.StreamingResponse.class,
        response);
    try (streaming) {
      streaming.writeTo(new ByteArrayOutputStream());
    }
    DocumentWorkerTestFrame received = decodeRequest(requests);

    assertEquals("extract", received.header().get("operation"));
    assertEquals("test.docx", received.header().get("filename"));
    assertEquals(
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        received.header().get("content_type"));
  }

  @Test
  void sourceLengthMismatchReturns422AndReleasesWorker() throws Exception {
    storage.put("source/object", new byte[] {1, 2});
    workerGateway.respondWith(connection(successResponse(), new ByteArrayOutputStream()));

    WebApplicationException error = assertThrows(
        WebApplicationException.class,
        () -> handler.handle(null, storedRequest("test.pdf", "application/pdf", 1)));

    assertEquals(422, error.getResponse().getStatus());
  }

  @Test
  void missingStoredSourceReturns422() throws Exception {
    workerGateway.respondWith(connection(successResponse(), new ByteArrayOutputStream()));

    WebApplicationException error = assertThrows(
        WebApplicationException.class,
        () -> handler.handle(null, storedRequest("test.pdf", "application/pdf", 1)));

    assertEquals(422, error.getResponse().getStatus());
  }

  @Test
  void workerRejectionReturns422() throws Exception {
    storage.put("source/object", new byte[] {1});
    workerGateway.respondWith(connection(
        DocumentWorkerResponse.failure("invalid_request", "Invalid request"),
        new ByteArrayOutputStream()));

    WebApplicationException error = assertThrows(
        WebApplicationException.class,
        () -> handler.handle(null, storedRequest("test.pdf", "application/pdf", 1)));

    assertEquals(422, error.getResponse().getStatus());
  }

  @Test
  void workerIoFailureReturns502() {
    storage.put("source/object", new byte[] {1});
    workerGateway.failWith(new IOException("socket unavailable"));

    WebApplicationException error = assertThrows(
        WebApplicationException.class,
        () -> handler.handle(null, storedRequest("test.pdf", "application/pdf", 1)));

    assertEquals(502, error.getResponse().getStatus());
  }

  @Test
  void workerStreamCorruptionIsNotTreatedAsAMetadataMismatch() {
    storage.put("source/object", new byte[] {1});
    workerGateway.failWith(new StreamCorruptedException("worker response is corrupt"));

    WebApplicationException error = assertThrows(
        WebApplicationException.class,
        () -> handler.handle(null, storedRequest("test.pdf", "application/pdf", 1)));

    assertEquals(502, error.getResponse().getStatus());
  }

  @Test
  void leavesValidArtifactForAConcurrentRetryWhenTextPersistenceFails() throws Exception {
    storage.put("source/object", new byte[] {1});
    storage.failStore("source/object.txt");
    workerGateway.respondWith(connection(successResponse(), new ByteArrayOutputStream()));

    WebApplicationException error = assertThrows(
        WebApplicationException.class,
        () -> handler.handle(null, storedRequest("test.pdf", "application/pdf", 1)));

    assertEquals(502, error.getResponse().getStatus());
    assertArrayEquals(
        "artifact".getBytes(StandardCharsets.UTF_8),
        storage.object("source/object.artifact"));
  }

  private WebsocketRequest storedRequest(String filename,
                                         String contentType,
                                         long totalLength)
  {
    return storedRequest(filename, contentType, totalLength, null);
  }

  private WebsocketRequest storedRequest(String filename,
                                         String contentType,
                                         long totalLength,
                                         Number inlineTextMaxCharacters)
  {
    return storedRequestWithInlineLimit(
        filename,
        contentType,
        totalLength,
        inlineTextMaxCharacters == null ? null : inlineTextMaxCharacters.toString());
  }

  private WebsocketRequest storedRequestWithInlineLimit(String filename,
                                                        String contentType,
                                                        long totalLength,
                                                        String inlineTextMaxCharacters)
  {
    String body = "{\"filename\":\"" + filename
        + "\",\"content_type\":\"" + contentType
        + "\",\"total_length\":" + totalLength
        + ",\"source_object_key\":\"source/object\""
        + ",\"encryption_key\":\"key\""
        + (inlineTextMaxCharacters == null
            ? ""
            : ",\"inline_text_max_characters\":" + inlineTextMaxCharacters)
        + "}";
    return new WebsocketRequest(
        1,
        "POST",
        "/v2/document/extract",
        Optional.of(body));
  }

  private void assertBadRequest(WebsocketRequest request) {
    WebApplicationException error = assertThrows(
        WebApplicationException.class,
        () -> handler.handle(null, request));

    assertEquals(400, error.getResponse().getStatus());
  }

  private static DocumentWorkerResponse<DocumentExtractionMetadata> successResponse() {
    return successResponse("text");
  }

  private static DocumentWorkerResponse<DocumentExtractionMetadata> successResponse(String text) {
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
            (long) text.length(),
            jsonEscapedTextBytes(textBytes)),
        payloads);
  }

  private static long jsonEscapedTextBytes(byte[] text) {
    long escaped = text.length;
    for (byte value : text) {
      int character = Byte.toUnsignedInt(value);
      if (character == 0x08
          || character == 0x09
          || character == 0x0a
          || character == 0x0c
          || character == 0x0d
          || character == 0x22
          || character == 0x5c)
      {
        escaped++;
      } else if (character < 0x20) {
        escaped += 5;
      }
    }
    return escaped;
  }

  private DocumentWorkerConnection connection(DocumentWorkerResponse<?> response,
                                              ByteArrayOutputStream requests)
    throws IOException
  {
    ByteArrayOutputStream encoded = new ByteArrayOutputStream();
    new DocumentWorkerOutputStream(mapper, encoded).writeResponse(response);
    return new DocumentWorkerConnection(
        new DocumentWorkerInputStream(mapper, new ByteArrayInputStream(encoded.toByteArray())),
        new DocumentWorkerOutputStream(mapper, requests),
        (objectKey, encryptionKey) -> {
          throw new AssertionError("Extraction connection must not reopen document storage");
        },
        mapper,
        validatorFactory.getValidator());
  }

  private DocumentWorkerTestFrame decodeRequest(ByteArrayOutputStream requests) throws IOException {
    return new DocumentWorkerTestFrameDecoder(
        mapper,
        new ByteArrayInputStream(requests.toByteArray())).read();
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

  private static class RecordingStorage
      implements DocumentStorageGateway, DocumentStorageWriter {

    private final Map<String, byte[]> objects = new LinkedHashMap<>();
    private final Map<String, Boolean> bufferedInputs = new LinkedHashMap<>();

    private String openedObjectKey;
    private String failedStoreObjectKey;

    @Override
    public DecryptedDocument open(String objectKey, String encryptionKey) throws IOException {
      openedObjectKey = objectKey;
      byte[] value = objects.get(objectKey);
      if (value == null) {
        throw new DocumentNotFoundException();
      }
      return new DecryptedDocument(new ByteArrayInputStream(value), value.length);
    }

    @Override
    public long store(String objectKey,
                      String encryptionKey,
                      InputStream content,
                      long maximumBytes)
      throws IOException
    {
      if (objectKey.equals(failedStoreObjectKey)) {
        throw new IOException("storage failure");
      }
      bufferedInputs.put(objectKey, content instanceof ByteArrayInputStream);
      byte[] value = content.readAllBytes();
      if (value.length > maximumBytes) {
        throw new IOException("Plaintext exceeds storage limit");
      }
      objects.put(objectKey, value);
      return value.length;
    }

    private void put(String objectKey, byte[] value) {
      objects.put(objectKey, value);
    }

    private String openedObjectKey() {
      return openedObjectKey;
    }

    private byte[] object(String objectKey) {
      return objects.get(objectKey);
    }

    private void failStore(String objectKey) {
      failedStoreObjectKey = objectKey;
    }

    private boolean receivedBufferedInput(String objectKey) {
      return bufferedInputs.getOrDefault(objectKey, false);
    }

  }

  private static class TestDocumentWorkerGateway implements DocumentWorkerGateway {

    private DocumentWorkerConnection connection;
    private IOException               failure;

    @Override
    public DocumentWorkerConnection connect() throws IOException {
      if (failure != null) {
        throw failure;
      }
      return connection;
    }

    private void respondWith(DocumentWorkerConnection connection) {
      this.connection = connection;
      this.failure = null;
    }

    private void failWith(IOException failure) {
      this.connection = null;
      this.failure = failure;
    }
  }
}
