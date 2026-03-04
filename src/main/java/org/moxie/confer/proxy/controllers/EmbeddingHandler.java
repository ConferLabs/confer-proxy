package org.moxie.confer.proxy.controllers;

import ai.onnxruntime.OrtException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import org.moxie.confer.proxy.entities.EmbeddingRequest;
import org.moxie.confer.proxy.entities.EmbeddingResponse;
import org.moxie.confer.proxy.entities.WebsocketRequest;
import org.moxie.confer.proxy.services.EmbeddingService;
import org.moxie.confer.proxy.streaming.StreamRegistry;
import org.moxie.confer.proxy.websocket.WebsocketHandler;
import org.moxie.confer.proxy.websocket.WebsocketHandlerResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

@ApplicationScoped
public class EmbeddingHandler implements WebsocketHandler {

  private static final Logger log            = LoggerFactory.getLogger(EmbeddingHandler.class);
  private static final int    MAX_BATCH_SIZE = 64;

  @Inject
  EmbeddingService embeddingService;

  @Inject
  ObjectMapper mapper;

  @Override
  public WebsocketHandlerResponse handle(WebsocketRequest request, StreamRegistry streamRegistry) {
    EmbeddingRequest embeddingRequest = parseRequest(request);

    if (embeddingRequest.texts() == null || embeddingRequest.texts().isEmpty()) {
      throw new WebApplicationException("texts array is required and must not be empty", 400);
    }

    if (embeddingRequest.texts().size() > MAX_BATCH_SIZE) {
      throw new WebApplicationException("texts array must not exceed " + MAX_BATCH_SIZE + " elements", 400);
    }

    try {
      boolean           isQuery    = embeddingRequest.isQuery() != null && embeddingRequest.isQuery();
      List<float[]>     embeddings = isQuery
          ? embeddingService.embedQueryBatch(embeddingRequest.texts())
          : embeddingService.embedBatch(embeddingRequest.texts());
      EmbeddingResponse response   = new EmbeddingResponse(embeddings, embeddingService.getDimension());

      return new WebsocketHandlerResponse.SingleResponse(200, mapper.writeValueAsString(response));
    } catch (OrtException e) {
      log.error("Embedding inference failed", e);
      throw new WebApplicationException("Embedding inference failed", 500);
    } catch (JsonProcessingException e) {
      log.error("Failed to serialize embedding response", e);
      throw new WebApplicationException("Failed to serialize response", 500);
    }
  }

  private EmbeddingRequest parseRequest(WebsocketRequest request) {
    if (request.body().isEmpty()) {
      throw new WebApplicationException("Request body is required", 400);
    }

    try {
      return mapper.readValue(request.body().get(), EmbeddingRequest.class);
    } catch (JsonProcessingException e) {
      throw new WebApplicationException("Invalid EmbeddingRequest body", 400);
    }
  }
}
