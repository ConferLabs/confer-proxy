package org.moxie.confer.proxy.tools.documents;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.moxie.confer.proxy.documents.DocumentToolSession;
import org.moxie.confer.proxy.documents.requests.DocumentSearchQuery;
import org.moxie.confer.proxy.documents.requests.DocumentSearchRequest;
import org.moxie.confer.proxy.documents.responses.DocumentSearchDocumentResult;
import org.moxie.confer.proxy.documents.responses.DocumentSearchMatch;
import org.moxie.confer.proxy.tools.ToolExecutionContext;
import org.moxie.confer.proxy.tools.ToolRequirement;
import org.moxie.confer.proxy.tools.ToolResult;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FileSearchToolTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Mock
  private DocumentToolSession documentSession;

  private ToolExecutionContext context;
  private FileSearchTool       tool;
  private ValidatorFactory     validators;

  @BeforeEach
  void setUp() {
    validators = Validation.buildDefaultValidatorFactory();
    context = new ToolExecutionContext(documentSession);
    tool = new FileSearchTool(MAPPER, validators.getValidator());
  }

  @AfterEach
  void tearDown() {
    validators.close();
  }

  @Test
  void declaresItsNameAndRequirement() {
    assertEquals("file_search", tool.getName());
    assertEquals(Set.of(ToolRequirement.DOCUMENTS), tool.getRequirements());
    assertTrue(tool.getFunctionDefinition()
        .description()
        .orElseThrow()
        .contains("short excerpts that locate potentially relevant pages"));

    JsonNode schema = MAPPER.valueToTree(
        tool.getFunctionDefinition().parameters().orElseThrow());
    assertTrue(schema.has("additionalProperties"));
    assertFalse(schema.path("additionalProperties").asBoolean());
  }

  @Test
  void decodesTypedRequestAndSerializesTypedResult() throws Exception {
    DocumentSearchRequest request = new DocumentSearchRequest(
        List.of(
            new DocumentSearchQuery(List.of("total analyte", "ng/dL")),
            new DocumentSearchQuery(List.of("free analyte", "pg/mL"))),
        "doc");
    DocumentSearchDocumentResult search = new DocumentSearchDocumentResult(
        "doc",
        "Jung.pdf",
        List.of(new DocumentSearchMatch(7, 1, "match", List.of(), List.of())),
        true,
        1,
        null);
    when(documentSession.search(request)).thenReturn(search);

    ToolResult result = tool.execute(
        """
          {
            "attachment_id":"doc",
            "queries":[
              {"all":["total analyte","ng/dL"]},
              {"all":["free analyte","pg/mL"]}
            ]
          }
          """,
        context);
    JsonNode content = MAPPER.readTree(result.modelContent());

    assertEquals(1, content.path("matchCount").asInt());
    assertTrue(content.path("has_more_results").asBoolean());
    assertFalse(content.has("has_more"));
    assertEquals(7, content.path("hits").get(0).path("page_number").asInt());
    assertEquals("match", content.path("hits").get(0).path("excerpt").asText());
    assertFalse(content.path("hits").get(0).has("text"));
    assertFalse(content.path("hits").get(0).has("chunk_id"));
    verify(documentSession).search(request);
  }

  @Test
  void rejectsInvalidStructuredQueries() throws Exception {
    ToolResult result = tool.execute(
        "{\"queries\":[{\"all\":[]}]}",
        context);

    assertEquals(
        "file_search arguments are invalid",
        MAPPER.readTree(result.modelContent()).path("error").asText());
    verifyNoInteractions(documentSession);
  }
}
