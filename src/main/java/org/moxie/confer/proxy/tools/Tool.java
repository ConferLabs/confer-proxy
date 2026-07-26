package org.moxie.confer.proxy.tools;

import com.openai.models.FunctionDefinition;

import java.util.Set;

public interface Tool {
  /**
   * Get the function definition for this tool
   */
  FunctionDefinition getFunctionDefinition();

  /**
   * Get the name of this tool
   */
  String getName();

  /**
   * Execute the tool with the given arguments
   *
   * @param arguments JSON string of tool arguments
   * @param context Request-specific resources available during this execution
   * @return Content and attachments produced by the tool
   */
  ToolResult execute(String arguments, ToolExecutionContext context);

  /**
   * Get the request capabilities that must be available before this tool can be
   * advertised or executed.
   */
  default Set<ToolRequirement> getRequirements() {
    return Set.of();
  }

}
