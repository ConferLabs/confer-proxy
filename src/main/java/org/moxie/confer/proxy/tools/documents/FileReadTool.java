package org.moxie.confer.proxy.tools.documents;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.core.JsonValue;
import com.openai.models.FunctionDefinition;
import com.openai.models.FunctionParameters;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.moxie.confer.proxy.documents.DocumentAccessException;
import org.moxie.confer.proxy.documents.DocumentToolSession;
import org.moxie.confer.proxy.documents.requests.DocumentReadRequest;
import org.moxie.confer.proxy.documents.responses.DocumentReadResult;
import org.moxie.confer.proxy.tools.ToolRequirement;

import java.io.IOException;
import java.util.Map;
import java.util.Set;

@ApplicationScoped
public class FileReadTool extends DocumentTool<DocumentReadRequest, DocumentReadResult> {

  private static final String NAME = "file_read";
  private static final String DESCRIPTION =
      "Read the complete extracted text from one or more consecutive pages of an attached "
          + "file. Use to verify exact values, dates, quotations, and calculations located by "
          + "file_search, or whenever an excerpt lacks enough context to support the answer. "
          + "Do not use merely to confirm which file or page matched. If relevant page content "
          + "is visual or the extracted text remains ambiguous, use file_view. Page numbers "
          + "are one-based and are returned by file_overview and file_search. page_count "
          + "defaults to 1.";

  @Inject
  public FileReadTool(ObjectMapper mapper) {
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
                "attachment_id", Map.of("type", "string", "description", "The attachment_id to read"),
                "page_number", Map.of(
                    "type", "integer",
                    "minimum", 1,
                    "description", "First one-based page number to read"),
                "page_count", Map.of(
                    "type", "integer",
                    "minimum", 1,
                    "maximum", 20,
                    "default", 1,
                    "description", "Number of consecutive pages to read"))))
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
  protected DocumentReadRequest decodeArguments(DocumentToolArguments arguments)
    throws InvalidDocumentToolArgumentsException
  {
    return new DocumentReadRequest(
        arguments.requiredText("attachment_id"),
        arguments.requiredInteger("page_number", 1, Integer.MAX_VALUE),
        arguments.boundedInteger("page_count", 1, 1, 20));
  }

  @Override
  protected DocumentToolOutput<DocumentReadResult> execute(DocumentReadRequest request,
                                                           DocumentToolSession session)
    throws IOException, DocumentAccessException
  {
    return new DocumentToolOutput<>(session.read(request));
  }
}
