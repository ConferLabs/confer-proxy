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
public class WebSearchTool implements Tool {

  private static final Logger log = LoggerFactory.getLogger(WebSearchTool.class);
  private static final String TOOL_NAME = "web_search";

  private final TavilySearchService tavilySearch;
  private final ObjectMapper        mapper;

  @Inject
  public WebSearchTool(TavilySearchService tavilySearch, ObjectMapper mapper) {
    this.tavilySearch = tavilySearch;
    this.mapper = mapper;
  }

  @Override
  public FunctionDefinition getFunctionDefinition() {
    return FunctionDefinition.builder()
        .name(TOOL_NAME)
        .description("Search the web for current information, news, facts, or any information not in your training data. Use this when the user asks for current events, recent information, or facts you don't know.")
        .parameters(FunctionParameters.builder()
            .putAdditionalProperty("type", com.openai.core.JsonValue.from("object"))
            .putAdditionalProperty("properties", com.openai.core.JsonValue.from(Map.of(
                "query", Map.of(
                    "type", "string",
                    "description", "The search query"
                )
            )))
            .putAdditionalProperty("required", com.openai.core.JsonValue.from(List.of("query")))
            .build())
        .build();
  }

  @Override
  public String getName() {
    return TOOL_NAME;
  }

  @Override
  public boolean hasExternalRequests() {
    return true;
  }

  @Override
  public String execute(String arguments, String toolCallId, OutputStream output) {
    try {
      String                             query          = parseSearchQuery(arguments);
      TavilySearchService.SearchResponse searchResponse = tavilySearch.search(query);

      return formatSearchResults(query, searchResponse);
    } catch (IOException e) {
      log.error("Error executing web search", e);
      return "Error executing web search: " + e.getMessage();
    }
  }

  private String parseSearchQuery(String arguments) throws IOException {
    JsonNode argsNode = mapper.readTree(arguments);
    return argsNode.get("query").asText();
  }

  private String formatSearchResults(String query, TavilySearchService.SearchResponse searchResponse) {
    try {
      // Return JSON format for client parsing
      return mapper.writeValueAsString(Map.of(
          "query", query,
          "results", searchResponse.results()
      ));
    } catch (JsonProcessingException e) {
      log.error("Error serializing search results to JSON", e);
      return "Error formatting search results: " + e.getMessage();
    }
  }
}
