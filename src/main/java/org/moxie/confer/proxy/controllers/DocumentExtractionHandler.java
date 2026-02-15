package org.moxie.confer.proxy.controllers;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import org.moxie.confer.proxy.config.Config;
import org.moxie.confer.proxy.entities.WebsocketRequest;
import org.moxie.confer.proxy.services.DoclingHttpClient;
import org.moxie.confer.proxy.streaming.StreamRegistry;
import org.moxie.confer.proxy.websocket.WebsocketHandler;
import org.moxie.confer.proxy.websocket.WebsocketHandlerResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.http.HttpResponse;
import java.nio.channels.Channels;
import java.nio.channels.Pipe;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

/**
 * Handles streaming document extraction requests.
 */
@ApplicationScoped
public class DocumentExtractionHandler implements WebsocketHandler {

  private static final Logger log = LoggerFactory.getLogger(DocumentExtractionHandler.class);

  @Inject
  ObjectMapper mapper;

  @Inject
  DoclingHttpClient doclingClient;

  @Inject
  Config config;

  /**
   * Request body JSON schema for streaming document extraction.
   */
  public record DocumentExtractionOptions(
      String filename,
      @JsonProperty("content_type") String contentType,
      @JsonProperty("total_length") Long totalLength,
      Boolean ocr,
      @JsonProperty("table_structure") Boolean tableStructure,
      @JsonProperty("include_images") Boolean includeImages,
      @JsonProperty("image_export_mode") String imageExportMode
  ) {
    public String contentTypeOrDefault() {
      return contentType != null ? contentType : "application/octet-stream";
    }
  }


  @Override
  public WebsocketHandlerResponse handle(WebsocketRequest request, StreamRegistry registry) {
    if (!config.isDoclingEnabled()) {
      throw new WebApplicationException("Document extraction is not enabled", 503);
    }

    if (request.chunk().isEmpty()) {
      throw new WebApplicationException("Streaming required for document extraction", 400);
    }

    DocumentExtractionOptions options   = parseOptions(request);
    long                      requestId = request.id();

    Pipe         pipe;
    OutputStream pipeOut;
    InputStream  pipeIn;

    try {
      pipe    = Pipe.open();
      pipeOut = Channels.newOutputStream(pipe.sink());
      pipeIn  = Channels.newInputStream(pipe.source());
      registry.createStream(requestId, pipeOut);
    } catch (IOException e) {
      log.error("Failed to create pipe for document extraction", e);
      throw new WebApplicationException("Document extraction failed", 500);
    }

    DoclingHttpClient.ConvertOptions convertOptions = new DoclingHttpClient.ConvertOptions(
        options.ocr(), options.tableStructure(), options.includeImages(), options.imageExportMode());

    CompletableFuture<HttpResponse<InputStream>> responseFuture =
        doclingClient.convertFile(pipeIn, options.filename(), options.contentTypeOrDefault(), convertOptions);

    responseFuture.whenComplete((result, error) -> {
      if (error != null) {
        closeQuietly(pipeIn);
      }
    });

    WebsocketRequest.StreamChunk firstChunk = request.chunk().get();

    try {
      registry.handleChunk(requestId, firstChunk.data(), firstChunk.sequenceNumber(), firstChunk.isFinal());
    } catch (IOException e) {
      log.error("Failed to write first chunk", e);
      registry.cancelStream(requestId);
      closeQuietly(pipeIn);
      responseFuture.cancel(true);
      throw new WebApplicationException("Document extraction failed", 500);
    }

    HttpResponse<InputStream> response;

    try {
      response = responseFuture.get();
    } catch (ExecutionException e) {
      log.error("HTTP request to docling failed", e.getCause());
      registry.cancelStream(requestId);
      closeQuietly(pipeIn);
      throw new WebApplicationException("Document extraction failed", 502);
    } catch (InterruptedException e) {
      log.error("HTTP request to docling interrupted", e);
      registry.cancelStream(requestId);
      closeQuietly(pipeIn);
      Thread.currentThread().interrupt();
      throw new WebApplicationException("Document extraction failed", 502);
    }

    if (response.statusCode() != 200) {
      log.warn("Docling returned non-200 status: {}", response.statusCode());
      registry.cancelStream(requestId);
      closeQuietly(pipeIn);
      closeResponseQuietly(response);
      throw new WebApplicationException("Document extraction failed", response.statusCode());
    }

    Map<String, String> headers = new HashMap<>();
    response.headers().firstValue("Content-Length").ifPresent(cl -> headers.put("Content-Length", cl));
    response.headers().firstValue("Content-Type").ifPresent(ct -> headers.put("Content-Type", ct));

    return new WebsocketHandlerResponse.StreamingResponse(headers, output -> {
      try (InputStream responseBody = response.body()) {
        responseBody.transferTo(output);
      } catch (IOException e) {
        log.error("IO error during document extraction", e);
        throw new WebApplicationException("Document extraction failed", 500);
      } finally {
        registry.cancelStream(requestId);
        closeQuietly(pipeIn);
      }
    });
  }

  private void closeQuietly(InputStream stream) {
    try {
      stream.close();
    } catch (IOException ignored) {
    }
  }

  private void closeResponseQuietly(HttpResponse<InputStream> response) {
    try {
      response.body().close();
    } catch (IOException ignored) {
    }
  }

  private DocumentExtractionOptions parseOptions(WebsocketRequest request) {
    if (request.body().isEmpty()) {
      throw new WebApplicationException("Request body with extraction options is required", 400);
    }

    try {
      DocumentExtractionOptions options = mapper.readValue(request.body().get(), DocumentExtractionOptions.class);

      if (options.filename() == null || options.filename().isBlank()) {
        throw new WebApplicationException("filename is required", 400);
      }

      return options;
    } catch (JsonProcessingException e) {
      throw new WebApplicationException("Invalid request body: " + e.getMessage(), 400);
    }
  }

}
