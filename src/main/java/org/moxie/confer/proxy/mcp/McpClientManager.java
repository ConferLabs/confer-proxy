package org.moxie.confer.proxy.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpClientTransport;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.moxie.confer.proxy.config.Config;
import org.moxie.confer.proxy.tools.Tool;
import org.moxie.confer.proxy.tools.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages MCP client connections and registers discovered tools with the ToolRegistry.
 *
 * Reads server configurations from the mcp.servers.config property (JSON format)
 * and establishes connections to each configured MCP server.
 */
@ApplicationScoped
public class McpClientManager {

  private static final Logger LOG = LoggerFactory.getLogger(McpClientManager.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final McpJsonMapper JSON_MAPPER = new JacksonMcpJsonMapper(MAPPER);

  private final Config config;
  private final ToolRegistry toolRegistry;
  private final Map<String, McpSyncClient> clients = new ConcurrentHashMap<>();
  private final List<McpToolAdapter> registeredTools = new ArrayList<>();

  @Inject
  public McpClientManager(Config config, ToolRegistry toolRegistry) {
    this.config = config;
    this.toolRegistry = toolRegistry;
  }

  @PostConstruct
  void init() {
    if (!config.isMcpEnabled()) {
      LOG.info("MCP support is disabled");
      return;
    }

    String serversJson = config.getMcpServersConfig();
    if (serversJson == null || serversJson.isBlank()) {
      LOG.warn("MCP is enabled but no servers configured (mcp.servers.config is empty)");
      return;
    }

    try {
      List<McpServerConfig> servers = parseServerConfigs(serversJson);
      LOG.info("Initializing {} MCP server connection(s)", servers.size());

      for (McpServerConfig serverConfig : servers) {
        connectServer(serverConfig);
      }

      LOG.info("MCP initialization complete. Registered {} tools from {} servers",
          registeredTools.size(), clients.size());

    } catch (Exception e) {
      LOG.error("Failed to initialize MCP clients: {}", e.getMessage(), e);
    }
  }

  @PreDestroy
  void shutdown() {
    LOG.info("Shutting down {} MCP client(s)", clients.size());
    for (Map.Entry<String, McpSyncClient> entry : clients.entrySet()) {
      try {
        entry.getValue().closeGracefully();
        LOG.debug("Closed MCP client: {}", entry.getKey());
      } catch (Exception e) {
        LOG.warn("Error closing MCP client {}: {}", entry.getKey(), e.getMessage());
      }
    }
    clients.clear();
  }

  private List<McpServerConfig> parseServerConfigs(String json) throws Exception {
    // Support both array format and object with "servers" array
    var node = MAPPER.readTree(json);
    if (node.isArray()) {
      return MAPPER.readValue(json, new TypeReference<>() {});
    } else if (node.has("servers")) {
      return MAPPER.readValue(node.get("servers").toString(), new TypeReference<>() {});
    } else {
      throw new IllegalArgumentException("Invalid MCP config format: expected array or {\"servers\": [...]}");
    }
  }

  private void connectServer(McpServerConfig serverConfig) {
    LOG.info("Connecting to MCP server: {} (transport: {})", serverConfig.name(), serverConfig.transport());

    try {
      McpClientTransport transport = createTransport(serverConfig);
      McpSyncClient client = McpClient.sync(transport)
          .requestTimeout(Duration.ofSeconds(config.getMcpRequestTimeoutSeconds()))
          .build();

      // Initialize the connection
      client.initialize();
      LOG.info("Connected to MCP server: {}", serverConfig.name());

      // Discover and register tools
      McpSchema.ListToolsResult toolsResult = client.listTools();
      if (toolsResult.tools() != null && !toolsResult.tools().isEmpty()) {
        LOG.info("Discovered {} tools from server: {}", toolsResult.tools().size(), serverConfig.name());

        for (McpSchema.Tool mcpTool : toolsResult.tools()) {
          McpToolAdapter adapter = new McpToolAdapter(client, mcpTool, serverConfig.name());
          toolRegistry.registerTool(adapter);
          registeredTools.add(adapter);
          LOG.debug("Registered MCP tool: {} -> {}", mcpTool.name(), adapter.getName());
        }
      } else {
        LOG.info("No tools discovered from server: {}", serverConfig.name());
      }

      clients.put(serverConfig.name(), client);

    } catch (Exception e) {
      LOG.error("Failed to connect to MCP server {}: {}", serverConfig.name(), e.getMessage(), e);
    }
  }

  private McpClientTransport createTransport(McpServerConfig serverConfig) {
    if (serverConfig.isStdio()) {
      return createStdioTransport(serverConfig);
    } else if (serverConfig.isSse()) {
      return createSseTransport(serverConfig);
    } else {
      throw new IllegalArgumentException("Unknown transport type: " + serverConfig.transport());
    }
  }

  private McpClientTransport createStdioTransport(McpServerConfig serverConfig) {
    if (serverConfig.command() == null || serverConfig.command().isBlank()) {
      throw new IllegalArgumentException("Stdio transport requires 'command' to be specified");
    }

    ServerParameters.Builder builder = ServerParameters.builder(serverConfig.command());

    if (serverConfig.args() != null && !serverConfig.args().isEmpty()) {
      builder.args(serverConfig.args().toArray(new String[0]));
    }

    if (serverConfig.env() != null && !serverConfig.env().isEmpty()) {
      builder.env(serverConfig.env());
    }

    return new StdioClientTransport(builder.build(), JSON_MAPPER);
  }

  private McpClientTransport createSseTransport(McpServerConfig serverConfig) {
    if (serverConfig.url() == null || serverConfig.url().isBlank()) {
      throw new IllegalArgumentException("SSE transport requires 'url' to be specified");
    }

    return HttpClientSseClientTransport.builder(serverConfig.url())
        .jsonMapper(JSON_MAPPER)
        .build();
  }

  /**
   * Get the client for a specific server by name.
   */
  public McpSyncClient getClient(String serverName) {
    return clients.get(serverName);
  }

  /**
   * Check if a specific server is connected.
   */
  public boolean isConnected(String serverName) {
    return clients.containsKey(serverName);
  }

  /**
   * Get all registered MCP tools.
   */
  public List<Tool> getRegisteredTools() {
    return new ArrayList<>(registeredTools);
  }

  /**
   * Get the count of connected servers.
   */
  public int getConnectedServerCount() {
    return clients.size();
  }

  /**
   * Get the count of registered tools.
   */
  public int getRegisteredToolCount() {
    return registeredTools.size();
  }
}
