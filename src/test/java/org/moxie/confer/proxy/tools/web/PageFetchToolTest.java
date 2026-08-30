package org.moxie.confer.proxy.tools.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.moxie.confer.proxy.documents.DocumentToolSession;
import org.moxie.confer.proxy.services.TavilySearchService;
import org.moxie.confer.proxy.tools.ToolExecutionContext;
import org.moxie.confer.proxy.tools.ToolResult;
import org.moxie.confer.proxy.workers.WorkerWorkspace;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PageFetchToolTest {

  @Mock
  private TavilySearchService tavilySearch;

  @Mock
  private DocumentToolSession documentSession;

  @Mock
  private WorkerWorkspace workerWorkspace;

  @Test
  void returnsFullModelContentAndBoundedClientContent() throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    PageFetchTool tool = new PageFetchTool(tavilySearch, mapper);
    String url = "https://example.com/article";
    String rawContent = "Complete article content";
    when(tavilySearch.extract(List.of(url))).thenReturn(
        new TavilySearchService.ExtractResponse(List.of(
            new TavilySearchService.ExtractResult(url, rawContent))));

    ToolResult result = tool.execute(
        "{\"urls\":[\"https://example.com/article\"]}",
        new ToolExecutionContext(documentSession, workerWorkspace));
    JsonNode clientResult = mapper.readTree(result.clientContent());

    assertTrue(result.modelContent().contains(rawContent));
    assertFalse(result.clientContent().contains(rawContent));
    assertEquals(url, clientResult.path("results").get(0).path("url").asText());
    assertEquals(
        rawContent.length(),
        clientResult.path("results").get(0).path("contentLength").asInt());
    assertTrue(result.images().isEmpty());
  }
}
