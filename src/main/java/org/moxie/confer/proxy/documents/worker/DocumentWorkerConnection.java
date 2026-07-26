package org.moxie.confer.proxy.documents.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validator;
import org.moxie.confer.proxy.documents.DecryptedDocument;
import org.moxie.confer.proxy.documents.DocumentAccessException;
import org.moxie.confer.proxy.documents.DocumentNotFoundException;
import org.moxie.confer.proxy.documents.DocumentObjectKeys;
import org.moxie.confer.proxy.documents.DocumentStorageGateway;
import org.moxie.confer.proxy.documents.requests.DocumentSearchQuery;
import org.moxie.confer.proxy.documents.worker.requests.CloseDocumentWorkerRequest;
import org.moxie.confer.proxy.documents.worker.requests.DocumentWorkerRequest;
import org.moxie.confer.proxy.documents.worker.requests.DocumentWorkerRequestPayload;
import org.moxie.confer.proxy.documents.worker.requests.ExtractDocumentRequest;
import org.moxie.confer.proxy.documents.worker.requests.GetDocumentOverviewRequest;
import org.moxie.confer.proxy.documents.worker.requests.OpenDocumentRequest;
import org.moxie.confer.proxy.documents.worker.requests.OpenTextDocumentRequest;
import org.moxie.confer.proxy.documents.worker.requests.ReadDocumentRequest;
import org.moxie.confer.proxy.documents.worker.requests.ReleaseDocumentRequest;
import org.moxie.confer.proxy.documents.worker.requests.RenderDocumentPageRequest;
import org.moxie.confer.proxy.documents.worker.requests.SearchDocumentRequest;
import org.moxie.confer.proxy.documents.worker.responses.DocumentExtractionMetadata;
import org.moxie.confer.proxy.documents.worker.responses.DocumentWorkerError;
import org.moxie.confer.proxy.documents.worker.responses.DocumentOverview;
import org.moxie.confer.proxy.documents.worker.responses.DocumentSearchResult;
import org.moxie.confer.proxy.documents.worker.responses.DocumentTextWindow;
import org.moxie.confer.proxy.documents.worker.responses.DocumentWorkerResponse;
import org.moxie.confer.proxy.documents.worker.responses.DocumentWorkerResponseStatus;
import org.moxie.confer.proxy.documents.worker.responses.IndexedDocumentText;
import org.moxie.confer.proxy.documents.worker.responses.RenderedDocumentPage;
import org.moxie.confer.proxy.documents.worker.responses.RenderedDocumentPageMetadata;
import org.moxie.confer.proxy.entities.DocumentReference;
import org.moxie.confer.proxy.storage.InvalidObjectStorageKeyException;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class DocumentWorkerConnection implements AutoCloseable {

  private static final int SEARCH_RESULT_LIMIT = 12;
  private static final int PAGE_RENDER_DPI     = 160;

  private final Set<String> openDocuments   = new HashSet<>();
  private final Set<String> legacyDocuments = new HashSet<>();

  private final DocumentWorkerInputStream  input;
  private final DocumentWorkerOutputStream output;
  private final DocumentStorageGateway     storage;
  private final ObjectMapper               mapper;
  private final Validator                  validator;

  private boolean closed;
  private boolean extractionStarted;
  private boolean sessionStarted;

  public DocumentWorkerConnection(DocumentWorkerInputStream input,
                                  DocumentWorkerOutputStream output,
                                  DocumentStorageGateway storage,
                                  ObjectMapper mapper,
                                  Validator validator)
  {
    this.input     = input;
    this.output    = output;
    this.storage   = storage;
    this.mapper    = mapper;
    this.validator = validator;
  }

  public synchronized DocumentExtractionResponse extract(ExtractDocumentRequest request)
    throws IOException
  {
    requireOpen();
    if (extractionStarted || sessionStarted) {
      throw new IOException("Document worker connection is already in use");
    }

    extractionStarted = true;
    output.writeRequest(request);
    DocumentWorkerFrame<DocumentExtractionMetadata> response = input.beginResponse(
        DocumentExtractionMetadata.class);
    if (response.status() != DocumentWorkerResponseStatus.OK) {
      throw new DocumentExtractionRejectedException();
    }
    return new DocumentExtractionResponse(
        this,
        response.contentLength(),
        extractionMetadata(response));
  }

  synchronized void writeExtractionResponseTo(OutputStream destination) throws IOException {
    requireOpen();
    if (!extractionStarted) {
      throw new IOException("Document worker connection has no extraction response");
    }
    input.writeCurrentResponseTo(destination);
  }

  synchronized void consumeExtractionText(DocumentWorkerPayloadHandler handler) throws IOException {
    requireOpen();
    if (!extractionStarted) {
      throw new IOException("Document worker connection has no extraction response");
    }
    input.consumeCurrentPayload(DocumentWorkerPayloadRole.TEXT, handler);
  }

  synchronized void consumeExtractionPayloads(DocumentWorkerPayloadHandler handler)
    throws IOException
  {
    requireOpen();
    if (!extractionStarted) {
      throw new IOException("Document worker connection has no extraction response");
    }
    input.consumeCurrentPayloads(handler);
  }

  public synchronized DocumentOverview overview(DocumentReference reference)
    throws IOException
  {
    ensureOpen(reference);

    DocumentOverview overview = resultJson(
        execute(new GetDocumentOverviewRequest(reference.attachmentId())),
        DocumentOverview.class);
    if (!validator.validate(overview).isEmpty()) {
      throw new DocumentWorkerProtocolException(
          "Document worker overview metadata is incomplete");
    }
    return overview;
  }

  public synchronized DocumentSearchResult search(DocumentReference reference,
                                                   List<DocumentSearchQuery> queries,
                                                   int snippetCharacters)
    throws IOException
  {
    ensureOpen(reference);

    return resultJson(
        execute(new SearchDocumentRequest(
            reference.attachmentId(),
            queries,
            SEARCH_RESULT_LIMIT,
            snippetCharacters)),
        DocumentSearchResult.class);
  }

  public synchronized IndexedDocumentText read(DocumentReference reference,
                                                int containerNumber,
                                                int containerCount)
    throws IOException
  {
    ensureOpen(reference);

    DocumentWorkerResponse<DocumentTextWindow> response = execute(
        new ReadDocumentRequest(reference.attachmentId(),
                                containerNumber,
                                containerCount),
        DocumentTextWindow.class);

    return new IndexedDocumentText(
        response.requiredResult(),
        new String(
            response.requiredPayload(DocumentWorkerPayloadRole.TEXT).content(),
            StandardCharsets.UTF_8));
  }

  public synchronized RenderedDocumentPage renderPage(DocumentReference reference,
                                                       int containerNumber)
    throws IOException, DocumentAccessException
  {
    ensureOpen(reference);
    if (isLegacy(reference)) {
      throw new DocumentAccessException(
          "Visual pages are unavailable for this legacy attachment");
    }
    try (DecryptedDocument source = storage.open(
        reference.sourceObjectKey(),
        reference.encryptionKey()))
    {
      DocumentWorkerRequestPayload sourcePayload = new DocumentWorkerRequestPayload(
          DocumentWorkerPayloadRole.SOURCE,
          reference.contentType(),
          source.length(),
          source.content());
      DocumentWorkerResponse<RenderedDocumentPageMetadata> response = execute(
          new RenderDocumentPageRequest(
              reference.attachmentId(),
              containerNumber,
              PAGE_RENDER_DPI,
              sourcePayload),
          RenderedDocumentPageMetadata.class);
      return new RenderedDocumentPage(
          response.requiredResult(),
          response.requiredPayload(DocumentWorkerPayloadRole.IMAGE).content());
    }
  }

  public synchronized boolean isLegacy(DocumentReference reference) {
    return legacyDocuments.contains(reference.attachmentId());
  }

  public synchronized void release(DocumentReference reference) throws IOException {
    if (!openDocuments.contains(reference.attachmentId())) {
      return;
    }

    execute(new ReleaseDocumentRequest(reference.attachmentId()));
    openDocuments.remove(reference.attachmentId());
    legacyDocuments.remove(reference.attachmentId());
  }

  private void ensureOpen(DocumentReference reference) throws IOException {
    if (!openDocuments.contains(reference.attachmentId())) {
      open(reference);
    }
  }

  private void open(DocumentReference reference) throws IOException {
    DocumentObjectKeys keys = objectKeys(reference);
    try {
      try (DecryptedDocument artifact = storage.open(
          keys.artifact(),
          reference.encryptionKey()))
      {
        DocumentWorkerRequestPayload artifactPayload = new DocumentWorkerRequestPayload(
            DocumentWorkerPayloadRole.ARTIFACT,
            DocumentWorkerPayloadRole.ARTIFACT.requiredMediaType(),
            artifact.length(),
            artifact.content());

        execute(new OpenDocumentRequest(
            reference.attachmentId(),
            artifactPayload));

        openDocuments.add(reference.attachmentId());
      }
    } catch (DocumentNotFoundException error) {
      openLegacyText(reference);
    }
  }

  private void openLegacyText(DocumentReference reference) throws IOException {
    DocumentObjectKeys keys = objectKeys(reference);
    try (DecryptedDocument text = storage.open(
        keys.text(),
        reference.encryptionKey()))
    {
      DocumentWorkerRequestPayload textPayload = new DocumentWorkerRequestPayload(
          DocumentWorkerPayloadRole.TEXT,
          DocumentWorkerPayloadRole.TEXT.requiredMediaType(),
          text.length(),
          text.content());
      execute(new OpenTextDocumentRequest(
          reference.attachmentId(),
          reference.filename(),
          textPayload));
      openDocuments.add(reference.attachmentId());
      legacyDocuments.add(reference.attachmentId());
    }
  }

  private DocumentWorkerResponse<Void> execute(DocumentWorkerRequest request) throws IOException {
    return execute(request, Void.class);
  }

  private <T> DocumentWorkerResponse<T> execute(DocumentWorkerRequest request,
                                                Class<T> resultType)
    throws IOException
  {
    requireOpen();
    if (extractionStarted) {
      throw new IOException("Document worker connection is already in use");
    }
    sessionStarted = true;
    output.writeRequest(request);
    DocumentWorkerResponse<T> response = input.readResponse(resultType);
    if (!response.successful()) {
      throw new IOException(workerError(response));
    }
    return response;
  }

  private <T> T resultJson(DocumentWorkerResponse<?> response, Class<T> type) throws IOException {
    return mapper.readValue(
        response.requiredPayload(DocumentWorkerPayloadRole.RESULT).content(),
        type);
  }

  private String workerError(DocumentWorkerResponse<?> response) {
    DocumentWorkerError error = response.error();
    if (error != null && error.message() != null && !error.message().isBlank()) {
      return error.message();
    }
    return "Document worker rejected the request";
  }

  private DocumentObjectKeys objectKeys(DocumentReference reference) throws IOException {
    try {
      return new DocumentObjectKeys(reference.sourceObjectKey());
    } catch (InvalidObjectStorageKeyException error) {
      throw new IOException("Document object key is invalid", error);
    }
  }

  private DocumentExtractionMetadata extractionMetadata(
      DocumentWorkerFrame<DocumentExtractionMetadata> response)
    throws DocumentWorkerProtocolException
  {
    DocumentExtractionMetadata metadata = response.result();

    if (metadata == null || !validator.validate(metadata).isEmpty()) {
      throw new DocumentWorkerProtocolException("Document worker extraction metadata is incomplete");
    }

    DocumentWorkerPayloadDescriptor textPayload = response.payloads()
        .stream()
        .filter(payload -> payload.role() == DocumentWorkerPayloadRole.TEXT)
        .findFirst()
        .orElseThrow(() -> new DocumentWorkerProtocolException(
            "Document worker extraction response has no text payload"));

    DocumentWorkerPayloadDescriptor artifactPayload = response.payloads()
        .stream()
        .filter(payload -> payload.role() == DocumentWorkerPayloadRole.ARTIFACT)
        .findFirst()
        .orElseThrow(() -> new DocumentWorkerProtocolException(
            "Document worker extraction response has no artifact payload"));

    if (textPayload.length() != metadata.textBytes()) {
      throw new DocumentWorkerProtocolException("Document worker extraction text length is invalid");
    }

    if (artifactPayload.length() == 0 || response.payloads().size() != 2) {
      throw new DocumentWorkerProtocolException("Document worker extraction payloads are invalid");
    }

    return metadata;
  }

  @Override
  public synchronized void close() {
    if (closed) {
      return;
    }
    try {
      if (sessionStarted) {
        output.writeRequest(new CloseDocumentWorkerRequest());
        input.readResponse();
      }
    } catch (IOException ignored) {
      // Closing the streams below terminates an unreachable or failed worker.
    } finally {
      closed = true;
      closeStreams();
      openDocuments.clear();
      legacyDocuments.clear();
    }
  }

  private void requireOpen() throws IOException {
    if (closed) {
      throw new IOException("Document worker connection is closed");
    }
  }

  private void closeStreams() {
    try {
      output.close();
    } catch (IOException ignored) {
    }
    try {
      input.close();
    } catch (IOException ignored) {
    }
  }
}
