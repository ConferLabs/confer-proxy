package org.moxie.confer.proxy.tools.registry;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.moxie.confer.proxy.tools.Tool;
import org.moxie.confer.proxy.tools.documents.FileOverviewTool;
import org.moxie.confer.proxy.tools.documents.FileReadTool;
import org.moxie.confer.proxy.tools.documents.FileSearchTool;
import org.moxie.confer.proxy.tools.documents.FileViewTool;
import org.moxie.confer.proxy.tools.web.PageFetchTool;
import org.moxie.confer.proxy.tools.web.WebSearchTool;
import org.moxie.confer.proxy.tools.workers.ExecCommandTool;
import org.moxie.confer.proxy.tools.workers.PublishFileTool;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

@ApplicationScoped
public class ToolRegistry {

  private final Map<String, Tool> tools;

  @Inject
  public ToolRegistry(WebSearchTool webSearchTool,
                      PageFetchTool pageFetchTool,
                      ExecCommandTool execCommandTool,
                      PublishFileTool publishFileTool,
                      FileOverviewTool fileOverviewTool,
                      FileSearchTool fileSearchTool,
                      FileReadTool fileReadTool,
                      FileViewTool fileViewTool)
  {
    Map<String, Tool> registeredTools = new LinkedHashMap<>();
    addTool(registeredTools, webSearchTool);
    addTool(registeredTools, pageFetchTool);
    addTool(registeredTools, execCommandTool);
    addTool(registeredTools, publishFileTool);
    addTool(registeredTools, fileOverviewTool);
    addTool(registeredTools, fileSearchTool);
    addTool(registeredTools, fileReadTool);
    addTool(registeredTools, fileViewTool);

    this.tools = Collections.unmodifiableMap(registeredTools);
  }

  public RequestToolSet forRequest(ToolEligibility eligibility) {
    Objects.requireNonNull(eligibility, "eligibility");

    Map<String, Tool> eligibleTools = new LinkedHashMap<>();

    for (Map.Entry<String, Tool> candidate : tools.entrySet()) {
      Tool tool = candidate.getValue();

      if (eligibility.satisfies(tool.getRequirements())) {
        eligibleTools.put(candidate.getKey(), tool);
      }
    }

    return new RequestToolSet(eligibleTools);
  }

  private static void addTool(Map<String, Tool> destination, Tool tool) {
    Objects.requireNonNull(tool, "tool");

    String name = Objects.requireNonNull(tool.getName(), "tool name");

    if (name.isBlank()) {
      throw new IllegalStateException("Server tool name must not be blank");
    }

    if (destination.putIfAbsent(name, tool) != null) {
      throw new IllegalStateException("Duplicate server tool name: " + name);
    }
  }
}
