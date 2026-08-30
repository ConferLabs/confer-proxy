package org.moxie.confer.proxy.tools.workers;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.core.JsonValue;
import com.openai.models.FunctionDefinition;
import com.openai.models.FunctionParameters;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.moxie.confer.proxy.tools.Tool;
import org.moxie.confer.proxy.tools.ToolExecutionContext;
import org.moxie.confer.proxy.tools.ToolResult;
import org.moxie.confer.proxy.workers.WorkerCommandResult;
import org.moxie.confer.proxy.workers.WorkerException;
import org.moxie.confer.proxy.workers.WorkerWorkspace;

import java.util.List;
import java.util.Map;

@ApplicationScoped
public class ExecCommandTool implements Tool {

  private static final String NAME = "exec_command";
  private static final String ERROR = "{\"error\":\"Worker execution failed\"}";

  private final ObjectMapper mapper;

  @Inject
  public ExecCommandTool(ObjectMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public FunctionDefinition getFunctionDefinition() {
    return FunctionDefinition.builder()
        .name(NAME)
        .description(
            "Run a non-interactive shell command in this request's shared confidential Linux "
                + "workspace. Commands start in the workspace root. Bind attachments to "
                + "workspace-relative input paths and use those paths directly; bindings persist "
                + "across calls. Python 3 "
                + "and document libraries (python-docx, openpyxl/XlsxWriter, "
                + "python-pptx, ReportLab/pypdf, Pillow), PDF utilities, and fonts are preinstalled; "
                + "use pip only for others. Minimize tool round trips: combine deterministic, "
                + "related steps in one command, using && so later steps run only after earlier "
                + "steps succeed. Prefer one script that completes and validates an edit over many "
                + "small commands. Use separate calls when a result determines the next action or "
                + "a step may block. Keep stdout/stderr concise because it may be truncated.")
        .parameters(FunctionParameters.builder()
            .putAdditionalProperty("type", JsonValue.from("object"))
            .putAdditionalProperty("additionalProperties", JsonValue.from(false))
            .putAdditionalProperty("properties", JsonValue.from(getProperties()))
            .putAdditionalProperty("required", JsonValue.from(List.of("cmd", "inputs")))
            .build())
        .build();
  }

  private static Map<String, Object> getProperties() {
    Map<String, Object> input = Map.of(
        "type", "object",
        "additionalProperties", false,
        "properties", Map.of(
            "attachment_id", Map.of(
                "type", "string",
                "description", "ID from the attached-file marker."),
            "path", Map.of(
                "type", "string",
                "description", "Workspace-relative path, including an appropriate filename "
                    + "extension, to use directly from the command.")),
        "required", List.of("attachment_id", "path"));
    return Map.of(
        "activity", Map.of(
            "type", "string",
            "description", "Short user-facing description of the task in present-participle "
                + "form without punctuation, for example 'Adding the landlord signature to "
                + "the lease'."),
        "cmd", Map.of(
            "type", "string",
            "description", "Shell command to execute in the worker workspace."),
        "inputs", Map.of(
            "type", "array",
            "description", "Attachments to copy into the workspace before this command. Include "
                + "each attachment before its first use; repeating the same binding is harmless.",
            "items", input));
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
      ExecCommandArguments request = mapper.readValue(arguments, ExecCommandArguments.class);

      if (request == null || request.inputs() == null || request.inputs().contains(null)) {
        return ToolResult.text(ERROR);
      }

      List<WorkerWorkspace.Input> inputs =
          request.inputs()
                 .stream()
                 .map(input -> new WorkerWorkspace.Input(input.attachmentId(), input.path()))
                 .toList();

      WorkerCommandResult result = context.getWorkerWorkspace().execute(request.cmd(), inputs, context.getDocumentSession());

      return ToolResult.text(mapper.writeValueAsString(new ExecCommandResult(result.exitCode(), result.output(), result.truncated())));
    } catch (WorkerException error) {
      return failure(error.getMessage());
    } catch (JsonProcessingException | IllegalArgumentException | IllegalStateException error) {
      return ToolResult.text(ERROR);
    }
  }

  private ToolResult failure(String details) {
    try {
      String safeDetails = details == null || details.isBlank()
          ? "Worker execution is temporarily unavailable"
          : details;
      return ToolResult.text(
          mapper.writeValueAsString(Map.of(
              "error", "Worker execution failed",
              "details", safeDetails)),
          ERROR);
    } catch (JsonProcessingException error) {
      return ToolResult.text(ERROR);
    }
  }

  private record ExecCommandArguments(String activity,
                                      String cmd,
                                      List<ExecCommandInput> inputs) {}

  private record ExecCommandInput(
      @JsonProperty("attachment_id") String attachmentId,
      String path) {}

  private record ExecCommandResult(
      @JsonProperty("exit_code") int exitCode,
      String output,
      boolean truncated) {}
}
