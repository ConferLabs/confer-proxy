package org.moxie.confer.proxy.documents.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.moxie.confer.proxy.documents.DecryptedDocument;
import org.moxie.confer.proxy.documents.DocumentAccessException;
import org.moxie.confer.proxy.documents.DocumentNotFoundException;
import org.moxie.confer.proxy.documents.DocumentStorageGateway;
import org.moxie.confer.proxy.documents.requests.DocumentSearchQuery;
import org.moxie.confer.proxy.documents.worker.requests.DocumentWorkerRequest;
import org.moxie.confer.proxy.documents.worker.requests.DocumentWorkerRequestPayload;
import org.moxie.confer.proxy.documents.worker.requests.ExtractDocumentRequest;
import org.moxie.confer.proxy.documents.worker.responses.DocumentOverview;
import org.moxie.confer.proxy.documents.worker.responses.DocumentContainerReference;
import org.moxie.confer.proxy.documents.worker.responses.DocumentExtractionMetadata;
import org.moxie.confer.proxy.documents.worker.responses.DocumentSearchResult;
import org.moxie.confer.proxy.documents.worker.responses.DocumentTextWindow;
import org.moxie.confer.proxy.documents.worker.responses.DocumentWorkerResponse;
import org.moxie.confer.proxy.documents.worker.responses.DocumentWorkerResponsePayload;
import org.moxie.confer.proxy.documents.worker.responses.IndexedDocumentText;
import org.moxie.confer.proxy.documents.worker.responses.RenderedDocumentPage;
import org.moxie.confer.proxy.documents.worker.responses.RenderedDocumentPageMetadata;
import org.moxie.confer.proxy.entities.DocumentReference;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocumentWorkerConnectionTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final byte[] ARTIFACT = new byte[] {1, 2, 3};
  private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);
  private static final List<DocumentSearchQuery> SEARCH_QUERIES =
      List.of(new DocumentSearchQuery(List.of("match")));
  private static final DocumentReference REFERENCE = new DocumentReference(
      "document",
      "Document.pdf",
      "application/pdf",
      ARTIFACT.length,
      "objects/document",
      KEY);

  private static ValidatorFactory validatorFactory;

  @BeforeAll
  static void createValidatorFactory() {
    validatorFactory = Validation.buildDefaultValidatorFactory();
  }

  @AfterAll
  static void closeValidatorFactory() {
    validatorFactory.close();
  }

  @Test
  void streamsExtractionWithoutExposingStreamsOrStartingASession() throws Exception {
    ByteArrayOutputStream encodedResponse = new ByteArrayOutputStream();
    new DocumentWorkerOutputStream(MAPPER, encodedResponse).writeResponse(
        DocumentWorkerResponse.success(
            new DocumentExtractionMetadata(0L, 0L, 0L),
            Map.of(
                DocumentWorkerPayloadRole.ARTIFACT,
                payload(DocumentWorkerPayloadRole.ARTIFACT, ARTIFACT),
                DocumentWorkerPayloadRole.TEXT,
                payload(DocumentWorkerPayloadRole.TEXT, new byte[0]))));
    ByteArrayOutputStream encodedRequest = new ByteArrayOutputStream();
    DocumentWorkerConnection connection = new DocumentWorkerConnection(
        new DocumentWorkerInputStream(
            MAPPER,
            new ByteArrayInputStream(encodedResponse.toByteArray())),
        new DocumentWorkerOutputStream(MAPPER, encodedRequest),
        (objectKey, encryptionKey) -> {
          throw new AssertionError("Extraction must not access document storage");
        },
        MAPPER,
        validatorFactory.getValidator());
    DocumentWorkerRequestPayload source = new DocumentWorkerRequestPayload(
        DocumentWorkerPayloadRole.SOURCE,
        "application/pdf",
        ARTIFACT.length,
        new ByteArrayInputStream(ARTIFACT));

    DocumentExtractionResponse response = connection.extract(
        new ExtractDocumentRequest("Document.pdf", "application/pdf", source));
    ByteArrayOutputStream streamed = new ByteArrayOutputStream();
    response.writeTo(streamed);
    response.close();

    DocumentWorkerTestFrameDecoder requestInput = new DocumentWorkerTestFrameDecoder(
        MAPPER,
        new ByteArrayInputStream(encodedRequest.toByteArray()));
    DocumentWorkerTestFrame request = requestInput.read();
    assertEquals("extract", request.header().get("operation"));
    assertArrayEquals(
        ARTIFACT,
        request.payloads().get(DocumentWorkerPayloadRole.SOURCE).content());
    assertThrows(IOException.class, requestInput::read);

    DocumentWorkerResponse<DocumentExtractionMetadata> decodedResponse =
        new DocumentWorkerInputStream(
            MAPPER,
            new ByteArrayInputStream(streamed.toByteArray())).readResponse(
            DocumentExtractionMetadata.class);
    assertArrayEquals(
        ARTIFACT,
        decodedResponse.payloads().get(DocumentWorkerPayloadRole.ARTIFACT).content());
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "text_bytes",
      "text_characters",
      "json_escaped_text_bytes"
  })
  void rejectsExtractionMetadataWithMissingRequiredField(String missingField) throws Exception {
    Map<String, Object> metadata = new LinkedHashMap<>(Map.of(
        "text_bytes", 0,
        "text_characters", 0,
        "json_escaped_text_bytes", 0));
    metadata.remove(missingField);

    assertInvalidExtractionMetadata(metadata);
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "text_bytes",
      "text_characters",
      "json_escaped_text_bytes"
  })
  void rejectsNegativeExtractionMetadata(String negativeField) throws Exception {
    Map<String, Object> metadata = new LinkedHashMap<>(Map.of(
        "text_bytes", 0,
        "text_characters", 0,
        "json_escaped_text_bytes", 0));
    metadata.put(negativeField, -1);

    assertInvalidExtractionMetadata(metadata);
  }

  @Test
  void rejectsExtractionTextLengthMismatch() throws Exception {
    assertInvalidExtractionMetadata(Map.of(
        "text_bytes", 1,
        "text_characters", 0,
        "json_escaped_text_bytes", 0));
  }

  @Test
  void performsTypedOperationsOverOneConnection() throws Exception {
    ByteArrayOutputStream responses = new ByteArrayOutputStream();
    DocumentWorkerOutputStream responseOutput = new DocumentWorkerOutputStream(MAPPER, responses);
    responseOutput.writeResponse(okResponse());
    writeJsonResponse(responseOutput, """
        {
          "metadata":{
            "schema_version":1,
            "source_sha256":"0000000000000000000000000000000000000000000000000000000000000000",
            "media_type":"application/pdf",
            "title":"Document",
            "text_bytes":100,
            "container_count":2,
            "outline_count":1,
            "page_count":2,
            "future_field":true
          },
          "outline":[{"level":1,"title":"Section","container_number":1}],
          "outline_truncated":false,
          "labeled_containers":[{"number":1,"kind":"page","label":"12"}],
          "first_chunk_id":1,
          "content":"overview",
          "future_field":"ignored"
        }
        """);
    writeJsonResponse(responseOutput, """
        {
          "hits":[{
            "chunk_id":2,
            "ordinal":1,
            "score":-0.5,
            "container_start":1,
            "container_end":1,
            "text":"match",
            "regions":[{
              "id":"p1-table-1",
              "container_number":1,
              "type":"table",
              "confidence":0.75,
              "renderable":true
            }],
            "containers":[{"number":1,"kind":"page","label":null}]
          }],
          "has_more":true
        }
        """);
    responseOutput.writeResponse(readResponse());
    responseOutput.writeResponse(imageResponse());
    responseOutput.writeResponse(okResponse());
    ByteArrayOutputStream requests = new ByteArrayOutputStream();
    DocumentStorageGateway storage = (objectKey, encryptionKey) ->
        new DecryptedDocument(new ByteArrayInputStream(ARTIFACT), ARTIFACT.length);

    DocumentWorkerConnection connection = new DocumentWorkerConnection(
        new DocumentWorkerInputStream(MAPPER, new ByteArrayInputStream(responses.toByteArray())),
        new DocumentWorkerOutputStream(MAPPER, requests),
        storage,
        MAPPER,
        validatorFactory.getValidator());

    DocumentOverview overview = connection.overview(REFERENCE);
    DocumentSearchResult search = connection.search(REFERENCE, SEARCH_QUERIES, 512);
    IndexedDocumentText text = connection.read(REFERENCE, 1, 1);
    RenderedDocumentPage image = connection.renderPage(REFERENCE, 1);
    connection.close();

    assertEquals("overview", overview.content());
    assertEquals(2, overview.metadata().pageCount());
    assertEquals(2, overview.metadata().containerCount());
    assertEquals(1, overview.outline().getFirst().containerNumber());
    assertEquals("12", overview.labeledContainers().getFirst().label());
    assertEquals("match", search.hits().getFirst().text());
    assertEquals("p1-table-1", search.hits().getFirst().regions().getFirst().id());
    assertNull(search.hits().getFirst().containers().getFirst().label());
    assertTrue(search.hasMore());
    assertEquals(1, text.window().containerStart());
    assertEquals(2, text.window().totalContainers());
    assertEquals("window text", text.text());
    assertEquals("image/png", image.metadata().mediaType());
    assertEquals(640, image.metadata().width());
    assertArrayEquals(new byte[] {(byte) 0x89, 0x50}, image.png());

    DocumentWorkerTestFrameDecoder requestInput = new DocumentWorkerTestFrameDecoder(
        MAPPER,
        new ByteArrayInputStream(requests.toByteArray()));
    DocumentWorkerTestFrame open = requestInput.read();
    DocumentWorkerTestFrame overviewRequest = requestInput.read();
    DocumentWorkerTestFrame searchRequest = requestInput.read();
    DocumentWorkerTestFrame readRequest = requestInput.read();
    DocumentWorkerTestFrame renderRequest = requestInput.read();
    DocumentWorkerTestFrame close = requestInput.read();

    assertEquals("session_open", open.header().get("operation"));
    assertArrayEquals(
        ARTIFACT,
        open.payloads().get(DocumentWorkerPayloadRole.ARTIFACT).content());
    assertEquals("session_overview", overviewRequest.header().get("operation"));
    assertEquals("session_search", searchRequest.header().get("operation"));
    assertEquals(
        List.of(Map.of("all", List.of("match"))),
        searchRequest.header().get("queries"));
    assertEquals(512, searchRequest.header().get("snippet_characters"));
    assertEquals("session_read", readRequest.header().get("operation"));
    assertEquals(1, readRequest.header().get("container_number"));
    assertEquals(1, readRequest.header().get("container_count"));
    assertEquals("session_render", renderRequest.header().get("operation"));
    assertEquals(1, renderRequest.header().get("container_number"));
    assertArrayEquals(
        ARTIFACT,
        renderRequest.payloads().get(DocumentWorkerPayloadRole.SOURCE).content());
    assertEquals("session_close", close.header().get("operation"));
  }

  @Test
  void rejectsIncompleteOverviewMetadata() throws Exception {
    ByteArrayOutputStream responses = new ByteArrayOutputStream();
    DocumentWorkerOutputStream responseOutput = new DocumentWorkerOutputStream(MAPPER, responses);
    responseOutput.writeResponse(okResponse());
    writeJsonResponse(responseOutput, """
        {
          "metadata":{
            "schema_version":1,
            "source_sha256":"hash",
            "media_type":"application/pdf",
            "title":"Document",
            "text_bytes":100,
            "outline_count":0
          },
          "outline":[],
          "outline_truncated":false,
          "labeled_containers":[],
          "first_chunk_id":1
        }
        """);
    DocumentStorageGateway storage = (objectKey, encryptionKey) ->
        new DecryptedDocument(new ByteArrayInputStream(ARTIFACT), ARTIFACT.length);

    try (DocumentWorkerConnection connection = new DocumentWorkerConnection(
        new DocumentWorkerInputStream(MAPPER, new ByteArrayInputStream(responses.toByteArray())),
        new DocumentWorkerOutputStream(MAPPER, new ByteArrayOutputStream()),
        storage,
        MAPPER,
        validatorFactory.getValidator()))
    {
      assertThrows(
          DocumentWorkerProtocolException.class,
          () -> connection.overview(REFERENCE));
    }
  }

  @Test
  void searchOpensDocumentWithoutRequestingAnOverview() throws Exception {
    ByteArrayOutputStream responses = new ByteArrayOutputStream();
    DocumentWorkerOutputStream responseOutput = new DocumentWorkerOutputStream(MAPPER, responses);
    responseOutput.writeResponse(okResponse());
    writeJsonResponse(responseOutput, "{\"hits\":[],\"has_more\":false}");
    responseOutput.writeResponse(okResponse());
    ByteArrayOutputStream requests = new ByteArrayOutputStream();
    DocumentStorageGateway storage = (objectKey, encryptionKey) ->
        new DecryptedDocument(new ByteArrayInputStream(ARTIFACT), ARTIFACT.length);
    DocumentWorkerConnection connection = new DocumentWorkerConnection(
        new DocumentWorkerInputStream(MAPPER, new ByteArrayInputStream(responses.toByteArray())),
        new DocumentWorkerOutputStream(MAPPER, requests),
        storage,
        MAPPER,
        validatorFactory.getValidator());

    connection.search(REFERENCE, SEARCH_QUERIES, 1_200);
    connection.close();

    DocumentWorkerTestFrameDecoder requestInput = new DocumentWorkerTestFrameDecoder(
        MAPPER,
        new ByteArrayInputStream(requests.toByteArray()));
    assertEquals("session_open", requestInput.read().header().get("operation"));
    assertEquals("session_search", requestInput.read().header().get("operation"));
    assertEquals("session_close", requestInput.read().header().get("operation"));
  }

  @Test
  void reportsLegacyPageRenderingAsCheckedDocumentAccessFailure() throws Exception {
    ByteArrayOutputStream responses = new ByteArrayOutputStream();
    DocumentWorkerOutputStream responseOutput = new DocumentWorkerOutputStream(MAPPER, responses);
    responseOutput.writeResponse(okResponse());
    responseOutput.writeResponse(okResponse());
    DocumentStorageGateway storage = (objectKey, encryptionKey) -> {
      if (objectKey.equals(REFERENCE.sourceObjectKey() + ".artifact")) {
        throw new DocumentNotFoundException();
      }
      return new DecryptedDocument(new ByteArrayInputStream(ARTIFACT), ARTIFACT.length);
    };
    DocumentWorkerConnection connection = new DocumentWorkerConnection(
        new DocumentWorkerInputStream(MAPPER, new ByteArrayInputStream(responses.toByteArray())),
        new DocumentWorkerOutputStream(MAPPER, new ByteArrayOutputStream()),
        storage,
        MAPPER,
        validatorFactory.getValidator());

    assertThrows(
        DocumentAccessException.class,
        () -> connection.renderPage(REFERENCE, 0));

    connection.close();
  }

  private static void writeJsonResponse(DocumentWorkerOutputStream output, String json)
    throws Exception
  {
    output.writeResponse(DocumentWorkerResponse.success(
        Map.of(
            DocumentWorkerPayloadRole.RESULT,
            payload(DocumentWorkerPayloadRole.RESULT, json.getBytes(StandardCharsets.UTF_8)))));
  }

  private static DocumentWorkerResponse<Void> okResponse() {
    return DocumentWorkerResponse.success(Map.of());
  }

  private static DocumentWorkerResponse<DocumentTextWindow> readResponse() {
    return DocumentWorkerResponse.success(
        new DocumentTextWindow(
            1,
            1,
            2,
            List.of(),
            List.of(new DocumentContainerReference(1, "page", "12"))),
        Map.of(
            DocumentWorkerPayloadRole.TEXT,
            payload(
                DocumentWorkerPayloadRole.TEXT,
                "window text".getBytes(StandardCharsets.UTF_8))));
  }

  private static DocumentWorkerResponse<RenderedDocumentPageMetadata> imageResponse() {
    return DocumentWorkerResponse.success(
        new RenderedDocumentPageMetadata("image/png", 640, 480, 1),
        Map.of(
            DocumentWorkerPayloadRole.IMAGE,
            payload(DocumentWorkerPayloadRole.IMAGE, new byte[] {(byte) 0x89, 0x50})));
  }

  private static DocumentWorkerResponsePayload payload(DocumentWorkerPayloadRole role,
                                                        byte[] content)
  {
    return new DocumentWorkerResponsePayload(role.requiredMediaType(), content);
  }

  private static void assertInvalidExtractionMetadata(Map<String, Object> metadata)
    throws Exception
  {
    ByteArrayOutputStream encodedResponse = new ByteArrayOutputStream();
    writeExtractionResponse(encodedResponse, metadata);

    try (DocumentWorkerConnection connection = new DocumentWorkerConnection(
        new DocumentWorkerInputStream(
            MAPPER,
            new ByteArrayInputStream(encodedResponse.toByteArray())),
        new DocumentWorkerOutputStream(MAPPER, new ByteArrayOutputStream()),
        (objectKey, encryptionKey) -> {
          throw new AssertionError("Extraction must not access document storage");
        },
        MAPPER,
        validatorFactory.getValidator()))
    {
      DocumentWorkerRequestPayload source = new DocumentWorkerRequestPayload(
          DocumentWorkerPayloadRole.SOURCE,
          "application/pdf",
          ARTIFACT.length,
          new ByteArrayInputStream(ARTIFACT));

      assertThrows(
          DocumentWorkerProtocolException.class,
          () -> connection.extract(
              new ExtractDocumentRequest("Document.pdf", "application/pdf", source)));
    }
  }

  private static void writeExtractionResponse(ByteArrayOutputStream output,
                                              Map<String, Object> metadata)
    throws IOException
  {
    byte[] header = MAPPER.writeValueAsBytes(Map.of(
        "version", DocumentWorkerRequest.VERSION,
        "status", "ok",
        "result", metadata,
        "payloads", List.of(
            Map.of(
                "role", "artifact",
                "media_type", DocumentWorkerPayloadRole.ARTIFACT.requiredMediaType(),
                "length", ARTIFACT.length),
            Map.of(
                "role", "text",
                "media_type", DocumentWorkerPayloadRole.TEXT.requiredMediaType(),
                "length", 0))));
    DataOutputStream framed = new DataOutputStream(output);
    framed.writeInt(header.length);
    framed.write(header);
    framed.write(ARTIFACT);
  }
}
