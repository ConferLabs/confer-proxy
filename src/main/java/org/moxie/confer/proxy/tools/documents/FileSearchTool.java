package org.moxie.confer.proxy.tools.documents;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.core.JsonValue;
import com.openai.models.FunctionDefinition;
import com.openai.models.FunctionParameters;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.validation.Validator;
import org.moxie.confer.proxy.documents.DocumentAccessException;
import org.moxie.confer.proxy.documents.DocumentToolSession;
import org.moxie.confer.proxy.documents.requests.DocumentSearchRequest;
import org.moxie.confer.proxy.documents.responses.DocumentSearchToolResult;
import org.moxie.confer.proxy.tools.ToolRequirement;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;

@ApplicationScoped
public class FileSearchTool
    extends DocumentTool<DocumentSearchRequest, DocumentSearchToolResult>
{

  private static final String NAME = "file_search";
  private static final String DESCRIPTION =
      "Search extracted text across files attached to this conversation. "
          + "Results are short excerpts that locate potentially relevant pages and may omit "
          + "surrounding context. Before relying on an exact value, date, quotation, or "
          + "calculation, use file_read on the returned page. Also use file_read whenever an "
          + "excerpt lacks enough context to support the answer. If the user only asks which "
          + "file or page contains a match, answer from the search result without reading the "
          + "page. If a relevant result identifies visual content, use file_view before "
          + "answering from it. Each item in queries is an alternative search, so queries are "
          + "combined with OR. Within a query, every literal word or exact phrase in all must "
          + "occur, so all entries are combined with AND. Put synonyms or equivalent "
          + "formulations in separate queries. Strings are literals, not regular expressions "
          + "or Boolean syntax. Omit attachment_id to search all files. A file with "
          + "has_more_results has additional matches; if needed, search that attachment "
          + "specifically with a narrower or complementary query.";

  private final DocumentSearchResultLimiter limiter;
  private final Validator                   validator;

  @Inject
  public FileSearchTool(ObjectMapper mapper, Validator validator) {
    super(mapper);
    limiter = new DocumentSearchResultLimiter(mapper);
    this.validator = validator;
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
                "attachment_id", Map.of(
                    "type", "string",
                    "description", "The attachment_id to search. Omit to search all files."),
                "queries", Map.of(
                    "type", "array",
                    "description", "Alternative searches combined with OR.",
                    "minItems", 1,
                    "maxItems", 8,
                    "items", Map.of(
                        "type", "object",
                        "additionalProperties", false,
                        "properties", Map.of(
                            "all", Map.of(
                                "type", "array",
                                "description", "Literal words or exact phrases that must all occur in the same passage.",
                                "minItems", 1,
                                "maxItems", 8,
                                "items", Map.of(
                                    "type", "string",
                                    "minLength", 1,
                                    "maxLength", 256))),
                        "required", List.of("all"))))))
            .putAdditionalProperty("required", JsonValue.from(List.of("queries")))
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
  protected DocumentSearchRequest decodeArguments(DocumentToolArguments arguments)
    throws InvalidDocumentToolArgumentsException
  {
    DocumentSearchRequest request = arguments.decode(DocumentSearchRequest.class);
    if (!validator.validate(request).isEmpty()) {
      throw new InvalidDocumentToolArgumentsException("file_search arguments are invalid");
    }
    return request;
  }

  @Override
  protected DocumentToolOutput<DocumentSearchToolResult> execute(
      DocumentSearchRequest request,
      DocumentToolSession session)
    throws IOException, DocumentAccessException
  {
    DocumentSearchToolResult result = session.search(request);
    return new DocumentToolOutput<>(limiter.limit(result));
  }
}
