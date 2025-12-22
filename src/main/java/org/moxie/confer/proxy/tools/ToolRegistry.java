package org.moxie.confer.proxy.tools;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@ApplicationScoped
public class ToolRegistry {

  private final Map<String, Tool> tools = new HashMap<>();

  @Inject
  public ToolRegistry(WebSearchTool webSearchTool, PageFetchTool pageFetchTool) {
    registerTool(webSearchTool);
    registerTool(pageFetchTool);
  }

  public void registerTool(Tool tool) {
    tools.put(tool.getName(), tool);
  }

  public Optional<Tool> getTool(String name) {
    return Optional.ofNullable(tools.get(name));
  }

  public Map<String, Tool> getAllTools() {
    return Map.copyOf(tools);
  }
}
