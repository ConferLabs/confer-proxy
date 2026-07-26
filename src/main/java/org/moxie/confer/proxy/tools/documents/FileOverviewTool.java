package org.moxie.confer.proxy.tools.documents;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.core.JsonValue;
import com.openai.models.FunctionDefinition;
import com.openai.models.FunctionParameters;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.moxie.confer.proxy.documents.DocumentAccessException;
import org.moxie.confer.proxy.documents.DocumentToolSession;
import org.moxie.confer.proxy.documents.requests.DocumentOverviewRequest;
import org.moxie.confer.proxy.documents.responses.DocumentOverviewResult;
import org.moxie.confer.proxy.tools.ToolRequirement;

import java.io.IOException;
import java.util.Map;
import java.util.Set;

@ApplicationScoped
public class FileOverviewTool
    extends DocumentTool<DocumentOverviewRequest, DocumentOverviewResult>
{

  private static final String NAME = "file_overview";

  @Inject
  public FileOverviewTool(ObjectMapper mapper) {
    super(mapper);
  }

  @Override
  public FunctionDefinition getFunctionDefinition() {
    return FunctionDefinition.builder()
        .name(NAME)
        .description("Get an overview of an attached file. Small files include their complete extracted text; larger files return metadata, a bounded outline with one-based page numbers, labeled pages, and the first page number. Call this first for summaries or when document structure matters.")
        .parameters(FunctionParameters.builder()
            .putAdditionalProperty("type", JsonValue.from("object"))
            .putAdditionalProperty("additionalProperties", JsonValue.from(false))
            .putAdditionalProperty("properties", JsonValue.from(Map.of(
                "attachment_id", Map.of(
                    "type", "string",
                    "description", "The attachment_id from the attached-file marker"))))
            .putAdditionalProperty("required", JsonValue.from(new String[] {"attachment_id"}))
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
  protected DocumentOverviewRequest decodeArguments(DocumentToolArguments arguments)
    throws InvalidDocumentToolArgumentsException
  {
    return new DocumentOverviewRequest(arguments.requiredText("attachment_id"));
  }

  @Override
  protected DocumentToolOutput<DocumentOverviewResult> execute(
      DocumentOverviewRequest request,
      DocumentToolSession session)
    throws IOException, DocumentAccessException
  {
    return new DocumentToolOutput<>(session.overview(request));
  }
}
