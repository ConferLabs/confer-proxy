package org.moxie.confer.proxy.tools.workers;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.core.JsonValue;
import com.openai.models.FunctionDefinition;
import com.openai.models.FunctionParameters;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.moxie.confer.proxy.attachments.AttachmentReference;
import org.moxie.confer.proxy.tools.Tool;
import org.moxie.confer.proxy.tools.ToolExecutionContext;
import org.moxie.confer.proxy.tools.ToolResult;
import org.moxie.confer.proxy.workers.WorkerException;
import org.moxie.confer.proxy.workers.WorkerWorkspace;

import java.util.List;
import java.util.Map;

@ApplicationScoped
public class PublishFileTool implements Tool {

  private static final String NAME = "publish_file";
  private static final String ERROR = "{\"error\":\"File publication failed\"}";
  private final ObjectMapper mapper;

  @Inject
  public PublishFileTool(ObjectMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public FunctionDefinition getFunctionDefinition() {
    return FunctionDefinition.builder()
        .name(NAME)
        .description(
            "Attach a completed file created by exec_command to the response so the user can "
                + "download it.")
        .parameters(FunctionParameters.builder()
            .putAdditionalProperty("type", JsonValue.from("object"))
            .putAdditionalProperty("additionalProperties", JsonValue.from(false))
            .putAdditionalProperty("properties", JsonValue.from(Map.of(
                "path", Map.of(
                    "type", "string",
                    "description", "Path to the file, relative to the working directory used by "
                        + "exec_command."))))
            .putAdditionalProperty("required", JsonValue.from(List.of("path")))
            .build())
        .build();
  }

  @Override
  public String getName() {
    return NAME;
  }

  @Override
  public ToolResult execute(String               arguments,
                            ToolExecutionContext context)
  {
    try {
      PublishFileArguments request = mapper.readValue(
          arguments,
          PublishFileArguments.class);
      if (request == null) {
        return ToolResult.text(ERROR);
      }
      AttachmentReference reference = context.getWorkerWorkspace().publishFile(request.path());
      String content = mapper.writeValueAsString(new PublishFileResult(
          reference.id(),
          reference.filename(),
          reference.contentType(),
          reference.size()));
      return new ToolResult(
          content,
          content,
          List.of(),
          List.of(reference));
    } catch (WorkerException error) {
      return failure(error.getMessage());
    } catch (JsonProcessingException | IllegalArgumentException | IllegalStateException error) {
      return ToolResult.text(ERROR);
    }
  }

  private ToolResult failure(String details) {
    try {
      String safeDetails = details == null || details.isBlank()
          ? "File publication is temporarily unavailable"
          : details;
      return ToolResult.text(
          mapper.writeValueAsString(Map.of(
              "error", "File publication failed",
              "details", safeDetails)),
          ERROR);
    } catch (JsonProcessingException error) {
      return ToolResult.text(ERROR);
    }
  }

  private record PublishFileArguments(String path) {}

  private record PublishFileResult(
      @JsonProperty("attachment_id") String attachmentId,
      String filename,
      @JsonProperty("content_type") String contentType,
      long size) {}
}
