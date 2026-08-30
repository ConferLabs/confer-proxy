package org.moxie.confer.proxy.controllers;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.validation.Validator;
import jakarta.ws.rs.WebApplicationException;
import org.moxie.confer.proxy.documents.DocumentDescriptor;
import org.moxie.confer.proxy.documents.DocumentLengthMismatchException;
import org.moxie.confer.proxy.documents.DocumentNotFoundException;
import org.moxie.confer.proxy.documents.DocumentObjectKeys;
import org.moxie.confer.proxy.documents.UnsupportedDocumentTypeException;
import org.moxie.confer.proxy.documents.extraction.StoredDocumentExtractor;
import org.moxie.confer.proxy.documents.requests.DocumentExtractionRequest;
import org.moxie.confer.proxy.documents.responses.DocumentExtractionResult;
import org.moxie.confer.proxy.documents.worker.DocumentExtractionRejectedException;
import org.moxie.confer.proxy.documents.worker.DocumentWorkerTimeoutException;
import org.moxie.confer.proxy.entities.WebsocketRequest;
import org.moxie.confer.proxy.storage.InvalidObjectStorageKeyException;
import org.moxie.confer.proxy.websocket.WebsocketConnectionContext;
import org.moxie.confer.proxy.websocket.WebsocketHandler;
import org.moxie.confer.proxy.websocket.WebsocketHandlerResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Map;

@ApplicationScoped
public class DocumentExtractionHandler implements WebsocketHandler {

  private static final Logger log = LoggerFactory.getLogger(DocumentExtractionHandler.class);

  private static final int DEFAULT_INLINE_TEXT_MAX_CHARACTERS = 256 * 1024;
  private static final int MAX_INLINE_TEXT_CHARACTERS         = 16 * 1024 * 1024;

  @Inject
  ObjectMapper mapper;

  @Inject
  Validator validator;

  @Inject
  StoredDocumentExtractor extractor;

  @Override
  public WebsocketHandlerResponse handle(WebsocketConnectionContext context,
                                         WebsocketRequest           request)
  {
    if (request.chunk().isPresent()) {
      throw new WebApplicationException("Stored document extraction must not contain file data", 400);
    }

    DocumentExtractionRequest options                 = parseRequest(request);
    int                       inlineTextMaxCharacters = inlineTextMaxCharacters(options);
    DocumentDescriptor        document                = documentDescriptor(options);
    DocumentObjectKeys        objectKeys              = objectKeys(options);

    DocumentExtractionResult result = extract(options, document, objectKeys, inlineTextMaxCharacters);
    byte[]                   body   = serialize(result);

    return new WebsocketHandlerResponse.StreamingResponse(
        Map.of(
            "Content-Length", Integer.toString(body.length),
            "Content-Type", "application/vnd.confer.document-extraction+json"),
        output -> output.write(body));
  }

  private byte[] serialize(DocumentExtractionResult result) {
    try {
      return mapper.writeValueAsBytes(result);
    } catch (JsonProcessingException error) {
      log.warn("Failed to serialize document extraction response", error);
      throw new WebApplicationException("Document extraction failed", 500);
    }
  }

  private DocumentExtractionResult extract(DocumentExtractionRequest options,
                                           DocumentDescriptor document,
                                           DocumentObjectKeys objectKeys,
                                           int inlineTextMaxCharacters)
  {
    try {
      return extractor.extract(document, objectKeys, options.encryptionKey(), inlineTextMaxCharacters);
    } catch (DocumentWorkerTimeoutException error) {
      throw new WebApplicationException(error.getMessage(), 503);
    } catch (DocumentExtractionRejectedException error) {
      log.warn("Document worker rejected extraction request");
      throw new WebApplicationException("Document extraction failed", 422);
    } catch (DocumentNotFoundException error) {
      throw new WebApplicationException("Stored document was not found", 422);
    } catch (DocumentLengthMismatchException error) {
      throw new WebApplicationException(error.getMessage(), 422);
    } catch (IOException error) {
      log.warn("Stored document extraction failed", error);
      throw new WebApplicationException("Document extraction failed", 502);
    }
  }

  private DocumentExtractionRequest parseRequest(WebsocketRequest request) {
    String body = request.body().orElseThrow(
        () -> new WebApplicationException("Request body with extraction options is required", 400));

    try {
      DocumentExtractionRequest options = mapper.readValue(body, DocumentExtractionRequest.class);

      if (options == null || !validator.validate(options).isEmpty()) {
        throw new WebApplicationException("Invalid document extraction request", 400);
      }

      return options;
    } catch (JsonProcessingException error) {
      throw new WebApplicationException("Invalid document extraction request", 400);
    }
  }

  private int inlineTextMaxCharacters(DocumentExtractionRequest request) {
    Long requested = request.inlineTextMaxCharacters();

    if (requested == null) {
      return DEFAULT_INLINE_TEXT_MAX_CHARACTERS;
    }
    return Math.toIntExact(Math.min(requested, MAX_INLINE_TEXT_CHARACTERS));
  }

  private DocumentDescriptor documentDescriptor(DocumentExtractionRequest request) {
    try {
      return new DocumentDescriptor(request.filename(), request.contentType(), request.totalLength());
    } catch (UnsupportedDocumentTypeException error) {
      throw new WebApplicationException(error.getMessage(), 415);
    }
  }

  private DocumentObjectKeys objectKeys(DocumentExtractionRequest request) {
    if (request.sourceObjectKey() == null || request.sourceObjectKey().isBlank() ||
        request.encryptionKey() == null   || request.encryptionKey().isBlank())
    {
      throw new WebApplicationException("Stored document reference is invalid", 400);
    }

    try {
      return new DocumentObjectKeys(request.sourceObjectKey());
    } catch (InvalidObjectStorageKeyException error) {
      throw new WebApplicationException("Stored document reference is invalid", 400);
    }
  }
}
