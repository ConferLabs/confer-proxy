package org.moxie.confer.proxy.tools.workers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.moxie.confer.proxy.attachments.AttachmentReference;
import org.moxie.confer.proxy.documents.DocumentToolSession;
import org.moxie.confer.proxy.tools.ToolExecutionContext;
import org.moxie.confer.proxy.tools.ToolResult;
import org.moxie.confer.proxy.workers.WorkerException;
import org.moxie.confer.proxy.workers.WorkerWorkspace;

import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PublishFileToolTest {

  private static final String ERROR = "{\"error\":\"File publication failed\"}";
  private static final AttachmentReference REFERENCE = new AttachmentReference(
      "01ARZ3NDEKTSV4RRFFQ69G5FAV",
      "report.pdf",
      "application/pdf",
      42,
      Base64.getEncoder().encodeToString(new byte[32]),
      "00000000-0000-0000-0000-000000000002/"
          + "01ARZ3NDEKTSV4RRFFQ69G5FAV",
      120L);

  private ObjectMapper          mapper;
  private PublishFileTool       tool;
  private DocumentToolSession   documents;
  private WorkerWorkspace       workspace;
  private ToolExecutionContext context;

  @BeforeEach
  void setUp() {
    mapper        = new ObjectMapper();
    tool          = new PublishFileTool(mapper);
    documents     = mock(DocumentToolSession.class);
    workspace     = mock(WorkerWorkspace.class);
    context       = new ToolExecutionContext(documents, workspace);
  }

  @Test
  void exposesTheToolContract() {
    assertEquals("publish_file", tool.getName());
    assertEquals("publish_file", tool.getFunctionDefinition().name());
    assertEquals(
        "Attach a completed file created by exec_command to the response so the user can "
            + "download it.",
        tool.getFunctionDefinition().description().orElseThrow());

    JsonNode schema = mapper.valueToTree(
        tool.getFunctionDefinition().parameters().orElseThrow());
    assertEquals("object", schema.path("type").asText());
    assertTrue(schema.has("additionalProperties"));
    assertFalse(schema.path("additionalProperties").asBoolean());
    assertEquals(Set.of("path"), getFieldNames(schema.path("properties")));
    assertEquals("string", schema.path("properties").path("path").path("type").asText());
    assertEquals(
        "Path to the file, relative to the working directory used by exec_command.",
        schema.path("properties").path("path").path("description").asText());
    assertEquals(1, schema.path("required").size());
    assertEquals("path", schema.path("required").get(0).asText());
  }

  @Test
  void publishesOnlyTheUserFacingMetadataAndKeepsTheCapabilityInTheAttachment()
      throws Exception
  {
    when(workspace.publishFile("reports/report.pdf")).thenReturn(REFERENCE);

    ToolResult result = tool.execute(
        "{\"path\":\"reports/report.pdf\"}",
        context);
    JsonNode content = mapper.readTree(result.modelContent());

    assertEquals("report.pdf", content.path("filename").asText());
    assertEquals(REFERENCE.id(), content.path("attachment_id").asText());
    assertEquals("application/pdf", content.path("content_type").asText());
    assertEquals(42, content.path("size").asLong());
    assertEquals(4, content.size());
    assertEquals(result.modelContent(), result.clientContent());
    assertFalse(result.modelContent().contains(REFERENCE.sourceObjectKey()));
    assertFalse(result.modelContent().contains(REFERENCE.encryptionKey()));
    assertTrue(result.images().isEmpty());
    assertEquals(List.of(REFERENCE), result.attachments());
    verify(workspace).publishFile("reports/report.pdf");
  }

  @Test
  void rejectsMalformedArgumentsBeforeUsingTheWorker() {
    for (String arguments : List.of(
        "not-json",
        "null")) {
      ToolResult result = tool.execute(arguments, context);

      assertEquals(ERROR, result.modelContent());
      assertEquals(ERROR, result.clientContent());
      assertTrue(result.images().isEmpty());
    }
    verifyNoInteractions(workspace);
  }

  @Test
  void returnsPublicationFailureDetailsOnlyToTheModel() throws Exception {
    WorkerException failure = mock(WorkerException.class);
    when(failure.getMessage()).thenReturn("The requested file does not exist");
    when(workspace.publishFile("missing.pdf")).thenThrow(failure);

    ToolResult result = tool.execute(
        "{\"path\":\"missing.pdf\"}",
        context);

    assertEquals(
        "The requested file does not exist",
        mapper.readTree(result.modelContent()).path("details").asText());
    assertEquals(ERROR, result.clientContent());
    assertFalse(result.clientContent().contains("does not exist"));
    assertTrue(result.images().isEmpty());
  }

  private static Set<String> getFieldNames(JsonNode node) {
    Set<String> names = new HashSet<>();
    node.fieldNames().forEachRemaining(names::add);
    return Set.copyOf(names);
  }
}
