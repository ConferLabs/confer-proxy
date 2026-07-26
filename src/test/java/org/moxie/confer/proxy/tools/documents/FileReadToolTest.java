package org.moxie.confer.proxy.tools.documents;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.moxie.confer.proxy.documents.DocumentToolSession;
import org.moxie.confer.proxy.documents.requests.DocumentReadRequest;
import org.moxie.confer.proxy.documents.responses.DocumentReadResult;
import org.moxie.confer.proxy.tools.ToolExecutionContext;
import org.moxie.confer.proxy.tools.ToolRequirement;
import org.moxie.confer.proxy.tools.ToolResult;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FileReadToolTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Mock
  private DocumentToolSession documentSession;

  private ToolExecutionContext context;
  private FileReadTool         tool;

  @BeforeEach
  void setUp() {
    context = new ToolExecutionContext(documentSession);
    tool = new FileReadTool(MAPPER);
  }

  @Test
  void declaresItsNameAndRequirement() {
    assertEquals("file_read", tool.getName());
    assertEquals(Set.of(ToolRequirement.DOCUMENTS), tool.getRequirements());
    assertTrue(tool.getFunctionDefinition()
        .description()
        .orElseThrow()
        .contains("verify exact values, dates, quotations, and calculations"));

    JsonNode schema = MAPPER.valueToTree(
        tool.getFunctionDefinition().parameters().orElseThrow());
    JsonNode properties = schema.path("properties");
    assertTrue(schema.has("additionalProperties"));
    assertFalse(schema.path("additionalProperties").asBoolean());
    assertEquals(1, properties.path("page_number").path("minimum").asInt());
    assertEquals(1, properties.path("page_count").path("minimum").asInt());
    assertEquals(20, properties.path("page_count").path("maximum").asInt());
    assertEquals(1, properties.path("page_count").path("default").asInt());
  }

  @Test
  void decodesTypedRequestAndSerializesTypedResult() throws Exception {
    DocumentReadRequest request = new DocumentReadRequest("doc", 7, 3);
    DocumentReadResult read = new DocumentReadResult(
        "doc", "Jung.pdf", 7, 3, 100, List.of(), List.of(), "text", null);
    when(documentSession.read(request)).thenReturn(read);

    ToolResult result = tool.execute(
        "{\"attachment_id\":\"doc\",\"page_number\":7,\"page_count\":3}",
        context);
    JsonNode content = MAPPER.readTree(result.modelContent());

    assertEquals(7, content.path("page_number").asInt());
    assertEquals(3, content.path("page_count").asInt());
    verify(documentSession).read(request);
  }

  @Test
  void rejectsMissingOrInvalidPageCoordinates() throws Exception {
    ToolResult missingPage = tool.execute("{\"attachment_id\":\"doc\"}", context);
    ToolResult zeroPage = tool.execute(
        "{\"attachment_id\":\"doc\",\"page_number\":0}",
        context);
    ToolResult excessiveCount = tool.execute(
        "{\"attachment_id\":\"doc\",\"page_number\":1,\"page_count\":21}",
        context);

    assertEquals(
        "page_number is required",
        MAPPER.readTree(missingPage.modelContent()).path("error").asText());
    assertEquals(
        "page_number is outside the allowed range",
        MAPPER.readTree(zeroPage.modelContent()).path("error").asText());
    assertEquals(
        "page_count is outside the allowed range",
        MAPPER.readTree(excessiveCount.modelContent()).path("error").asText());
  }
}
