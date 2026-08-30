package org.moxie.confer.proxy.tools.documents;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.moxie.confer.proxy.documents.DocumentAccessException;
import org.moxie.confer.proxy.documents.DocumentToolSession;
import org.moxie.confer.proxy.tools.Tool;
import org.moxie.confer.proxy.tools.ToolExecutionContext;
import org.moxie.confer.proxy.tools.ToolResult;

import java.io.IOException;
import java.util.Objects;

public abstract class DocumentTool<Request, Response> implements Tool {

  private static final String GENERIC_ERROR = "Document retrieval failed";

  private final ObjectMapper mapper;

  protected DocumentTool(ObjectMapper mapper) {
    this.mapper = Objects.requireNonNull(mapper, "mapper");
  }

  @Override
  public ToolResult execute(String arguments, ToolExecutionContext context) {
    try {
      DocumentToolArguments values = readArguments(arguments);
      Request request = decodeArguments(values);
      DocumentToolOutput<Response> output = execute(request, context.getDocumentSession());
      String content = mapper.writeValueAsString(output.content());
      return new ToolResult(content, content, output.images());
    } catch (IOException | InvalidDocumentToolArgumentsException | DocumentAccessException error) {
      return error(error);
    }
  }

  protected abstract Request decodeArguments(DocumentToolArguments arguments)
    throws InvalidDocumentToolArgumentsException;

  protected abstract DocumentToolOutput<Response> execute(Request request,
                                                          DocumentToolSession session)
    throws IOException, DocumentAccessException;

  private DocumentToolArguments readArguments(String arguments)
    throws InvalidDocumentToolArgumentsException
  {
    try {
      return new DocumentToolArguments(mapper, mapper.readTree(arguments));
    } catch (JsonProcessingException error) {
      throw new InvalidDocumentToolArgumentsException(
          "Tool arguments are not valid JSON",
          error);
    }
  }

  private ToolResult error(Throwable error) {
    String message = GENERIC_ERROR;
    if ((error instanceof InvalidDocumentToolArgumentsException
        || error instanceof DocumentAccessException)
        && error.getMessage() != null)
    {
      message = error.getMessage();
    }

    try {
      return ToolResult.text(mapper.writeValueAsString(new DocumentToolError(message)));
    } catch (JsonProcessingException serializationError) {
      return ToolResult.text("{\"error\":\"Document retrieval failed\"}");
    }
  }
}
