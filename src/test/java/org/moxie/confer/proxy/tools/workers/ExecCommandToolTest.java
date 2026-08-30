package org.moxie.confer.proxy.tools.workers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.moxie.confer.proxy.documents.DocumentToolSession;
import org.moxie.confer.proxy.tools.ToolExecutionContext;
import org.moxie.confer.proxy.tools.ToolResult;
import org.moxie.confer.proxy.workers.WorkerCommandResult;
import org.moxie.confer.proxy.workers.WorkerException;
import org.moxie.confer.proxy.workers.WorkerWorkspace;

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

class ExecCommandToolTest {

  private static final String ERROR = "{\"error\":\"Worker execution failed\"}";

  private ObjectMapper         mapper;
  private ExecCommandTool      tool;
  private DocumentToolSession  documents;
  private WorkerWorkspace      workspace;
  private ToolExecutionContext context;

  @BeforeEach
  void setUp() {
    mapper        = new ObjectMapper();
    tool          = new ExecCommandTool(mapper);
    documents     = mock(DocumentToolSession.class);
    workspace     = mock(WorkerWorkspace.class);
    context       = new ToolExecutionContext(documents, workspace);
  }

  @Test
  void exposesTheToolContract() {
    assertEquals("exec_command", tool.getName());
    assertEquals("exec_command", tool.getFunctionDefinition().name());
    assertTrue(tool.getFunctionDefinition().description().orElseThrow()
        .contains("confidential Linux workspace"));
    assertTrue(tool.getFunctionDefinition().description().orElseThrow()
        .contains("Commands start in the workspace root"));
    assertTrue(tool.getFunctionDefinition().description().orElseThrow()
        .contains("using && so later steps run only after earlier steps succeed"));
    assertTrue(tool.getFunctionDefinition().description().orElseThrow()
        .contains("Use separate calls when a result determines the next action"));

    JsonNode schema = mapper.valueToTree(
        tool.getFunctionDefinition().parameters().orElseThrow());
    assertEquals("object", schema.path("type").asText());
    assertTrue(schema.has("additionalProperties"));
    assertFalse(schema.path("additionalProperties").asBoolean());
    assertEquals(Set.of("activity", "cmd", "inputs"), getFieldNames(schema.path("properties")));
    assertEquals("string", schema.path("properties").path("activity").path("type").asText());
    assertTrue(schema.path("properties")
        .path("activity")
        .path("description")
        .asText()
        .contains("user-facing description"));
    assertEquals("string", schema.path("properties").path("cmd").path("type").asText());
    assertEquals(
        "Shell command to execute in the worker workspace.",
        schema.path("properties").path("cmd").path("description").asText());
    assertEquals("array", schema.path("properties").path("inputs").path("type").asText());
    assertTrue(schema.path("properties")
        .path("inputs")
        .path("description")
        .asText()
        .contains("before its first use"));
    JsonNode input = schema.path("properties").path("inputs").path("items");
    assertFalse(input.path("additionalProperties").asBoolean());
    assertEquals(
        Set.of("attachment_id", "path"),
        getFieldNames(input.path("properties")));
    assertTrue(input.path("properties")
        .path("path")
        .path("description")
        .asText()
        .contains("filename extension"));
    assertTrue(input.path("properties")
        .path("path")
        .path("description")
        .asText()
        .contains("use directly"));
    assertEquals(Set.of("cmd", "inputs"), getTextValues(schema.path("required")));
  }

  @Test
  void returnsNonzeroCommandResultsWithoutChangingTheirMeaning() throws Exception {
    when(workspace.execute("python3 create.py", List.of(), documents)).thenReturn(
        new WorkerCommandResult(7, "conversion failed\n", true));

    ToolResult result = tool.execute(
        "{\"activity\":\"Creating the document\",\"cmd\":\"python3 create.py\",\"inputs\":[]}",
        context);
    JsonNode content = mapper.readTree(result.modelContent());

    assertEquals(7, content.path("exit_code").asInt());
    assertEquals("conversion failed\n", content.path("output").asText());
    assertTrue(content.path("truncated").asBoolean());
    assertEquals(result.modelContent(), result.clientContent());
    assertTrue(result.images().isEmpty());
    verify(workspace).execute("python3 create.py", List.of(), documents);
  }

  @Test
  void rejectsMalformedArgumentsAndInvalidInputShapesBeforeUsingTheWorker() {
    for (String arguments : List.of(
        "not-json",
        "null",
        "{}",
        "{\"cmd\":\"pwd\"}",
        "{\"cmd\":\"pwd\",\"inputs\":null}",
        "{\"cmd\":\"pwd\",\"inputs\":[null]}")) {
      ToolResult result = tool.execute(arguments, context);

      assertEquals(ERROR, result.modelContent());
      assertEquals(ERROR, result.clientContent());
      assertTrue(result.images().isEmpty());
    }
    verifyNoInteractions(workspace);
  }

  @Test
  void passesAttachmentBindingsToTheRequestWorkspace() throws Exception {
    WorkerCommandResult expected = new WorkerCommandResult(0, "done\n", false);
    when(workspace.execute(
        "python3 transform.py inputs/source.xlsx",
        List.of(new WorkerWorkspace.Input("attachment-source", "inputs/source.xlsx")),
        documents))
        .thenReturn(expected);

    ToolResult result = tool.execute(
        "{\"cmd\":\"python3 transform.py inputs/source.xlsx\",\"inputs\":[{"
            + "\"attachment_id\":\"attachment-source\","
            + "\"path\":\"inputs/source.xlsx\"}]}",
        context);

    assertEquals(0, mapper.readTree(result.modelContent()).path("exit_code").asInt());
    verify(workspace).execute(
        "python3 transform.py inputs/source.xlsx",
        List.of(new WorkerWorkspace.Input("attachment-source", "inputs/source.xlsx")),
        documents);
  }

  @Test
  void returnsWorkerFailureDetailsOnlyToTheModel() throws Exception {
    WorkerException failure = mock(WorkerException.class);
    when(failure.getMessage()).thenReturn("pip could not resolve dependency");
    when(workspace.execute("pip install example", List.of(), documents)).thenThrow(failure);

    ToolResult result = tool.execute(
        "{\"cmd\":\"pip install example\",\"inputs\":[]}",
        context);

    assertEquals(
        "pip could not resolve dependency",
        mapper.readTree(result.modelContent()).path("details").asText());
    assertEquals(ERROR, result.clientContent());
    assertFalse(result.clientContent().contains("dependency"));
    assertTrue(result.images().isEmpty());
  }

  private static Set<String> getFieldNames(JsonNode node) {
    Set<String> names = new HashSet<>();
    node.fieldNames().forEachRemaining(names::add);
    return Set.copyOf(names);
  }

  private static Set<String> getTextValues(JsonNode node) {
    Set<String> values = new HashSet<>();
    node.forEach(value -> values.add(value.asText()));
    return Set.copyOf(values);
  }
}
