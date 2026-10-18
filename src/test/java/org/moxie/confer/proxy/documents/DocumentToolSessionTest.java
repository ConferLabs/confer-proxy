package org.moxie.confer.proxy.documents;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.moxie.confer.proxy.documents.requests.DocumentOverviewRequest;
import org.moxie.confer.proxy.documents.requests.DocumentPageViewRequest;
import org.moxie.confer.proxy.documents.requests.DocumentReadRequest;
import org.moxie.confer.proxy.documents.requests.DocumentSearchQuery;
import org.moxie.confer.proxy.documents.requests.DocumentSearchRequest;
import org.moxie.confer.proxy.documents.responses.DocumentOverviewResult;
import org.moxie.confer.proxy.documents.responses.DocumentReadResult;
import org.moxie.confer.proxy.documents.responses.DocumentSearchCollectionResult;
import org.moxie.confer.proxy.documents.responses.DocumentSearchDocumentResult;
import org.moxie.confer.proxy.documents.worker.DocumentWorkerConnection;
import org.moxie.confer.proxy.documents.worker.DocumentWorkerGateway;
import org.moxie.confer.proxy.documents.worker.DocumentWorkerInputStream;
import org.moxie.confer.proxy.documents.worker.DocumentWorkerOutputStream;
import org.moxie.confer.proxy.documents.worker.DocumentWorkerPayloadRole;
import org.moxie.confer.proxy.documents.worker.DocumentWorkerScheduler;
import org.moxie.confer.proxy.documents.worker.responses.DocumentContainerReference;
import org.moxie.confer.proxy.documents.worker.responses.DocumentSearchHit;
import org.moxie.confer.proxy.documents.worker.responses.DocumentSearchResult;
import org.moxie.confer.proxy.documents.worker.responses.DocumentTextWindow;
import org.moxie.confer.proxy.documents.worker.responses.DocumentWorkerResponse;
import org.moxie.confer.proxy.documents.worker.responses.DocumentWorkerResponsePayload;
import org.moxie.confer.proxy.documents.worker.responses.RenderedDocumentPageMetadata;
import org.moxie.confer.proxy.entities.DocumentReference;
import org.moxie.confer.proxy.images.ImageReference;
import org.moxie.confer.proxy.images.InvalidImageReferenceException;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DocumentToolSessionTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final Duration ACQUIRE_TIMEOUT = Duration.ofSeconds(1);
  private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);
  private static final ImageReference IMAGE = imageReference();
  private static final List<DocumentSearchQuery> SEARCH_QUERIES =
      List.of(new DocumentSearchQuery(List.of("match")));

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
  void streamsArtifactOnlyWhenUsed() throws Exception {
    RecordingStorage storage = new RecordingStorage(Map.of("user/doc.artifact", new byte[] {1, 2, 3}));
    RecordingWorker worker = new RecordingWorker(storage, List.of(
        okResponse(),
        jsonResponse(overviewJson("Jung text")),
        okResponse()));
    DocumentToolSession session = session(worker);

    assertTrue(storage.openedKeys.isEmpty());
    assertEquals(0, worker.connectionsOpened);

    DocumentOverviewResult result = session.overview(new DocumentOverviewRequest("doc"));

    assertEquals("Jung.pdf", result.filename());
    assertEquals("Jung text", result.content());
    assertEquals(1, result.metadata().pageCount());
    assertEquals(List.of("user/doc.artifact"), storage.openedKeys);
    assertEquals(1, worker.connectionsOpened);
  }

  @Test
  void fallsBackToLegacyExtractedTextWhenArtifactIsMissing() throws Exception {
    RecordingStorage storage = new RecordingStorage(Map.of(
        "user/doc.txt",
        "legacy text".getBytes(StandardCharsets.UTF_8)));
    RecordingWorker worker = new RecordingWorker(storage, List.of(
        okResponse(),
        jsonResponse(overviewJson("legacy text")),
        okResponse()));
    DocumentToolSession session = session(worker);

    DocumentOverviewResult result = session.overview(new DocumentOverviewRequest("doc"));

    assertTrue(result.warning().contains("older attachment"));
    assertEquals(List.of("user/doc.artifact", "user/doc.txt"), storage.openedKeys);
  }

  @Test
  void registersRenderedPagesForTheNextModelTurn() throws Exception {
    byte[] png = new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a};
    RecordingStorage storage = new RecordingStorage(Map.of(
        "user/doc.artifact", new byte[] {1},
        "user/doc", new byte[] {2}));
    RecordingWorker worker = new RecordingWorker(storage, List.of(
        okResponse(),
        imageResponse(png),
        okResponse()));
    DocumentToolSession session = session(worker);

    DocumentPageViewResult view = session.viewPage(
        new DocumentPageViewRequest("doc", 1));

    assertEquals(1, view.content().pageNumber());
    assertEquals("image/png", view.content().mediaType());
    assertEquals(List.of(IMAGE), view.images());
  }

  @Test
  void omitsUnavailableInlineContentFromLargeDocumentOverview() throws Exception {
    RecordingStorage storage = new RecordingStorage(Map.of("user/doc.artifact", new byte[] {1}));
    RecordingWorker worker = new RecordingWorker(storage, List.of(
        okResponse(),
        jsonResponse(overviewJson(null)),
        okResponse()));
    DocumentToolSession session = session(worker);

    DocumentOverviewResult result = session.overview(new DocumentOverviewRequest("doc"));

    assertNull(result.content());
    assertEquals(1, result.firstPageNumber());
  }

  @Test
  void returnsTypedSearchAndReadResults() throws Exception {
    RecordingStorage storage = new RecordingStorage(Map.of("user/doc.artifact", new byte[] {1}));
    RecordingWorker worker = RecordingWorker.scripted(storage, List.of(
        List.of(
            okResponse(),
            jsonResponse("""
                {
                  "hits":[{
                    "chunk_id":2,
                    "ordinal":1,
                    "score":-0.5,
                    "container_start":1,
                    "container_end":1,
                    "text":"match",
                    "regions":[],
                    "containers":[{"number":1,"kind":"page","label":"12"}]
                  }],
                  "has_more":true
                }
                """),
            okResponse()),
        List.of(
            okResponse(),
            readResponse(),
            okResponse())));
    DocumentToolSession session = session(worker);

    DocumentSearchDocumentResult search = (DocumentSearchDocumentResult) session.search(
        new DocumentSearchRequest(SEARCH_QUERIES, "doc"));
    DocumentReadResult read = session.read(
        new DocumentReadRequest("doc", 2, 1));

    assertTrue(search.hasMoreResults());
    assertEquals(2, search.hits().getFirst().pageNumber());
    assertEquals(1, search.matchCount());
    assertEquals(2, read.pageNumber());
    assertEquals(3, read.totalPages());
    assertEquals("window text", read.text());
    assertNull(read.warning());
    assertEquals(2, worker.connectionsOpened);
    assertEquals(
        List.of("user/doc.artifact", "user/doc.artifact"),
        storage.openedKeys);
  }

  @Test
  void warnsWhenAPageIsLongerThanOneRead() throws Exception {
    RecordingStorage storage = new RecordingStorage(Map.of("user/doc.artifact", new byte[] {1}));
    RecordingWorker worker = RecordingWorker.scripted(storage, List.of(
        List.of(
            okResponse(),
            readResponse(true),
            okResponse())));

    DocumentReadResult read = session(worker).read(new DocumentReadRequest("doc", 2, 1));

    assertEquals("window text", read.text());
    assertEquals(
        "This page is longer than one read returns, so only its beginning is included. "
            + "Search the document to find text later on this page.",
        read.warning());
  }

  @Test
  void usesLongerSnippetsForTargetedSearches() throws Exception {
    DocumentReference reference = reference("target");
    DocumentWorkerConnection connection = mock(DocumentWorkerConnection.class);
    when(connection.search(reference, SEARCH_QUERIES, 1_200))
        .thenReturn(searchResult("match"));
    DocumentWorkerScheduler scheduler = new DocumentWorkerScheduler(
        () -> connection,
        1,
        ACQUIRE_TIMEOUT);
    DocumentToolSession session = new DocumentToolSession(
        new DocumentManifest(List.of(reference)),
        scheduler,
        png -> IMAGE,
        mock(DocumentStorageGateway.class));

    session.search(new DocumentSearchRequest(SEARCH_QUERIES, reference.attachmentId()));

    verify(connection).search(reference, SEARCH_QUERIES, 1_200);
    verify(connection).close();
  }

  @Test
  void searchesCollectionsInParallelOnSharedProcesses() throws Exception {
    List<DocumentReference> references = List.of(
        reference("first"),
        reference("second"),
        reference("third"),
        reference("fourth"));
    DocumentWorkerConnection firstConnection  = mock(DocumentWorkerConnection.class);
    DocumentWorkerConnection secondConnection = mock(DocumentWorkerConnection.class);
    DocumentWorkerConnection thirdConnection  = mock(DocumentWorkerConnection.class);
    DocumentWorkerConnection fourthConnection = mock(DocumentWorkerConnection.class);
    List<DocumentWorkerConnection> connections = List.of(
        firstConnection,
        secondConnection,
        thirdConnection,
        fourthConnection);
    AtomicInteger nextConnection = new AtomicInteger();
    AtomicInteger activeSearches = new AtomicInteger();
    AtomicInteger maximumSearches = new AtomicInteger();
    AtomicBoolean virtualThreads = new AtomicBoolean(true);
    CountDownLatch parallelSearches = new CountDownLatch(2);

    for (DocumentWorkerConnection connection : connections) {
      when(connection.search(
          any(DocumentReference.class),
          eq(SEARCH_QUERIES),
          eq(512)))
          .thenAnswer(invocation -> {
            if (!Thread.currentThread().isVirtual()) {
              virtualThreads.set(false);
            }
            int active = activeSearches.incrementAndGet();
            maximumSearches.accumulateAndGet(active, Math::max);
            parallelSearches.countDown();
            try {
              if (!parallelSearches.await(2, TimeUnit.SECONDS)) {
                throw new IOException("Parallel searches did not start");
              }
              DocumentReference reference = invocation.getArgument(0);
              return searchResult(
                  reference.attachmentId(),
                  reference.attachmentId().equals("first"));
            } catch (InterruptedException error) {
              throw new IOException("Interrupted while awaiting parallel searches", error);
            } finally {
              activeSearches.decrementAndGet();
            }
          });
    }

    DocumentWorkerGateway gateway = () -> connections.get(nextConnection.getAndIncrement());
    DocumentWorkerScheduler scheduler = new DocumentWorkerScheduler(gateway, 2, ACQUIRE_TIMEOUT);
    DocumentToolSession session = new DocumentToolSession(
        new DocumentManifest(references),
        scheduler,
        png -> IMAGE,
        mock(DocumentStorageGateway.class));

    DocumentSearchCollectionResult result = (DocumentSearchCollectionResult) session.search(
        new DocumentSearchRequest(SEARCH_QUERIES, null));

    assertEquals(2, maximumSearches.get());
    assertTrue(virtualThreads.get());
    assertEquals(2, nextConnection.get());
    assertEquals(
        List.of("first", "second", "third", "fourth"),
        result.files().stream().map(file -> file.attachmentId()).toList());
    assertTrue(result.files().getFirst().hasMoreResults());
    assertTrue(result.files().stream().skip(1).noneMatch(file -> file.hasMoreResults()));
    assertEquals(4, result.matchCount());
    assertTrue(result.truncated());
    assertTrue(result.failedFiles().isEmpty());
    for (DocumentWorkerConnection connection : List.of(firstConnection, secondConnection)) {
      verify(connection, times(2)).search(
          any(DocumentReference.class),
          eq(SEARCH_QUERIES),
          eq(512));
      verify(connection, times(2)).release(any(DocumentReference.class));
      verify(connection).close();
    }
    verifyNoInteractions(thirdConnection, fourthConnection);
  }

  @Test
  void replacesTheProcessAfterACollectionSearchFails() throws Exception {
    DocumentReference first  = reference("first");
    DocumentReference failed = reference("failed");
    DocumentReference last   = reference("last");
    DocumentWorkerConnection brokenConnection      = mock(DocumentWorkerConnection.class);
    DocumentWorkerConnection replacementConnection = mock(DocumentWorkerConnection.class);
    List<DocumentWorkerConnection> connections = List.of(
        brokenConnection,
        replacementConnection);
    for (DocumentWorkerConnection connection : connections) {
      when(connection.search(
          any(DocumentReference.class),
          eq(SEARCH_QUERIES),
          eq(512)))
          .thenAnswer(invocation -> {
            DocumentReference reference = invocation.getArgument(0);
            if (reference.attachmentId().equals(failed.attachmentId())) {
              throw new IOException("Search failed");
            }
            return searchResult(reference.attachmentId());
          });
    }
    AtomicInteger nextConnection = new AtomicInteger();
    DocumentWorkerGateway gateway = () -> connections.get(nextConnection.getAndIncrement());
    DocumentToolSession session = new DocumentToolSession(
        new DocumentManifest(List.of(first, failed, last)),
        new DocumentWorkerScheduler(gateway, 1, ACQUIRE_TIMEOUT),
        png -> IMAGE,
        mock(DocumentStorageGateway.class));

    DocumentSearchCollectionResult result = (DocumentSearchCollectionResult) session.search(
        new DocumentSearchRequest(SEARCH_QUERIES, null));

    assertEquals(List.of("first", "last"),
        result.files().stream().map(file -> file.attachmentId()).toList());
    assertEquals(List.of("failed"),
        result.failedFiles().stream().map(failure -> failure.attachmentId()).toList());
    assertEquals(2, nextConnection.get());
    verify(brokenConnection).release(first);
    verify(brokenConnection).close();
    verify(replacementConnection).search(last, SEARCH_QUERIES, 512);
    verify(replacementConnection).release(last);
    verify(replacementConnection).close();
  }

  @Test
  void keepsASearchResultWhenReleasingItsDocumentFails() throws Exception {
    DocumentReference first  = reference("first");
    DocumentReference second = reference("second");
    DocumentWorkerConnection releaseFails = mock(DocumentWorkerConnection.class);
    DocumentWorkerConnection replacement  = mock(DocumentWorkerConnection.class);
    for (DocumentWorkerConnection connection : List.of(releaseFails, replacement)) {
      when(connection.search(any(DocumentReference.class), eq(SEARCH_QUERIES), eq(512)))
          .thenAnswer(invocation -> searchResult(((DocumentReference) invocation.getArgument(0)).attachmentId()));
    }
    doThrow(new IOException("Release failed")).when(releaseFails).release(first);
    List<DocumentWorkerConnection> connections = List.of(releaseFails, replacement);
    AtomicInteger nextConnection = new AtomicInteger();
    DocumentToolSession session = new DocumentToolSession(
        new DocumentManifest(List.of(first, second)),
        new DocumentWorkerScheduler(() -> connections.get(nextConnection.getAndIncrement()), 1, ACQUIRE_TIMEOUT),
        png -> IMAGE,
        mock(DocumentStorageGateway.class));

    DocumentSearchCollectionResult result = (DocumentSearchCollectionResult) session.search(
        new DocumentSearchRequest(SEARCH_QUERIES, null));

    assertEquals(List.of("first", "second"),
        result.files().stream().map(file -> file.attachmentId()).toList());
    assertTrue(result.failedFiles().isEmpty());
    verify(releaseFails).close();
    verify(replacement).search(second, SEARCH_QUERIES, 512);
    verify(replacement).close();
  }

  @Test
  void failsTheRestOfACollectionSearchWhenNoProcessCanStart() throws Exception {
    List<DocumentReference> references = List.of(
        reference("first"),
        reference("second"),
        reference("third"),
        reference("fourth"));
    AtomicInteger attempts = new AtomicInteger();
    DocumentWorkerGateway gateway = () -> {
      attempts.incrementAndGet();
      throw new IOException("Unable to start a remote document worker");
    };
    DocumentToolSession session = new DocumentToolSession(
        new DocumentManifest(references),
        new DocumentWorkerScheduler(gateway, 2, ACQUIRE_TIMEOUT),
        png -> IMAGE,
        mock(DocumentStorageGateway.class));

    DocumentSearchCollectionResult result = (DocumentSearchCollectionResult) session.search(
        new DocumentSearchRequest(SEARCH_QUERIES, null));

    assertTrue(attempts.get() <= 2, "attempts=" + attempts.get());
    assertEquals(List.of("first", "second", "third", "fourth"),
        result.failedFiles().stream().map(failure -> failure.attachmentId()).toList());
    assertTrue(result.files().isEmpty());
    assertTrue(result.partial());
  }

  @Test
  void reportsCollectionSearchFailuresWithTheirDocuments() throws Exception {
    DocumentReference successfulReference = reference("successful");
    DocumentReference failedReference = reference("failed");
    DocumentWorkerConnection successfulConnection = mock(DocumentWorkerConnection.class);
    DocumentWorkerConnection failedConnection = mock(DocumentWorkerConnection.class);

    AtomicInteger nextConnection = new AtomicInteger();
    List<DocumentWorkerConnection> connections = List.of(
        successfulConnection,
        failedConnection);
    for (DocumentWorkerConnection connection : connections) {
      when(connection.search(
          any(DocumentReference.class),
          eq(SEARCH_QUERIES),
          eq(512)))
          .thenAnswer(invocation -> {
            DocumentReference reference = invocation.getArgument(0);
            if (reference.attachmentId().equals(failedReference.attachmentId())) {
              throw new IOException("Search failed");
            }
            return searchResult("result");
          });
    }
    DocumentWorkerGateway gateway = () -> connections.get(nextConnection.getAndIncrement());
    DocumentWorkerScheduler scheduler = new DocumentWorkerScheduler(gateway, 2, ACQUIRE_TIMEOUT);
    DocumentToolSession session = new DocumentToolSession(
        new DocumentManifest(List.of(successfulReference, failedReference)),
        scheduler,
        png -> IMAGE,
        mock(DocumentStorageGateway.class));

    DocumentSearchCollectionResult result = (DocumentSearchCollectionResult) session.search(
        new DocumentSearchRequest(SEARCH_QUERIES, null));

    assertEquals(List.of("successful"),
        result.files().stream().map(file -> file.attachmentId()).toList());
    assertEquals(List.of("failed"),
        result.failedFiles().stream().map(failure -> failure.attachmentId()).toList());
    assertEquals(1, result.matchCount());
    assertTrue(result.partial());
    verify(successfulConnection).close();
    verify(failedConnection).close();
  }

  @Test
  void reportsUnknownAttachmentsBeforeOpeningWorker() throws Exception {
    RecordingStorage storage = new RecordingStorage(Map.of());
    RecordingWorker worker = new RecordingWorker(storage, List.of());
    DocumentToolSession session = session(worker);

    DocumentAccessException error = assertThrows(
        DocumentAccessException.class,
        () -> session.overview(new DocumentOverviewRequest("missing")));

    assertEquals("Unknown attachment_id", error.getMessage());
    assertEquals(0, worker.connectionsOpened);
  }

  @Test
  void keepsUnextractedAttachmentsOutOfDocumentTools() throws Exception {
    DocumentReference reference = new DocumentReference(
        "generated",
        "generated.xlsx",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        1,
        "user/generated",
        KEY,
        false);
    DocumentWorkerScheduler workers = mock(DocumentWorkerScheduler.class);
    DocumentToolSession session = new DocumentToolSession(
        new DocumentManifest(List.of(reference)),
        workers,
        png -> IMAGE,
        mock(DocumentStorageGateway.class));

    assertThrows(
        DocumentAccessException.class,
        () -> session.overview(new DocumentOverviewRequest("generated")));
    verifyNoInteractions(workers);
  }

  @Test
  void opensOriginalAttachmentsForWorkerTools() throws Exception {
    byte[] source = "source".getBytes(StandardCharsets.UTF_8);
    DocumentReference reference = reference("source");
    DocumentStorageGateway storage = mock(DocumentStorageGateway.class);
    when(storage.open(reference.sourceObjectKey(), reference.encryptionKey()))
        .thenReturn(new DecryptedDocument(
            new ByteArrayInputStream(source),
            source.length));
    DocumentToolSession session = inputSession(reference, storage);

    try (DecryptedDocument content = session.open(reference.attachmentId())) {
      assertEquals(source.length, content.length());
      assertArrayEquals(source, content.content().readAllBytes());
    }
  }

  @Test
  void rejectsUnknownWorkerInputsBeforeOpeningStorage() throws Exception {
    DocumentStorageGateway storage = mock(DocumentStorageGateway.class);
    DocumentToolSession session = inputSession(reference("source"), storage);

    IOException error = assertThrows(IOException.class, () -> session.open("missing"));

    assertEquals("Attachment is unavailable", error.getMessage());
    verifyNoInteractions(storage);
  }

  @Test
  void acceptsOpaqueObjectStorageNamespaceWithoutAUserIdentity() {
    DocumentReference reference = new DocumentReference(
        "doc",
        "Jung.pdf",
        "application/pdf",
        1,
        "opaque-namespace-7Kq/doc",
        KEY,
        null);

    DocumentManifest manifest = assertDoesNotThrow(() -> new DocumentManifest(List.of(reference)));

    assertEquals(reference, assertDoesNotThrow(() -> manifest.reference("doc")));
  }

  @Test
  void rejectsMalformedObjectStorageKeys() {
    DocumentReference reference = new DocumentReference(
        "doc",
        "Jung.pdf",
        "application/pdf",
        1,
        "../doc",
        KEY,
        null);

    assertThrows(
        InvalidDocumentManifestException.class,
        () -> new DocumentManifest(List.of(reference)));
  }

  private static DocumentToolSession session(RecordingWorker worker)
    throws InvalidDocumentManifestException
  {
    DocumentReference reference = new DocumentReference(
        "doc",
        "Jung.pdf",
        "application/pdf",
        1,
        "user/doc",
        KEY,
        null);
    DocumentWorkerScheduler scheduler = new DocumentWorkerScheduler(worker, 1, ACQUIRE_TIMEOUT);
    return new DocumentToolSession(
        new DocumentManifest(List.of(reference)),
        scheduler,
        png -> IMAGE,
        mock(DocumentStorageGateway.class));
  }

  private static DocumentToolSession inputSession(DocumentReference       reference,
                                                  DocumentStorageGateway storage)
      throws InvalidDocumentManifestException
  {
    return new DocumentToolSession(
        new DocumentManifest(List.of(reference)),
        mock(DocumentWorkerScheduler.class),
        png -> IMAGE,
        storage);
  }

  private static DocumentReference reference(String attachmentId) {
    return new DocumentReference(
        attachmentId,
        attachmentId + ".pdf",
        "application/pdf",
        1,
        "user/" + attachmentId,
        KEY,
        null);
  }

  private static DocumentSearchResult searchResult(String text) {
    return searchResult(text, false);
  }

  private static DocumentSearchResult searchResult(String text, boolean hasMore) {
    return new DocumentSearchResult(
        List.of(new DocumentSearchHit(
            1,
            0,
            -0.5,
            0,
            0,
            text,
            List.of(),
            List.of(new DocumentContainerReference(0, "page", null)))),
        hasMore);
  }

  private static ImageReference imageReference() {
    try {
      return new ImageReference("temporary-images/rendered", KEY, "image/png");
    } catch (InvalidImageReferenceException error) {
      throw new ExceptionInInitializerError(error);
    }
  }

  private static DocumentWorkerResponse<Void> jsonResponse(String json) {
    return DocumentWorkerResponse.success(
        Map.of(
            DocumentWorkerPayloadRole.RESULT,
            payload(DocumentWorkerPayloadRole.RESULT, json.getBytes(StandardCharsets.UTF_8))));
  }

  private static DocumentWorkerResponse<RenderedDocumentPageMetadata> imageResponse(byte[] png) {
    return DocumentWorkerResponse.success(
        new RenderedDocumentPageMetadata("image/png", 10, 10, 0),
        Map.of(
            DocumentWorkerPayloadRole.IMAGE,
            payload(DocumentWorkerPayloadRole.IMAGE, png)));
  }

  private static DocumentWorkerResponse<DocumentTextWindow> readResponse() {
    return readResponse(false);
  }

  private static DocumentWorkerResponse<DocumentTextWindow> readResponse(boolean truncated) {
    return DocumentWorkerResponse.success(
        new DocumentTextWindow(
            1,
            1,
            3,
            List.of(),
            List.of(new DocumentContainerReference(1, "page", "12")),
            truncated),
        Map.of(
            DocumentWorkerPayloadRole.TEXT,
            payload(
                DocumentWorkerPayloadRole.TEXT,
                "window text".getBytes(StandardCharsets.UTF_8))));
  }

  private static String overviewJson(String content) {
    String contentProperty = content == null ? "" : ",\"content\":\"" + content + "\"";
    return """
        {
          "metadata":{
            "schema_version":1,
            "source_sha256":"0000000000000000000000000000000000000000000000000000000000000000",
            "media_type":"application/pdf",
            "title":"Jung",
            "text_bytes":9,
            "container_count":1,
            "outline_count":0,
            "page_count":1
          },
          "outline":[],
          "outline_truncated":false,
          "labeled_containers":[],
          "first_chunk_id":1%s
        }
        """.formatted(contentProperty);
  }

  private static DocumentWorkerResponse<Void> okResponse() {
    return DocumentWorkerResponse.success(Map.of());
  }

  private static DocumentWorkerResponsePayload payload(DocumentWorkerPayloadRole role,
                                                        byte[] content)
  {
    return new DocumentWorkerResponsePayload(role.requiredMediaType(), content);
  }

  private static class RecordingStorage implements DocumentStorageGateway {

    private final Map<String, byte[]> objects;
    private final List<String> openedKeys = new ArrayList<>();

    private RecordingStorage(Map<String, byte[]> objects) {
      this.objects = objects;
    }

    @Override
    public DecryptedDocument open(String objectKey, String encryptionKey) throws IOException {
      openedKeys.add(objectKey);
      byte[] content = objects.get(objectKey);
      if (content == null) {
        throw new DocumentNotFoundException();
      }
      return new DecryptedDocument(new ByteArrayInputStream(content), content.length);
    }
  }

  private static class RecordingWorker implements DocumentWorkerGateway {

    private final DocumentStorageGateway storage;
    private final List<byte[]>           responses;

    private int connectionsOpened;

    private RecordingWorker(DocumentStorageGateway storage,
                            List<? extends DocumentWorkerResponse<?>> responses)
      throws IOException
    {
      this(storage, encode(List.of(responses)));
    }

    private RecordingWorker(DocumentStorageGateway storage,
                            byte[][] responses)
    {
      this.storage = storage;
      this.responses = List.of(responses);
    }

    private static RecordingWorker scripted(
        DocumentStorageGateway storage,
        List<? extends List<? extends DocumentWorkerResponse<?>>> responseSets)
      throws IOException
    {
      return new RecordingWorker(storage, encode(responseSets));
    }

    private static byte[][] encode(
        List<? extends List<? extends DocumentWorkerResponse<?>>> responseSets)
      throws IOException
    {
      byte[][] encodedSets = new byte[responseSets.size()][];

      for (int index = 0; index < responseSets.size(); index++) {
        List<? extends DocumentWorkerResponse<?>> responseSet = responseSets.get(index);
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        DocumentWorkerOutputStream output = new DocumentWorkerOutputStream(MAPPER, encoded);
        for (DocumentWorkerResponse<?> response : responseSet) {
          output.writeResponse(response);
        }
        encodedSets[index] = encoded.toByteArray();
      }

      return encodedSets;
    }

    @Override
    public synchronized DocumentWorkerConnection connect() {
      byte[] connectionResponses = responses.get(connectionsOpened);
      connectionsOpened++;
      return new DocumentWorkerConnection(
          new DocumentWorkerInputStream(MAPPER, new ByteArrayInputStream(connectionResponses)),
          new DocumentWorkerOutputStream(MAPPER, new ByteArrayOutputStream()),
          storage,
          MAPPER,
          validatorFactory.getValidator());
    }
  }
}
