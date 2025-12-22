package org.moxie.confer.proxy.tools;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.models.FunctionDefinition;
import com.openai.models.FunctionParameters;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.moxie.confer.proxy.services.TavilySearchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class PageFetchTool implements Tool {

  private static final Logger log = LoggerFactory.getLogger(PageFetchTool.class);
  private static final String TOOL_NAME = "page_fetch";

  private final TavilySearchService tavilySearch;
  private final ObjectMapper        mapper;

  @Inject
  public PageFetchTool(TavilySearchService tavilySearch, ObjectMapper mapper) {
    this.tavilySearch = tavilySearch;
    this.mapper       = mapper;
  }

  @Override
  public FunctionDefinition getFunctionDefinition() {
    return FunctionDefinition.builder()
        .name(TOOL_NAME)
        .description("Fetch and extract the full content from one or more webpage URLs (max 20). Use this when you need to read the detailed content of specific pages that were found in search results or mentioned by the user.")
        .parameters(FunctionParameters.builder()
            .putAdditionalProperty("type", com.openai.core.JsonValue.from("object"))
            .putAdditionalProperty("properties", com.openai.core.JsonValue.from(Map.of(
                "urls", Map.of(
                    "type", "array",
                    "items", Map.of("type", "string"),
                    "description", "The URLs of the webpages to fetch and extract content from (maximum 20 URLs)",
                    "maxItems", 20
                )
            )))
            .putAdditionalProperty("required", com.openai.core.JsonValue.from(List.of("urls")))
            .build())
        .build();
  }

  @Override
  public String getName() {
    return TOOL_NAME;
  }

  @Override
  public String execute(String arguments, String toolCallId, OutputStream output) {
    try {
      List<String>                        urls            = parseUrls(arguments);
      TavilySearchService.ExtractResponse extractResponse = tavilySearch.extract(urls);

      return formatExtractResults(urls, extractResponse);
    } catch (IOException e) {
      log.error("Error fetching page content", e);
      return "Error fetching page content: " + e.getMessage();
    }
  }

  private List<String> parseUrls(String arguments) throws IOException {
    JsonNode argsNode = mapper.readTree(arguments);
    JsonNode urlsNode = argsNode.get("urls");

    List<String> urls = new java.util.ArrayList<>();
    if (urlsNode.isArray()) {
      for (JsonNode urlNode : urlsNode) {
        urls.add(urlNode.asText());
      }
    }

    return urls;
  }

  private String formatExtractResults(List<String> urls, TavilySearchService.ExtractResponse extractResponse) {
    try {
      // Return JSON format for client parsing
      return mapper.writeValueAsString(Map.of(
          "urls", urls,
          "results", extractResponse.results()
      ));
    } catch (JsonProcessingException e) {
      log.error("Error serializing extract results to JSON", e);
      return "Error formatting extract results: " + e.getMessage();
    }
  }

  @Override
  public String getClientResult(String fullResult) {
    try {
      // Parse the full result to extract just the metadata
      JsonNode resultNode = mapper.readTree(fullResult);
      JsonNode urlsNode = resultNode.get("urls");
      JsonNode resultsNode = resultNode.get("results");

      if (resultsNode == null || !resultsNode.isArray()) {
        // Fallback to safe error summary
        return "{\"error\": \"Failed to parse page_fetch result\"}";
      }

      // Create summaries without the large rawContent field
      List<Map<String, Object>> summaries = new java.util.ArrayList<>();
      for (JsonNode result : resultsNode) {
        String url = result.has("url") ? result.get("url").asText() : "unknown";
        String rawContent = result.has("rawContent") ? result.get("rawContent").asText() : "";

        summaries.add(Map.of(
            "url", url,
            "contentLength", rawContent.length(),
            "status", "success"
        ));
      }

      return mapper.writeValueAsString(Map.of(
          "urls", urlsNode,
          "results", summaries
      ));
    } catch (JsonProcessingException e) {
      log.warn("Failed to create client summary for page_fetch, returning error summary", e);
      // Safe fallback - small static error message
      return "{\"error\": \"Failed to summarize page_fetch result\"}";
    }
  }
}
