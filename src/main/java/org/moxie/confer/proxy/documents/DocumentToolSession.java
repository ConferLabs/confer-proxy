package org.moxie.confer.proxy.documents;

import org.moxie.confer.proxy.documents.requests.DocumentOverviewRequest;
import org.moxie.confer.proxy.documents.requests.DocumentPageViewRequest;
import org.moxie.confer.proxy.documents.requests.DocumentReadRequest;
import org.moxie.confer.proxy.documents.requests.DocumentSearchQuery;
import org.moxie.confer.proxy.documents.requests.DocumentSearchRequest;
import org.moxie.confer.proxy.documents.responses.DocumentOverviewMetadata;
import org.moxie.confer.proxy.documents.responses.DocumentOverviewResult;
import org.moxie.confer.proxy.documents.responses.DocumentOutlineLocation;
import org.moxie.confer.proxy.documents.responses.DocumentPageReference;
import org.moxie.confer.proxy.documents.responses.DocumentPageViewContent;
import org.moxie.confer.proxy.documents.responses.DocumentReadResult;
import org.moxie.confer.proxy.documents.responses.DocumentSearchCollectionResult;
import org.moxie.confer.proxy.documents.responses.DocumentSearchDocumentResult;
import org.moxie.confer.proxy.documents.responses.DocumentSearchFailure;
import org.moxie.confer.proxy.documents.responses.DocumentSearchFileResult;
import org.moxie.confer.proxy.documents.responses.DocumentSearchMatch;
import org.moxie.confer.proxy.documents.responses.DocumentSearchToolResult;
import org.moxie.confer.proxy.documents.responses.DocumentVisualContent;
import org.moxie.confer.proxy.documents.worker.DocumentWorkerConnection;
import org.moxie.confer.proxy.documents.worker.DocumentWorkerLease;
import org.moxie.confer.proxy.documents.worker.DocumentWorkerScheduler;
import org.moxie.confer.proxy.documents.worker.responses.DocumentArtifactMetadata;
import org.moxie.confer.proxy.documents.worker.responses.DocumentOverview;
import org.moxie.confer.proxy.documents.worker.responses.DocumentSearchHit;
import org.moxie.confer.proxy.documents.worker.responses.DocumentSearchResult;
import org.moxie.confer.proxy.documents.worker.responses.IndexedDocumentText;
import org.moxie.confer.proxy.documents.worker.responses.RenderedDocumentPage;
import org.moxie.confer.proxy.documents.worker.responses.RenderedDocumentPageMetadata;
import org.moxie.confer.proxy.entities.DocumentReference;
import org.moxie.confer.proxy.images.ImageReference;
import org.moxie.confer.proxy.images.TemporaryImageStorage;
import org.moxie.confer.proxy.util.Result;
import org.moxie.confer.proxy.workers.WorkerInputSource;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Gatherers;

public class DocumentToolSession implements WorkerInputSource {

  private static final String LEGACY_WARNING =
      "This older attachment uses its extracted-text fallback; visual pages are unavailable.";

  private static final String PAGE_ATTACHED_MESSAGE =
      "The rendered page is attached to the next model turn.";

  private static final String RETRIEVAL_FAILED = "Document retrieval failed";

  private static final int COLLECTION_SEARCH_SNIPPET_CHARACTERS = 512;
  private static final int TARGETED_SEARCH_SNIPPET_CHARACTERS   = 1_200;

  private final DocumentManifest        manifest;
  private final DocumentWorkerScheduler workers;
  private final TemporaryImageStorage   images;
  private final DocumentStorageGateway  storage;

  DocumentToolSession(DocumentManifest manifest,
                      DocumentWorkerScheduler workers,
                      TemporaryImageStorage images,
                      DocumentStorageGateway storage)
  {
    this.manifest = manifest;
    this.workers  = workers;
    this.images   = images;
    this.storage  = storage;
  }

  @Override
  public DecryptedDocument open(String attachmentId) throws IOException {
    try {
      DocumentReference reference = manifest.reference(attachmentId);
      return storage.open(reference.sourceObjectKey(), reference.encryptionKey());
    } catch (DocumentAccessException error) {
      throw new IOException("Attachment is unavailable", error);
    }
  }

  public DocumentOverviewResult overview(DocumentOverviewRequest request)
    throws IOException, DocumentAccessException
  {
    DocumentReference reference = getExtractedReference(request.attachmentId());

    try (DocumentWorkerLease worker = workers.acquire()) {
      DocumentWorkerConnection connection = worker.connection();
      DocumentOverview         overview   = connection.overview(reference);
      DocumentOverviewMetadata metadata   = overviewMetadata(overview.metadata());

      return new DocumentOverviewResult(
          reference.attachmentId(),
          reference.filename(),
          metadata,
          overview.outline().stream()
              .map(entry -> new DocumentOutlineLocation(
                  entry.level(),
                  entry.title(),
                  entry.containerNumber() + 1))
              .toList(),
          overview.outlineTruncated(),
          overview.labeledContainers().stream()
              .map(container -> new DocumentPageReference(
                  container.number() + 1,
                  container.kind(),
                  container.label()))
              .toList(),
          firstPageNumber(metadata.pageCount()),
          overview.content(),
          legacyWarning(connection, reference));
    }
  }

  public DocumentSearchToolResult search(DocumentSearchRequest request)
    throws IOException, DocumentAccessException
  {
    if (request.attachmentId() != null) {
      return searchDocument(
          getExtractedReference(request.attachmentId()),
          request.queries(),
          TARGETED_SEARCH_SNIPPET_CHARACTERS);
    }

    return searchAll(
        manifest.values()
                .stream()
                .filter(DocumentReference::supportsDocumentTools)
                .toList(),
        request.queries(),
        COLLECTION_SEARCH_SNIPPET_CHARACTERS);
  }

  public DocumentReadResult read(DocumentReadRequest request)
    throws IOException, DocumentAccessException
  {
    DocumentReference reference = getExtractedReference(request.attachmentId());

    try (DocumentWorkerLease worker = workers.acquire()) {
      DocumentWorkerConnection connection = worker.connection();
      IndexedDocumentText      text       = connection.read(reference, request.pageNumber() - 1, request.pageCount());

      return new DocumentReadResult(
          reference.attachmentId(),
          reference.filename(),
          text.window().containerStart() + 1,
          text.window().containerEnd() - text.window().containerStart() + 1,
          text.window().totalContainers(),
          text.window().containers().stream()
              .map(container -> new DocumentPageReference(
                  container.number() + 1,
                  container.kind(),
                  container.label()))
              .toList(),
          text.window().regions().stream()
              .map(region -> new DocumentVisualContent(
                  region.containerNumber() + 1,
                  region.type(),
                  region.confidence()))
              .toList(),
          text.text(),
          legacyWarning(connection, reference));
    }
  }

  public DocumentPageViewResult viewPage(DocumentPageViewRequest request)
    throws IOException, DocumentAccessException
  {
    DocumentReference    reference = getExtractedReference(request.attachmentId());
    RenderedDocumentPage rendered  = renderPage(
        reference,
        request.pageNumber() - 1);

    RenderedDocumentPageMetadata metadata = rendered.metadata();
    ImageReference               image    = images.storePng(rendered.png());

    DocumentPageViewContent content = new DocumentPageViewContent(
        reference.attachmentId(),
        reference.filename(),
        metadata.mediaType(),
        metadata.width(),
        metadata.height(),
        metadata.containerNumber() + 1,
        PAGE_ATTACHED_MESSAGE);

    return new DocumentPageViewResult(content, List.of(image));
  }

  private RenderedDocumentPage renderPage(DocumentReference reference,
                                          int pageNumber)
    throws IOException, DocumentAccessException
  {
    try (DocumentWorkerLease worker = workers.acquire()) {
      return worker.connection().renderPage(reference, pageNumber);
    }
  }

  private DocumentReference getExtractedReference(String attachmentId)
      throws DocumentAccessException
  {
    DocumentReference reference = manifest.reference(attachmentId);
    if (!reference.supportsDocumentTools()) {
      throw new DocumentAccessException("Unknown attachment_id");
    }
    return reference;
  }

  private DocumentSearchDocumentResult searchDocument(DocumentReference reference,
                                                       List<DocumentSearchQuery> queries,
                                                       int snippetCharacters)
    throws IOException
  {
    try (DocumentWorkerLease worker = workers.acquire()) {
      DocumentWorkerConnection connection = worker.connection();
      DocumentSearchResult     search     = connection.search(
          reference,
          queries,
          snippetCharacters);

      return new DocumentSearchDocumentResult(
          reference.attachmentId(),
          reference.filename(),
          search.hits().stream().map(this::searchMatch).toList(),
          search.hasMore(),
          search.hits().size(),
          legacyWarning(connection, reference));
    }
  }

  private DocumentSearchCollectionResult searchAll(List<DocumentReference> references,
                                                    List<DocumentSearchQuery> queries,
                                                    int snippetCharacters)
  {
    List<DocumentSearchDocumentResult> searches = new ArrayList<>();
    List<DocumentSearchFailure>        failures = new ArrayList<>();

    int concurrency = Math.max(1, references.size());
    references.stream()
        .gather(Gatherers.mapConcurrent(
            concurrency,
            reference -> trySearchDocument(reference, queries, snippetCharacters)))
        .forEachOrdered(result -> result.ifSuccessOrElse(
            searches::add,
            failures::add));

    List<DocumentSearchFileResult> files = searches.stream()
        .filter(search -> !search.hits().isEmpty())
        .map(search -> new DocumentSearchFileResult(
            search.attachmentId(),
            search.filename(),
            search.matchCount(),
            search.hasMoreResults(),
            search.warning(),
            search.hits()))
        .toList();

    int matches = searches.stream()
        .mapToInt(DocumentSearchDocumentResult::matchCount)
        .sum();

    boolean truncated = searches.stream()
        .anyMatch(DocumentSearchDocumentResult::hasMoreResults);

    String message = matches == 0
        ? "No matches were found in the available files."
        : null;

    return new DocumentSearchCollectionResult(
        files,
        failures,
        matches,
        truncated,
        !failures.isEmpty(),
        message);
  }

  private Result<DocumentSearchDocumentResult, DocumentSearchFailure> trySearchDocument(
      DocumentReference reference,
      List<DocumentSearchQuery> queries,
      int snippetCharacters)
  {
    try {
      return Result.success(searchDocument(reference, queries, snippetCharacters));
    } catch (IOException error) {
      return Result.failure(new DocumentSearchFailure(
          reference.attachmentId(),
          reference.filename(),
          RETRIEVAL_FAILED));
    }
  }

  private DocumentSearchMatch searchMatch(DocumentSearchHit hit) {
    return new DocumentSearchMatch(
        hit.containerStart() + 1,
        hit.containerEnd() - hit.containerStart() + 1,
        hit.text(),
        hit.containers().stream()
            .map(container -> new DocumentPageReference(
                container.number() + 1,
                container.kind(),
                container.label()))
            .toList(),
        hit.regions().stream()
            .map(region -> new DocumentVisualContent(
                region.containerNumber() + 1,
                region.type(),
                region.confidence()))
            .toList());
  }

  private String legacyWarning(DocumentWorkerConnection connection,
                               DocumentReference reference)
  {
    return connection.isLegacy(reference) ? LEGACY_WARNING : null;
  }

  private DocumentOverviewMetadata overviewMetadata(DocumentArtifactMetadata metadata) {
    return new DocumentOverviewMetadata(
        metadata.schemaVersion(),
        metadata.sourceSha256(),
        metadata.mediaType(),
        metadata.title(),
        metadata.textBytes(),
        metadata.outlineCount(),
        metadata.pageCount() == null ? metadata.containerCount() : metadata.pageCount());
  }

  private Integer firstPageNumber(int pageCount) {
    return pageCount == 0 ? null : 1;
  }
}
