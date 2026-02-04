package org.moxie.confer.proxy.services;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.moxie.confer.proxy.config.Config;
import org.moxie.confer.proxy.streaming.MultipartInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * HTTP client for streaming files to docling-serve.
 */
@ApplicationScoped
public class DoclingHttpClient {

  private static final Logger log = LoggerFactory.getLogger(DoclingHttpClient.class);
  private static final Duration REQUEST_TIMEOUT = Duration.ofMinutes(5);

  @Inject
  Config config;

  private final HttpClient httpClient = HttpClient.newBuilder()
      .version(HttpClient.Version.HTTP_1_1)
      .build();

  /**
   * Options for document conversion.
   */
  public record ConvertOptions(Boolean ocr, Boolean tableStructure, Boolean includeImages, String imageExportMode) {
    public static ConvertOptions defaults() {
      return new ConvertOptions(null, null, null, null);
    }
  }

  /**
   * Convert a document by streaming it to docling-serve.
   * Returns a CompletableFuture so the caller can begin writing to the pipe
   * before the HTTP client finishes reading from it.
   *
   * @param fileStream  The input stream containing the file data
   * @param filename    The original filename (used by docling for format detection)
   * @param contentType The MIME type of the file
   * @return A future that completes with the HTTP response
   */
  public CompletableFuture<HttpResponse<InputStream>> convertFile(
      InputStream fileStream,
      String filename,
      String contentType
  ) {
    return convertFile(fileStream, filename, contentType, ConvertOptions.defaults());
  }

  /**
   * Convert a document by streaming it to docling-serve with options.
   * Returns a CompletableFuture so the caller can begin writing to the pipe
   * before the HTTP client finishes reading from it.
   *
   * @param fileStream  The input stream containing the file data
   * @param filename    The original filename (used by docling for format detection)
   * @param contentType The MIME type of the file
   * @param options     Conversion options (OCR, table structure)
   * @return A future that completes with the HTTP response
   */
  public CompletableFuture<HttpResponse<InputStream>> convertFile(
      InputStream fileStream,
      String filename,
      String contentType,
      ConvertOptions options
  ) {
    String boundary = UUID.randomUUID().toString();

    List<MultipartInputStream.FormField> formFields = new ArrayList<>();
    formFields.add(new MultipartInputStream.FormField("ocr_engine", "rapidocr"));
    if (options.ocr() != null) {
      formFields.add(new MultipartInputStream.FormField("do_ocr", options.ocr().toString()));
    }
    if (options.tableStructure() != null) {
      formFields.add(new MultipartInputStream.FormField("do_table_structure", options.tableStructure().toString()));
    }
    if (options.includeImages() != null) {
      formFields.add(new MultipartInputStream.FormField("include_images", options.includeImages().toString()));
    }
    if (options.imageExportMode() != null) {
      formFields.add(new MultipartInputStream.FormField("image_export_mode", options.imageExportMode()));
    }

    MultipartInputStream multipartStream = new MultipartInputStream(
        boundary, fileStream, "files", filename, contentType, formFields);

    HttpRequest request = HttpRequest.newBuilder()
        .uri(URI.create("http://127.0.0.1:" + config.getDoclingPort() + "/v1/convert/file"))
        .header("Content-Type", "multipart/form-data; boundary=" + boundary)
        .timeout(REQUEST_TIMEOUT)
        .POST(HttpRequest.BodyPublishers.ofInputStream(() -> multipartStream))
        .build();

    return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream());
  }

  /**
   * Check if docling-serve is healthy.
   */
  public boolean isHealthy() {
    HttpRequest request = HttpRequest.newBuilder()
        .uri(URI.create("http://127.0.0.1:" + config.getDoclingPort() + "/health"))
        .GET()
        .build();

    try {
      HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
      return response.statusCode() == 200;
    } catch (IOException | InterruptedException e) {
      return false;
    }
  }

}
