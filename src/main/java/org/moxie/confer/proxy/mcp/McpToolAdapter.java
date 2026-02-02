package org.moxie.confer.proxy.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.core.JsonValue;
import com.openai.models.FunctionDefinition;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.moxie.confer.proxy.tools.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * Adapts an MCP tool to the confer-proxy Tool interface.
 * Wraps MCP tool definitions and routes execution through the MCP client.
 */
public class McpToolAdapter implements Tool {

  private static final Logger LOG = LoggerFactory.getLogger(McpToolAdapter.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final McpSyncClient client;
  private final McpSchema.Tool mcpTool;
  private final String serverName;
  private final String namespacedName;

  public McpToolAdapter(McpSyncClient client, McpSchema.Tool mcpTool, String serverName) {
    this.client = client;
    this.mcpTool = mcpTool;
    this.serverName = serverName;
    // Namespace tool names to avoid conflicts: mcp_<server>_<tool>
    this.namespacedName = "mcp_" + serverName + "_" + mcpTool.name();
  }

  @Override
  public String getName() {
    return namespacedName;
  }

  @Override
  public FunctionDefinition getFunctionDefinition() {
    return FunctionDefinition.builder()
        .name(namespacedName)
        .description(buildDescription())
        .parameters(convertInputSchema())
        .build();
  }

  @Override
  public String execute(String arguments, String toolCallId, OutputStream output) {
    LOG.info("Executing MCP tool: {} (server: {}, original: {})", namespacedName, serverName, mcpTool.name());

    try {
      // Parse arguments from JSON string to Map
      Map<String, Object> args = parseArguments(arguments);

      // Call the MCP tool
      McpSchema.CallToolResult result = client.callTool(new McpSchema.CallToolRequest(mcpTool.name(), args));

      // Convert result to string
      return formatResult(result);

    } catch (Exception e) {
      LOG.error("Error executing MCP tool {}: {}", namespacedName, e.getMessage(), e);
      return "Error executing tool: " + e.getMessage();
    }
  }

  private String buildDescription() {
    String desc = mcpTool.description() != null ? mcpTool.description() : "";
    return String.format("[MCP:%s] %s", serverName, desc);
  }

  private JsonValue convertInputSchema() {
    // Convert MCP input schema to OpenAI-compatible JSON schema
    try {
      if (mcpTool.inputSchema() == null) {
        // Return empty object schema if no input schema
        return JsonValue.from(Map.of(
            "type", "object",
            "properties", Map.of()
        ));
      }

      // The MCP schema should already be JSON Schema compatible
      // Convert it to a JsonValue for the OpenAI SDK
      Map<String, Object> schema = new HashMap<>();
      schema.put("type", "object");

      if (mcpTool.inputSchema().properties() != null) {
        schema.put("properties", mcpTool.inputSchema().properties());
      } else {
        schema.put("properties", Map.of());
      }

      if (mcpTool.inputSchema().required() != null) {
        schema.put("required", mcpTool.inputSchema().required());
      }

      return JsonValue.from(schema);
    } catch (Exception e) {
      LOG.warn("Failed to convert input schema for tool {}: {}", namespacedName, e.getMessage());
      return JsonValue.from(Map.of("type", "object", "properties", Map.of()));
    }
  }

  private Map<String, Object> parseArguments(String arguments) throws JsonProcessingException {
    if (arguments == null || arguments.isBlank()) {
      return Map.of();
    }
    return MAPPER.readValue(arguments, new TypeReference<>() {});
  }

  private String formatResult(McpSchema.CallToolResult result) {
    if (result == null || result.content() == null || result.content().isEmpty()) {
      return "";
    }

    StringBuilder sb = new StringBuilder();
    for (McpSchema.Content content : result.content()) {
      if (content instanceof McpSchema.TextContent textContent) {
        sb.append(textContent.text());
      } else if (content instanceof McpSchema.ImageContent imageContent) {
        sb.append("[Image: ").append(imageContent.mimeType()).append("]");
      } else if (content instanceof McpSchema.EmbeddedResource embeddedResource) {
        sb.append("[Resource: ").append(embeddedResource.resource().uri()).append("]");
      } else {
        sb.append("[Unknown content type]");
      }
      sb.append("\n");
    }

    // Check if the tool reported an error
    if (result.isError() != null && result.isError()) {
      return "Tool error: " + sb.toString().trim();
    }

    return sb.toString().trim();
  }

  /**
   * Get the original MCP tool name (without namespace prefix).
   */
  public String getOriginalToolName() {
    return mcpTool.name();
  }

  /**
   * Get the server name this tool belongs to.
   */
  public String getServerName() {
    return serverName;
  }
}
