package org.moxie.confer.proxy.tools;

import com.openai.models.FunctionDefinition;

import java.io.OutputStream;

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
   * @param toolCallId The ID of the tool call
   * @param output Output stream to send notifications to client
   * @return The result of the tool execution (full result for model context)
   */
  String execute(String arguments, String toolCallId, OutputStream output);

  /**
   * Whether this tool makes external network requests (e.g. web search, page fetch).
   * Tools that return true can be disabled by the client via the webSearch flag.
   */
  default boolean hasExternalRequests() {
    return false;
  }

  /**
   * Get the client-friendly version of a tool result for persistence.
   * By default, returns the full result. Tools can override to provide
   * a summarized version to avoid storing large content.
   *
   * @param fullResult The full result from execute()
   * @return The result to send to the client for persistence
   */
  default String getClientResult(String fullResult) {
    return fullResult;
  }
}
