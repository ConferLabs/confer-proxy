package org.moxie.confer.proxy.documents.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.moxie.confer.proxy.documents.requests.DocumentSearchQuery;
import org.moxie.confer.proxy.documents.worker.requests.DocumentWorkerRequest;
import org.moxie.confer.proxy.documents.worker.requests.DocumentWorkerRequestPayload;
import org.moxie.confer.proxy.documents.worker.requests.ExtractDocumentRequest;
import org.moxie.confer.proxy.documents.worker.requests.OpenDocumentRequest;
import org.moxie.confer.proxy.documents.worker.requests.ReadDocumentRequest;
import org.moxie.confer.proxy.documents.worker.requests.SearchDocumentRequest;
import org.moxie.confer.proxy.documents.worker.responses.DocumentWorkerResponse;
import org.moxie.confer.proxy.documents.worker.responses.DocumentWorkerResponsePayload;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocumentWorkerStreamsTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Test
  void encodesTypedWorkerRequestAsWireHeader() throws Exception {
    ByteArrayOutputStream encoded = new ByteArrayOutputStream();
    DocumentWorkerRequest request = new ReadDocumentRequest(
        "document",
        6,
        2);

    new DocumentWorkerOutputStream(MAPPER, encoded).writeRequest(request);
    DocumentWorkerTestFrame decoded = new DocumentWorkerTestFrameDecoder(
        MAPPER,
        new ByteArrayInputStream(encoded.toByteArray())).read();

    assertEquals(DocumentWorkerRequest.VERSION, decoded.header().get("version"));
    assertEquals("session_read", decoded.header().get("operation"));
    assertEquals("document", decoded.header().get("document_id"));
    assertEquals(6, decoded.header().get("container_number"));
    assertEquals(2, decoded.header().get("container_count"));
    assertFalse(decoded.header().containsKey("chunk_id"));
  }

  @Test
  void encodesPayloadOwnedByTypedWorkerRequest() throws Exception {
    byte[] content = new byte[] {1, 2, 3};
    DocumentWorkerRequestPayload source = new DocumentWorkerRequestPayload(
        DocumentWorkerPayloadRole.SOURCE,
        "application/pdf",
        content.length,
        new ByteArrayInputStream(content));
    DocumentWorkerRequest request = new ExtractDocumentRequest(
        "document.pdf",
        "application/pdf",
        source);
    ByteArrayOutputStream encoded = new ByteArrayOutputStream();

    new DocumentWorkerOutputStream(MAPPER, encoded).writeRequest(request);
    DocumentWorkerTestFrame decoded = new DocumentWorkerTestFrameDecoder(
        MAPPER,
        new ByteArrayInputStream(encoded.toByteArray())).read();

    assertEquals("extract", decoded.header().get("operation"));
    assertEquals("document.pdf", decoded.header().get("filename"));
    assertFalse(decoded.header().containsKey("source"));
    assertArrayEquals(
        content,
        decoded.payloads().get(DocumentWorkerPayloadRole.SOURCE).content());
  }

  @Test
  void requestRejectsPayloadWithWrongRole() {
    DocumentWorkerRequestPayload source = new DocumentWorkerRequestPayload(
        DocumentWorkerPayloadRole.SOURCE,
        "application/pdf",
        0,
        new ByteArrayInputStream(new byte[0]));

    DocumentWorkerRequest request = new OpenDocumentRequest("document", source);

    assertThrows(
        DocumentWorkerProtocolException.class,
        () -> new DocumentWorkerOutputStream(
            MAPPER,
            new ByteArrayOutputStream()).writeRequest(request));
  }

  @Test
  void requestRejectsInvalidReadRangeAsCheckedProtocolFailure() {
    DocumentWorkerRequest request = new ReadDocumentRequest(
        "document",
        7,
        0);

    assertThrows(
        DocumentWorkerProtocolException.class,
        () -> new DocumentWorkerOutputStream(
            MAPPER,
            new ByteArrayOutputStream()).writeRequest(request));
  }

  @Test
  void requestRejectsInvalidSearchSnippetLengthAsCheckedProtocolFailure() {
    DocumentWorkerRequest request = new SearchDocumentRequest(
        "document",
        List.of(new DocumentSearchQuery(List.of("match"))),
        12,
        63);

    assertThrows(
        DocumentWorkerProtocolException.class,
        () -> new DocumentWorkerOutputStream(
            MAPPER,
            new ByteArrayOutputStream()).writeRequest(request));
  }

  @Test
  void requestRejectsSourceMediaTypeThatDoesNotMatchContentType() {
    DocumentWorkerRequestPayload source = new DocumentWorkerRequestPayload(
        DocumentWorkerPayloadRole.SOURCE,
        "text/plain",
        0,
        new ByteArrayInputStream(new byte[0]));
    DocumentWorkerRequest request = new ExtractDocumentRequest(
        "document.pdf",
        "application/pdf",
        source);

    assertThrows(
        DocumentWorkerProtocolException.class,
        () -> new DocumentWorkerOutputStream(
            MAPPER,
            new ByteArrayOutputStream()).writeRequest(request));
  }

  @Test
  void roundTripsWorkerResponseWithoutBase64Encoding() throws Exception {
    Map<DocumentWorkerPayloadRole, byte[]> payloads = new LinkedHashMap<>();
    payloads.put(DocumentWorkerPayloadRole.ARTIFACT, new byte[] {0, 1, 2, 3});
    payloads.put(DocumentWorkerPayloadRole.TEXT, "hello".getBytes(StandardCharsets.UTF_8));
    DocumentWorkerResponse<Void> expected = response(null, payloads);
    ByteArrayOutputStream encoded = new ByteArrayOutputStream();

    new DocumentWorkerOutputStream(MAPPER, encoded).writeResponse(expected);
    DocumentWorkerResponse<Void> actual = new DocumentWorkerInputStream(
        MAPPER,
        new ByteArrayInputStream(encoded.toByteArray())).readResponse();

    assertEquals(expected.status(), actual.status());
    assertArrayEquals(
        expected.payloads().get(DocumentWorkerPayloadRole.ARTIFACT).content(),
        actual.payloads().get(DocumentWorkerPayloadRole.ARTIFACT).content());
    assertEquals(
        "application/vnd.confer.document-artifact",
        actual.payloads().get(DocumentWorkerPayloadRole.ARTIFACT).mediaType());
    assertArrayEquals(
        expected.payloads().get(DocumentWorkerPayloadRole.TEXT).content(),
        actual.payloads().get(DocumentWorkerPayloadRole.TEXT).content());
  }

  @Test
  void rejectsTruncatedWorkerPayload() throws Exception {
    DocumentWorkerResponse<Void> response = response(
        null,
        Map.of(DocumentWorkerPayloadRole.ARTIFACT, new byte[] {1, 2, 3}));
    ByteArrayOutputStream encoded = new ByteArrayOutputStream();
    new DocumentWorkerOutputStream(MAPPER, encoded).writeResponse(response);
    byte[] truncated = Arrays.copyOf(encoded.toByteArray(), encoded.size() - 1);
    DocumentWorkerInputStream input = new DocumentWorkerInputStream(
        MAPPER,
        new ByteArrayInputStream(truncated));

    assertThrows(IOException.class, input::readResponse);
  }

  @Test
  void rejectsMalformedResponsePayloadDescriptorsAsIoFailure() throws Exception {
    byte[] header = MAPPER.writeValueAsBytes(Map.of(
        "version", DocumentWorkerRequest.VERSION,
        "status", "ok",
        "payloads", List.of(Map.of(
            "role", "artifact",
            "media_type", "application/vnd.confer.document-artifact",
            "length", "not-a-number"))));
    ByteArrayOutputStream encoded = new ByteArrayOutputStream();
    DataOutputStream output = new DataOutputStream(encoded);
    output.writeInt(header.length);
    output.write(header);
    DocumentWorkerInputStream input = new DocumentWorkerInputStream(
        MAPPER,
        new ByteArrayInputStream(encoded.toByteArray()));

    IOException error = assertThrows(IOException.class, input::beginResponse);

    assertEquals("Document worker response header is invalid", error.getMessage());
  }

  @Test
  void rejectsMediaTypeThatDoesNotMatchPayloadRole() throws Exception {
    byte[] header = MAPPER.writeValueAsBytes(Map.of(
        "version", DocumentWorkerRequest.VERSION,
        "status", "ok",
        "payloads", List.of(Map.of(
            "role", "artifact",
            "media_type", "image/png",
            "length", 1))));
    ByteArrayOutputStream encoded = new ByteArrayOutputStream();
    DataOutputStream output = new DataOutputStream(encoded);
    output.writeInt(header.length);
    output.write(header);
    output.write(1);
    DocumentWorkerInputStream input = new DocumentWorkerInputStream(
        MAPPER,
        new ByteArrayInputStream(encoded.toByteArray()));

    IOException error = assertThrows(IOException.class, input::beginResponse);

    assertEquals("Document worker payload media type is invalid", error.getMessage());
  }

  @Test
  void readsOnlyHeaderBeforeStreamingCurrentResponse() throws Exception {
    byte[] artifact = new byte[2 * 1024 * 1024];
    DocumentWorkerResponse<Void> response = response(
        null,
        Map.of(DocumentWorkerPayloadRole.ARTIFACT, artifact));
    ByteArrayOutputStream encoded = new ByteArrayOutputStream();
    new DocumentWorkerOutputStream(MAPPER, encoded).writeResponse(response);
    ByteArrayInputStream source = new ByteArrayInputStream(encoded.toByteArray());
    DocumentWorkerInputStream input = new DocumentWorkerInputStream(MAPPER, source);

    DocumentWorkerFrame<Void> head = input.beginResponse();

    assertEquals(artifact.length, source.available());
    assertEquals(encoded.size(), head.contentLength());
    ByteArrayOutputStream relayed = new ByteArrayOutputStream();
    input.writeCurrentResponseTo(relayed);
    assertArrayEquals(encoded.toByteArray(), relayed.toByteArray());
  }

  @Test
  void streamsOnePayloadWithBoundedReadsWhileDiscardingTheOthers() throws Exception {
    byte[] artifact = new byte[2 * 1024 * 1024];
    Arrays.fill(artifact, (byte) 7);
    byte[] text = "selected text".getBytes(StandardCharsets.UTF_8);
    Map<DocumentWorkerPayloadRole, byte[]> payloads = new LinkedHashMap<>();
    payloads.put(DocumentWorkerPayloadRole.ARTIFACT, artifact);
    payloads.put(DocumentWorkerPayloadRole.TEXT, text);
    ByteArrayOutputStream encoded = new ByteArrayOutputStream();
    DocumentWorkerOutputStream output = new DocumentWorkerOutputStream(MAPPER, encoded);
    output.writeResponse(response(null, payloads));
    output.writeResponse(response(
        "next",
        Map.of(DocumentWorkerPayloadRole.RESULT, new byte[] {9})));
    GuardedInputStream source = new GuardedInputStream(
        encoded.toByteArray(),
        64 * 1024);
    DocumentWorkerInputStream input = new DocumentWorkerInputStream(MAPPER, source);
    input.beginResponse(String.class);
    ByteArrayOutputStream selected = new ByteArrayOutputStream();

    input.writeCurrentPayloadTo(DocumentWorkerPayloadRole.TEXT, selected);
    DocumentWorkerResponse<String> next = input.readResponse(String.class);

    assertArrayEquals(text, selected.toByteArray());
    assertEquals("next", next.result());
    assertArrayEquals(
        new byte[] {9},
        next.requiredPayload(DocumentWorkerPayloadRole.RESULT).content());
    assertEquals(64 * 1024, source.maximumRequestedRead());
  }

  @Test
  void exposesEveryPayloadAsABoundedInputStream() throws Exception {
    byte[] artifact = new byte[2 * 1024 * 1024];
    Arrays.fill(artifact, (byte) 7);
    byte[] text = "selected text".getBytes(StandardCharsets.UTF_8);
    Map<DocumentWorkerPayloadRole, byte[]> payloads = new LinkedHashMap<>();
    payloads.put(DocumentWorkerPayloadRole.ARTIFACT, artifact);
    payloads.put(DocumentWorkerPayloadRole.TEXT, text);
    ByteArrayOutputStream encoded = new ByteArrayOutputStream();
    new DocumentWorkerOutputStream(MAPPER, encoded).writeResponse(response(null, payloads));
    GuardedInputStream source = new GuardedInputStream(encoded.toByteArray(), 64 * 1024);
    DocumentWorkerInputStream input = new DocumentWorkerInputStream(MAPPER, source);
    input.beginResponse();
    Map<DocumentWorkerPayloadRole, byte[]> received = new LinkedHashMap<>();

    input.consumeCurrentPayloads((descriptor, content) ->
        received.put(descriptor.role(), content.readAllBytes()));

    assertArrayEquals(artifact, received.get(DocumentWorkerPayloadRole.ARTIFACT));
    assertArrayEquals(text, received.get(DocumentWorkerPayloadRole.TEXT));
    assertTrue(source.maximumRequestedRead() <= 64 * 1024);
  }

  @Test
  void requiresEachStreamedPayloadToBeConsumed() throws Exception {
    ByteArrayOutputStream encoded = new ByteArrayOutputStream();
    new DocumentWorkerOutputStream(MAPPER, encoded).writeResponse(response(
        null,
        Map.of(DocumentWorkerPayloadRole.ARTIFACT, new byte[] {1})));
    DocumentWorkerInputStream input = new DocumentWorkerInputStream(
        MAPPER,
        new ByteArrayInputStream(encoded.toByteArray()));
    input.beginResponse();

    IOException error = assertThrows(
        IOException.class,
        () -> input.consumeCurrentPayloads((descriptor, content) -> {}));

    assertEquals(
        "Document worker response payload was not fully consumed",
        error.getMessage());
  }

  @Test
  void closingAPayloadDoesNotCloseTheWorkerInput() throws Exception {
    ByteArrayOutputStream encoded = new ByteArrayOutputStream();
    Map<DocumentWorkerPayloadRole, byte[]> payloads = new LinkedHashMap<>();
    payloads.put(DocumentWorkerPayloadRole.ARTIFACT, new byte[] {1});
    payloads.put(DocumentWorkerPayloadRole.TEXT, new byte[] {2});
    new DocumentWorkerOutputStream(MAPPER, encoded).writeResponse(response(null, payloads));
    DocumentWorkerInputStream input = new DocumentWorkerInputStream(
        MAPPER,
        new ByteArrayInputStream(encoded.toByteArray()));
    input.beginResponse();
    Map<DocumentWorkerPayloadRole, byte[]> received = new LinkedHashMap<>();

    input.consumeCurrentPayloads((descriptor, content) -> {
      try (InputStream payload = content) {
        received.put(descriptor.role(), payload.readAllBytes());
      }
    });

    assertArrayEquals(new byte[] {1}, received.get(DocumentWorkerPayloadRole.ARTIFACT));
    assertArrayEquals(new byte[] {2}, received.get(DocumentWorkerPayloadRole.TEXT));
  }

  @Test
  void reportsTruncatedCallbackScopedPayload() throws Exception {
    ByteArrayOutputStream encoded = new ByteArrayOutputStream();
    new DocumentWorkerOutputStream(MAPPER, encoded).writeResponse(response(
        null,
        Map.of(DocumentWorkerPayloadRole.ARTIFACT, new byte[] {1, 2})));
    byte[] truncated = Arrays.copyOf(encoded.toByteArray(), encoded.size() - 1);
    DocumentWorkerInputStream input = new DocumentWorkerInputStream(
        MAPPER,
        new ByteArrayInputStream(truncated));
    input.beginResponse();

    IOException error = assertThrows(
        IOException.class,
        () -> input.consumeCurrentPayloads((descriptor, content) -> content.readAllBytes()));

    assertEquals("Document worker response payload is truncated", error.getMessage());
  }

  @Test
  void payloadInputStreamCannotOutliveItsCallback() throws Exception {
    ByteArrayOutputStream encoded = new ByteArrayOutputStream();
    new DocumentWorkerOutputStream(MAPPER, encoded).writeResponse(response(
        null,
        Map.of(DocumentWorkerPayloadRole.ARTIFACT, new byte[] {1})));
    DocumentWorkerInputStream input = new DocumentWorkerInputStream(
        MAPPER,
        new ByteArrayInputStream(encoded.toByteArray()));
    input.beginResponse();
    AtomicReference<InputStream> payload = new AtomicReference<>();

    input.consumeCurrentPayloads((descriptor, content) -> {
      payload.set(content);
      content.readAllBytes();
    });
    IOException error = assertThrows(IOException.class, () -> payload.get().read());

    assertEquals(
        "Document worker response payload is no longer available",
        error.getMessage());
  }

  @Test
  void exchangesMultipleFramesOverOneInputAndOutputStream() throws Exception {
    ByteArrayOutputStream encoded = new ByteArrayOutputStream();
    DocumentWorkerOutputStream output = new DocumentWorkerOutputStream(MAPPER, encoded);
    output.writeResponse(response(
        "first",
        Map.of(DocumentWorkerPayloadRole.RESULT, new byte[] {1})));
    output.writeResponse(response(
        "second",
        Map.of(DocumentWorkerPayloadRole.RESULT, new byte[] {2})));
    DocumentWorkerInputStream input = new DocumentWorkerInputStream(
        MAPPER,
        new ByteArrayInputStream(encoded.toByteArray()));

    DocumentWorkerResponse<String> first = input.readResponse(String.class);
    DocumentWorkerResponse<String> second = input.readResponse(String.class);

    assertEquals("first", first.result());
    assertArrayEquals(
        new byte[] {1},
        first.payloads().get(DocumentWorkerPayloadRole.RESULT).content());
    assertEquals("second", second.result());
    assertArrayEquals(
        new byte[] {2},
        second.payloads().get(DocumentWorkerPayloadRole.RESULT).content());
  }

  @Test
  void streamingOneFrameDoesNotConsumeTheNextFrame() throws Exception {
    ByteArrayOutputStream encoded = new ByteArrayOutputStream();
    DocumentWorkerOutputStream output = new DocumentWorkerOutputStream(MAPPER, encoded);
    output.writeResponse(response(
        "first",
        Map.of(DocumentWorkerPayloadRole.RESULT, new byte[] {1, 2, 3})));
    output.writeResponse(response(
        "second",
        Map.of(DocumentWorkerPayloadRole.RESULT, new byte[] {4, 5, 6})));
    DocumentWorkerInputStream input = new DocumentWorkerInputStream(
        MAPPER,
        new ByteArrayInputStream(encoded.toByteArray()));

    DocumentWorkerFrame<String> firstHead = input.beginResponse(String.class);
    ByteArrayOutputStream firstFrame = new ByteArrayOutputStream();
    input.writeCurrentResponseTo(firstFrame);
    DocumentWorkerResponse<String> second = input.readResponse(String.class);

    assertEquals(firstHead.contentLength(), firstFrame.size());
    assertEquals("second", second.result());
    assertArrayEquals(
        new byte[] {4, 5, 6},
        second.payloads().get(DocumentWorkerPayloadRole.RESULT).content());
  }

  @Test
  void rejectsNextHeaderUntilCurrentPayloadIsConsumed() throws Exception {
    ByteArrayOutputStream encoded = new ByteArrayOutputStream();
    DocumentWorkerOutputStream output = new DocumentWorkerOutputStream(MAPPER, encoded);
    output.writeResponse(response(
        "first",
        Map.of(DocumentWorkerPayloadRole.RESULT, new byte[] {1})));
    output.writeResponse(response("second", Map.of()));
    DocumentWorkerInputStream input = new DocumentWorkerInputStream(
        MAPPER,
        new ByteArrayInputStream(encoded.toByteArray()));
    input.beginResponse(String.class);

    IOException error = assertThrows(IOException.class, input::beginResponse);

    assertEquals("Document worker response payload was not fully consumed", error.getMessage());
    input.readCurrentResponse();
    assertEquals("second", input.beginResponse(String.class).result());
  }

  private static <T> DocumentWorkerResponse<T> response(
      T result,
      Map<DocumentWorkerPayloadRole, byte[]> content)
  {
    Map<DocumentWorkerPayloadRole, DocumentWorkerResponsePayload> payloads = new LinkedHashMap<>();
    for (Map.Entry<DocumentWorkerPayloadRole, byte[]> entry : content.entrySet()) {
      payloads.put(
          entry.getKey(),
          new DocumentWorkerResponsePayload(
              entry.getKey().requiredMediaType(),
              entry.getValue()));
    }
    return DocumentWorkerResponse.success(result, payloads);
  }

  private static final class GuardedInputStream extends ByteArrayInputStream {

    private final int maximumRead;

    private int maximumRequestedRead;

    private GuardedInputStream(byte[] content, int maximumRead) {
      super(content);
      this.maximumRead = maximumRead;
    }

    @Override
    public synchronized int read(byte[] buffer, int offset, int length) {
      if (length > maximumRead) {
        throw new AssertionError("Unbounded protocol read requested: " + length);
      }
      maximumRequestedRead = Math.max(maximumRequestedRead, length);
      return super.read(buffer, offset, length);
    }

    private int maximumRequestedRead() {
      return maximumRequestedRead;
    }
  }
}
