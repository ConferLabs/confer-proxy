package org.moxie.confer.proxy.tools.documents;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.core.JsonValue;
import com.openai.models.FunctionDefinition;
import com.openai.models.FunctionParameters;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.moxie.confer.proxy.documents.DocumentAccessException;
import org.moxie.confer.proxy.documents.DocumentPageViewResult;
import org.moxie.confer.proxy.documents.DocumentToolSession;
import org.moxie.confer.proxy.documents.requests.DocumentPageViewRequest;
import org.moxie.confer.proxy.documents.responses.DocumentPageViewContent;
import org.moxie.confer.proxy.tools.ToolRequirement;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;

@ApplicationScoped
public class FileViewTool
    extends DocumentTool<DocumentPageViewRequest, DocumentPageViewContent>
{

  private static final String NAME = "file_view";
  private static final String DESCRIPTION =
      "Render one page of an attached PDF as an image and attach it to the next model turn. "
          + "Use before answering from a relevant table, form, chart, figure, scan, "
          + "handwriting, spatial layout, low-confidence extraction, or content not "
          + "represented reliably in extracted text. Page numbers are one-based and are "
          + "returned by file_overview, file_search, and file_read.";

  @Inject
  public FileViewTool(ObjectMapper mapper) {
    super(mapper);
  }

  @Override
  public FunctionDefinition getFunctionDefinition() {
    return FunctionDefinition.builder()
        .name(NAME)
        .description(DESCRIPTION)
        .parameters(FunctionParameters.builder()
            .putAdditionalProperty("type", JsonValue.from("object"))
            .putAdditionalProperty("additionalProperties", JsonValue.from(false))
            .putAdditionalProperty("properties", JsonValue.from(Map.of(
                "attachment_id", Map.of("type", "string", "description", "The attachment_id to view"),
                "page_number", Map.of(
                    "type", "integer",
                    "minimum", 1,
                    "description", "The one-based page number to render"))))
            .putAdditionalProperty("required", JsonValue.from(new String[] {"attachment_id", "page_number"}))
            .build())
        .build();
  }

  @Override
  public String getName() {
    return NAME;
  }

  @Override
  public Set<ToolRequirement> getRequirements() {
    return Set.of(ToolRequirement.DOCUMENTS);
  }

  @Override
  protected DocumentPageViewRequest decodeArguments(DocumentToolArguments arguments)
    throws InvalidDocumentToolArgumentsException
  {
    return new DocumentPageViewRequest(
        arguments.requiredText("attachment_id"),
        arguments.requiredInteger("page_number", 1, Integer.MAX_VALUE));
  }

  @Override
  protected DocumentToolOutput<DocumentPageViewContent> execute(
      DocumentPageViewRequest request,
      DocumentToolSession session)
    throws IOException, DocumentAccessException
  {
    DocumentPageViewResult result = session.viewPage(request);
    return new DocumentToolOutput<>(result.content(), result.images());
  }
}
