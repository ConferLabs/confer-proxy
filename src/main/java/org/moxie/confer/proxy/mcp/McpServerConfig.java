package org.moxie.confer.proxy.mcp;

import java.util.List;
import java.util.Map;

/**
 * Configuration for a single MCP server connection.
 *
 * Example JSON:
 * {
 *   "name": "filesystem",
 *   "transport": "stdio",
 *   "command": "npx",
 *   "args": ["-y", "@modelcontextprotocol/server-filesystem", "/allowed/path"],
 *   "env": {"SOME_VAR": "value"}
 * }
 */
public record McpServerConfig(
    String name,
    String transport,  // "stdio" or "sse"
    String command,    // For stdio: the command to run
    List<String> args, // For stdio: command arguments
    Map<String, String> env, // Environment variables
    String url         // For SSE: the server URL
) {

  public McpServerConfig {
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("MCP server name is required");
    }
    if (transport == null || transport.isBlank()) {
      transport = "stdio";
    }
    if (args == null) {
      args = List.of();
    }
    if (env == null) {
      env = Map.of();
    }
  }

  public boolean isStdio() {
    return "stdio".equalsIgnoreCase(transport);
  }

  public boolean isSse() {
    return "sse".equalsIgnoreCase(transport);
  }
}
