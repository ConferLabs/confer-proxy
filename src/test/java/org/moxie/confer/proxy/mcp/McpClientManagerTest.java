package org.moxie.confer.proxy.mcp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.condition.EnabledIf;
import org.moxie.confer.proxy.config.Config;
import org.moxie.confer.proxy.tools.Tool;
import org.moxie.confer.proxy.tools.ToolRegistry;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Integration tests for MCP client functionality.
 *
 * These tests require npx to be available and will spawn actual MCP servers.
 * Run with: mvn test -Dtest=McpClientManagerTest
 */
class McpClientManagerTest {

  private Config config;
  private ToolRegistry toolRegistry;

  @BeforeEach
  void setUp() {
    config = mock(Config.class);
    // Create a real ToolRegistry with mocked dependencies
    toolRegistry = new ToolRegistry(mock(org.moxie.confer.proxy.tools.WebSearchTool.class),
                                     mock(org.moxie.confer.proxy.tools.PageFetchTool.class));
  }

  @Test
  void testMcpDisabled() {
    when(config.isMcpEnabled()).thenReturn(false);

    McpClientManager manager = new McpClientManager(config, toolRegistry);
    manager.init();

    assertEquals(0, manager.getConnectedServerCount());
    assertEquals(0, manager.getRegisteredToolCount());
  }

  @Test
  void testMcpEnabledButNoConfig() {
    when(config.isMcpEnabled()).thenReturn(true);
    when(config.getMcpServersConfig()).thenReturn("");

    McpClientManager manager = new McpClientManager(config, toolRegistry);
    manager.init();

    assertEquals(0, manager.getConnectedServerCount());
  }

  @Test
  void testInvalidJsonConfig() {
    when(config.isMcpEnabled()).thenReturn(true);
    when(config.getMcpServersConfig()).thenReturn("not valid json");

    McpClientManager manager = new McpClientManager(config, toolRegistry);
    // Should not throw, just log error
    manager.init();

    assertEquals(0, manager.getConnectedServerCount());
  }

  @Test
  @EnabledIf("isNpxAvailable")
  void testFilesystemMcpServer() throws Exception {
    // Create a temp directory for the test - use /private prefix for macOS compatibility
    Path tempDir = Files.createTempDirectory("mcp-test");
    String realPath = tempDir.toRealPath().toString(); // Resolves /var -> /private/var on macOS
    Path testFile = Path.of(realPath, "test.txt");
    Files.writeString(testFile, "Hello MCP!");

    try {
      String serverConfig = String.format("""
          {"servers":[{
            "name": "fs",
            "transport": "stdio",
            "command": "npx",
            "args": ["-y", "@modelcontextprotocol/server-filesystem", "%s"]
          }]}
          """, realPath);

      when(config.isMcpEnabled()).thenReturn(true);
      when(config.getMcpServersConfig()).thenReturn(serverConfig);
      when(config.getMcpRequestTimeoutSeconds()).thenReturn(30);

      McpClientManager manager = new McpClientManager(config, toolRegistry);
      manager.init();

      // Give the server time to start
      Thread.sleep(2000);

      // Check that we connected and discovered tools
      assertTrue(manager.getConnectedServerCount() > 0, "Should have connected to filesystem server");
      assertTrue(manager.getRegisteredToolCount() > 0, "Should have discovered tools");

      // Check that tools are registered with correct naming
      Tool readTool = toolRegistry.getTool("mcp_fs_read_file").orElse(null);
      assertNotNull(readTool, "Should have registered mcp_fs_read_file tool");

      // Try to execute the tool
      String args = String.format("{\"path\": \"%s\"}", testFile.toString());
      String result = readTool.execute(args, "test-call-id", new ByteArrayOutputStream());

      assertTrue(result.contains("Hello MCP!"), "Should read file contents: " + result);

      // Cleanup
      manager.shutdown();

    } finally {
      // Cleanup temp files
      Files.deleteIfExists(testFile);
      Files.deleteIfExists(tempDir);
    }
  }

  @Test
  @EnabledIf("isPathfinderMcpAvailable")
  void testPathfinderMcpServer() throws Exception {
    // Test with the user's existing Pathfinder MCP server
    String serverConfig = """
        {"servers":[{
          "name": "pf2e",
          "transport": "stdio",
          "command": "node",
          "args": ["/Users/heathernelson/Documents/Pathfinder-MCP/dist/index.js"]
        }]}
        """;

    when(config.isMcpEnabled()).thenReturn(true);
    when(config.getMcpServersConfig()).thenReturn(serverConfig);
    when(config.getMcpRequestTimeoutSeconds()).thenReturn(30);

    McpClientManager manager = new McpClientManager(config, toolRegistry);
    manager.init();

    // Give the server time to start
    Thread.sleep(2000);

    try {
      // Check that we connected
      assertTrue(manager.getConnectedServerCount() > 0, "Should have connected to Pathfinder MCP server");
      assertTrue(manager.getRegisteredToolCount() > 0, "Should have discovered tools");

      // List all registered tools
      System.out.println("=== Discovered Pathfinder MCP Tools ===");
      for (var tool : manager.getRegisteredTools()) {
        System.out.println("  - " + tool.getName());
      }

    } finally {
      manager.shutdown();
    }
  }

  static boolean isPathfinderMcpAvailable() {
    return Files.exists(Path.of("/Users/heathernelson/Documents/Pathfinder-MCP/dist/index.js"));
  }

  static boolean isNpxAvailable() {
    try {
      Process p = new ProcessBuilder("which", "npx").start();
      return p.waitFor() == 0;
    } catch (Exception e) {
      return false;
    }
  }
}
