package org.moxie.confer.proxy.documents.responses;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.moxie.confer.proxy.documents.worker.DocumentExtraction;
import org.moxie.confer.proxy.lifecycle.ManagedResource;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;

/** Streams the deployed Docling-compatible response without buffering its text. */
public class LegacyDocumentExtractionResponse implements ManagedResource {

  private static final long EMPTY_RESPONSE_BYTES =
      "{\"document\":{\"md_content\":\"\"}}".getBytes(StandardCharsets.UTF_8).length;

  private final ObjectMapper       mapper;
  private final DocumentExtraction extraction;

  public LegacyDocumentExtractionResponse(ObjectMapper mapper,
                                          DocumentExtraction extraction)
  {
    this.mapper     = Objects.requireNonNull(mapper, "mapper");
    this.extraction = Objects.requireNonNull(extraction, "extraction");
  }

  public Map<String, String> headers() {
    return Map.of(
        "Content-Length", Long.toString(EMPTY_RESPONSE_BYTES + extraction.jsonEscapedTextBytes()),
        "Content-Type", "application/json");
  }

  public void writeTo(OutputStream output) throws IOException {
    extraction.consumeText((descriptor, content) -> {
      try (JsonGenerator json = mapper.createGenerator(output)) {
        json.disable(JsonGenerator.Feature.AUTO_CLOSE_TARGET);
        json.enable(JsonGenerator.Feature.COMBINE_UNICODE_SURROGATES_IN_UTF8);
        json.writeStartObject();
        json.writeObjectFieldStart("document");
        json.writeFieldName("md_content");
        json.writeString(new InputStreamReader(content, StandardCharsets.UTF_8), -1);
        json.writeEndObject();
        json.writeEndObject();
      }
    });
  }

  @Override
  public void close() {
    extraction.close();
  }
}
