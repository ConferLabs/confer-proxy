package org.moxie.confer.proxy.controllers;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.validation.Validator;
import jakarta.ws.rs.WebApplicationException;
import org.moxie.confer.proxy.documents.DocumentDescriptor;
import org.moxie.confer.proxy.documents.UnsupportedDocumentTypeException;
import org.moxie.confer.proxy.documents.requests.LegacyDocumentExtractionRequest;
import org.moxie.confer.proxy.documents.responses.LegacyDocumentExtractionResponse;
import org.moxie.confer.proxy.documents.worker.DocumentExtraction;
import org.moxie.confer.proxy.documents.worker.DocumentExtractionRejectedException;
import org.moxie.confer.proxy.documents.worker.DocumentWorkerPayloadRole;
import org.moxie.confer.proxy.documents.worker.DocumentWorkerScheduler;
import org.moxie.confer.proxy.documents.worker.DocumentWorkerTimeoutException;
import org.moxie.confer.proxy.documents.worker.requests.DocumentWorkerRequestPayload;
import org.moxie.confer.proxy.documents.worker.requests.ExtractDocumentRequest;
import org.moxie.confer.proxy.entities.WebsocketRequest;
import org.moxie.confer.proxy.streaming.StreamRegistry;
import org.moxie.confer.proxy.websocket.WebsocketHandler;
import org.moxie.confer.proxy.websocket.WebsocketHandlerResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.Channels;
import java.nio.channels.Pipe;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

@ApplicationScoped
public class LegacyDocumentExtractionHandler implements WebsocketHandler {

  private static final Logger log = LoggerFactory.getLogger(LegacyDocumentExtractionHandler.class);

  @Inject
  ObjectMapper mapper;

  @Inject
  Validator validator;

  @Inject
  DocumentWorkerScheduler workers;

  @Override
  public WebsocketHandlerResponse handle(WebsocketRequest request, StreamRegistry registry) {
    WebsocketRequest.StreamChunk    firstChunk = request.chunk().orElseThrow(() -> new WebApplicationException("Streaming required for document extraction", 400));
    LegacyDocumentExtractionRequest options    = parseRequest(request);
    DocumentDescriptor              document   = documentDescriptor(options);
    long                            requestId  = request.id();

    try {
      Pipe pipe = Pipe.open();

      try (InputStream source = Channels.newInputStream(pipe.source())) {
        try {
          registry.createStream(requestId, Channels.newOutputStream(pipe.sink()), document.totalLength());

          CompletableFuture<DocumentExtraction> extraction = extract(document, source);
          extraction.whenComplete((result, error) -> {
            if (error != null) {
              closeQuietly(source);
            }
          });

          try {
            registry.handleChunk(requestId, firstChunk.data(), firstChunk.sequenceNumber(), firstChunk.isFinal());
          } catch (IOException | IllegalStateException error) {
            extraction.thenAccept(DocumentExtraction::close);
            throw error;
          }

          return WebsocketHandlerResponse.StreamingResponse.using(
              () -> new LegacyDocumentExtractionResponse(mapper, extraction.join()),
              LegacyDocumentExtractionResponse::headers,
              LegacyDocumentExtractionResponse::writeTo);
        } finally {
          registry.cancelStream(requestId);
        }
      }
    } catch (IOException | IllegalStateException error) {
      log.warn("Legacy document upload failed", error);
      throw new WebApplicationException("Document extraction failed", 500);
    } catch (CompletionException error) {
      Throwable cause = error.getCause();
      if (cause instanceof DocumentWorkerTimeoutException timeoutError) {
        throw new WebApplicationException(timeoutError.getMessage(), 503);
      }
      if (cause instanceof DocumentExtractionRejectedException) {
        log.warn("Document worker rejected legacy extraction request");
        throw new WebApplicationException("Document extraction failed", 422);
      }
      log.warn("Document worker request failed", cause);
      throw new WebApplicationException("Document extraction failed", 502);
    }
  }

  private CompletableFuture<DocumentExtraction> extract(DocumentDescriptor document,
                                                        InputStream sourceContent)
  {
    DocumentWorkerRequestPayload source = new DocumentWorkerRequestPayload(DocumentWorkerPayloadRole.SOURCE,
                                                                           document.mediaType(),
                                                                           document.totalLength(),
                                                                           sourceContent);

    ExtractDocumentRequest request = new ExtractDocumentRequest(document.filename(),
                                                                document.mediaType(),
                                                                source);

    return CompletableFuture.supplyAsync(() -> {
      try {
        return workers.extract(connection -> connection.extract(request));
      } catch (IOException error) {
        throw new CompletionException(error);
      }
    }, command -> Thread.startVirtualThread(command));
  }

  private void closeQuietly(InputStream stream) {
    try {
      stream.close();
    } catch (IOException ignored) {
    }
  }

  private LegacyDocumentExtractionRequest parseRequest(WebsocketRequest request) {
    String body = request.body().orElseThrow(
        () -> new WebApplicationException("Request body with extraction options is required", 400));

    try {
      LegacyDocumentExtractionRequest options = mapper.readValue(body, LegacyDocumentExtractionRequest.class);

      if (options == null || !validator.validate(options).isEmpty()) {
        throw new WebApplicationException("Invalid document extraction request", 400);
      }

      return options;
    } catch (JsonProcessingException error) {
      throw new WebApplicationException("Invalid document extraction request", 400);
    }
  }

  private DocumentDescriptor documentDescriptor(LegacyDocumentExtractionRequest request) {
    try {
      return new DocumentDescriptor(request.filename(),
                                    request.contentType(),
                                    request.totalLength());
    } catch (UnsupportedDocumentTypeException error) {
      throw new WebApplicationException(error.getMessage(), 415);
    }
  }

}
