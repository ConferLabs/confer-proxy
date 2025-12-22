package org.moxie.confer.proxy.services;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.helidon.http.HeaderNames;
import io.helidon.webclient.api.HttpClientResponse;
import io.helidon.webclient.api.WebClient;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.moxie.confer.proxy.config.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class TavilySearchService {

  private static final Logger log = LoggerFactory.getLogger(TavilySearchService.class);

  private static final String TAVILY_SEARCH_URL = "https://api.tavily.com/search";
  private static final String TAVILY_EXTRACT_URL = "https://api.tavily.com/extract";

  @Inject
  Config config;

  @Inject
  ObjectMapper mapper;

  private final WebClient searchClient = WebClient.builder()
      .baseUri(TAVILY_SEARCH_URL)
      .build();

  private final WebClient extractClient = WebClient.builder()
      .baseUri(TAVILY_EXTRACT_URL)
      .build();

  public record SearchResult(String title, String url, String content, double score) {}

  public record SearchResponse(String query, List<SearchResult> results) {}

  public record ExtractResult(String url, String rawContent) {}

  public record ExtractResponse(List<ExtractResult> results) {}

  public SearchResponse search(String query) {
    return search(query, 5);
  }

  public SearchResponse search(String query, int maxResults) {
    Map<String, Object> requestBody = Map.of(
        "api_key", config.getTavilyApiKey(),
        "query", query,
        "max_results", maxResults,
        "search_depth", "basic",
        "include_answer", false
    );

    String requestJson;

    try {
      requestJson = mapper.writeValueAsString(requestBody);
    } catch (JsonProcessingException e) {
      log.error("Failed to serialize Tavily request", e);
      return new SearchResponse(query, List.of());
    }

    HttpClientResponse response = searchClient.post()
        .header(HeaderNames.CONTENT_TYPE, "application/json")
        .submit(requestJson);

    if (response.status().code() != 200) {
      log.error("Tavily search failed with status {}: {}", response.status().code(), response.as(String.class));
      return new SearchResponse(query, List.of());
    }

    String responseBody = response.as(String.class);
    JsonNode responseJson;
    try {
      responseJson = mapper.readTree(responseBody);
    } catch (IOException e) {
      log.error("Failed to parse Tavily response", e);
      return new SearchResponse(query, List.of());
    }

    List<SearchResult> results = new ArrayList<>();
    JsonNode resultsArray = responseJson.get("results");

    if (resultsArray != null && resultsArray.isArray()) {
      for (JsonNode resultNode : resultsArray) {
        results.add(new SearchResult(
            resultNode.get("title").asText(),
            resultNode.get("url").asText(),
            resultNode.get("content").asText(),
            resultNode.get("score").asDouble()
        ));
      }
    }

    return new SearchResponse(query, results);
  }

  public ExtractResponse extract(List<String> urls) {
    Map<String, Object> requestBody = Map.of(
        "api_key", config.getTavilyApiKey(),
        "urls", urls,
        "extract_depth", "basic",
        "format", "markdown"
    );

    String requestJson;

    try {
      requestJson = mapper.writeValueAsString(requestBody);
    } catch (JsonProcessingException e) {
      log.error("Failed to serialize Tavily extract request", e);
      return new ExtractResponse(List.of());
    }

    HttpClientResponse response = extractClient.post()
        .header(HeaderNames.CONTENT_TYPE, "application/json")
        .submit(requestJson);

    if (response.status().code() != 200) {
      log.error("Tavily extract failed with status {}: {}", response.status().code(), response.as(String.class));
      return new ExtractResponse(List.of());
    }

    String responseBody = response.as(String.class);
    JsonNode responseJson;
    try {
      responseJson = mapper.readTree(responseBody);
    } catch (IOException e) {
      log.error("Failed to parse Tavily extract response", e);
      return new ExtractResponse(List.of());
    }

    List<ExtractResult> results = new ArrayList<>();
    JsonNode resultsArray = responseJson.get("results");

    if (resultsArray != null && resultsArray.isArray()) {
      for (JsonNode resultNode : resultsArray) {
        results.add(new ExtractResult(
            resultNode.get("url").asText(),
            resultNode.get("raw_content").asText()
        ));
      }
    }

    log.info("Successfully extracted content from {} URLs", results.size());
    return new ExtractResponse(results);
  }
}
