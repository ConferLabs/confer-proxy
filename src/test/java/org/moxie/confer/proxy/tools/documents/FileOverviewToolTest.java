package org.moxie.confer.proxy.tools.documents;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.moxie.confer.proxy.documents.DocumentAccessException;
import org.moxie.confer.proxy.documents.DocumentToolSession;
import org.moxie.confer.proxy.documents.requests.DocumentOverviewRequest;
import org.moxie.confer.proxy.documents.responses.DocumentOverviewMetadata;
import org.moxie.confer.proxy.documents.responses.DocumentOverviewResult;
import org.moxie.confer.proxy.tools.ToolExecutionContext;
import org.moxie.confer.proxy.tools.ToolRequirement;
import org.moxie.confer.proxy.tools.ToolResult;

import java.io.IOException;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FileOverviewToolTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Mock
  private DocumentToolSession documentSession;

  private ToolExecutionContext context;
  private FileOverviewTool     tool;

  @BeforeEach
  void setUp() {
    context = new ToolExecutionContext(documentSession);
    tool = new FileOverviewTool(MAPPER);
  }

  @Test
  void declaresItsNameAndRequirement() {
    assertEquals("file_overview", tool.getName());
    assertEquals(Set.of(ToolRequirement.DOCUMENTS), tool.getRequirements());

    JsonNode schema = MAPPER.valueToTree(
        tool.getFunctionDefinition().parameters().orElseThrow());
    assertTrue(schema.has("additionalProperties"));
    assertFalse(schema.path("additionalProperties").asBoolean());
  }

  @Test
  void decodesTypedRequestAndSerializesTypedResult() throws Exception {
    DocumentOverviewRequest request = new DocumentOverviewRequest("doc");
    DocumentOverviewResult overview = new DocumentOverviewResult(
        "doc",
        "Jung.pdf",
        new DocumentOverviewMetadata(1, "hash", "application/pdf", "Jung", 1, 0, 1),
        List.of(),
        false,
        List.of(),
        1,
        null,
        null);
    when(documentSession.overview(request)).thenReturn(overview);

    ToolResult result = tool.execute("{\"attachment_id\":\"doc\"}", context);
    JsonNode content = MAPPER.readTree(result.modelContent());

    assertEquals("doc", content.path("attachment_id").asText());
    assertEquals(1, content.path("first_page_number").asInt());
    assertEquals(1, content.path("metadata").path("page_count").asInt());
    assertFalse(content.path("metadata").has("container_count"));
    assertFalse(content.has("content"));
    verify(documentSession).overview(request);
  }

  @Test
  void presentsSafeCheckedErrors() throws Exception {
    DocumentOverviewRequest request = new DocumentOverviewRequest("missing");
    when(documentSession.overview(request)).thenThrow(
        new DocumentAccessException("Unknown attachment_id"));

    ToolResult malformed = tool.execute("[]", context);
    ToolResult unknown = tool.execute("{\"attachment_id\":\"missing\"}", context);

    assertEquals(
        "Tool arguments must be a JSON object",
        MAPPER.readTree(malformed.modelContent()).path("error").asText());
    assertEquals(
        "Unknown attachment_id",
        MAPPER.readTree(unknown.modelContent()).path("error").asText());
  }

  @Test
  void doesNotExposeWorkerFailureDetails() throws Exception {
    DocumentOverviewRequest request = new DocumentOverviewRequest("doc");
    when(documentSession.overview(request)).thenThrow(
        new IOException("sensitive worker detail"));

    ToolResult result = tool.execute("{\"attachment_id\":\"doc\"}", context);

    assertEquals(
        "Document retrieval failed",
        MAPPER.readTree(result.modelContent()).path("error").asText());
  }
}
