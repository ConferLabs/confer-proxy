package org.moxie.confer.proxy.tools.documents;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.moxie.confer.proxy.documents.DocumentPageViewResult;
import org.moxie.confer.proxy.documents.DocumentToolSession;
import org.moxie.confer.proxy.documents.requests.DocumentPageViewRequest;
import org.moxie.confer.proxy.documents.responses.DocumentPageViewContent;
import org.moxie.confer.proxy.images.ImageReference;
import org.moxie.confer.proxy.images.InvalidImageReferenceException;
import org.moxie.confer.proxy.tools.ToolExecutionContext;
import org.moxie.confer.proxy.tools.ToolImageAttachment;
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
class FileViewToolTest {

  private static final ObjectMapper   MAPPER = new ObjectMapper();
  private static final ImageReference IMAGE  = imageReference();

  @Mock
  private DocumentToolSession documentSession;

  private ToolExecutionContext context;
  private FileViewTool         tool;

  @BeforeEach
  void setUp() {
    context = new ToolExecutionContext(documentSession);
    tool = new FileViewTool(MAPPER);
  }

  @Test
  void declaresItsNameAndRequirement() {
    assertEquals("file_view", tool.getName());
    assertEquals(Set.of(ToolRequirement.DOCUMENTS), tool.getRequirements());
    assertTrue(tool.getFunctionDefinition()
        .description()
        .orElseThrow()
        .contains("table, form, chart, figure, scan, handwriting, spatial layout"));

    JsonNode schema = MAPPER.valueToTree(
        tool.getFunctionDefinition().parameters().orElseThrow());
    assertTrue(schema.has("additionalProperties"));
    assertFalse(schema.path("additionalProperties").asBoolean());
    assertEquals(
        1,
        schema.path("properties").path("page_number").path("minimum").asInt());
  }

  @Test
  void decodesTypedRequestAndSerializesTypedResult() throws Exception {
    DocumentPageViewRequest request = new DocumentPageViewRequest("doc", 7);
    DocumentPageViewContent page = new DocumentPageViewContent(
        "doc", "Jung.pdf", "image/png", 10, 20, 7, "attached");
    when(documentSession.viewPage(request)).thenReturn(
        new DocumentPageViewResult(page, List.of(IMAGE)));

    ToolResult result = tool.execute(
        "{\"attachment_id\":\"doc\",\"page_number\":7}",
        context);
    JsonNode content = MAPPER.readTree(result.modelContent());

    assertEquals(7, content.path("page_number").asInt());
    assertEquals(List.of(new ToolImageAttachment(IMAGE)), result.attachments());
    verify(documentSession).viewPage(request);
  }

  @Test
  void rejectsMissingOrInvalidPageNumber() throws Exception {
    ToolResult missingPage = tool.execute("{\"attachment_id\":\"doc\"}", context);
    ToolResult zeroPage = tool.execute(
        "{\"attachment_id\":\"doc\",\"page_number\":0}",
        context);

    assertEquals(
        "page_number is required",
        MAPPER.readTree(missingPage.modelContent()).path("error").asText());
    assertEquals(
        "page_number is outside the allowed range",
        MAPPER.readTree(zeroPage.modelContent()).path("error").asText());
  }

  private static ImageReference imageReference() {
    try {
      return new ImageReference(
          "temporary-images/rendered",
          "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
          "image/png");
    } catch (InvalidImageReferenceException error) {
      throw new ExceptionInInitializerError(error);
    }
  }
}
