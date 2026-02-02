package org.moxie.confer.proxy.config;


import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;

@ApplicationScoped
public class Config {

  @Inject
  @ConfigProperty(name = "jwt.secret")
  private String jwtSecret;

  @Inject
  @ConfigProperty(name = "ita.url")
  private String itaUrl;

  @Inject
  @ConfigProperty(name = "ita.api_key")
  private String itaApiKey;

  @Inject
  @ConfigProperty(name = "site_url")
  private String siteUrl;

  @Inject
  @ConfigProperty(name = "together_api_key")
  private String togetherApiKey;

  @Inject
  @ConfigProperty(name = "tavily_api_key")
  private String tavilyApiKey;

  @Inject
  @ConfigProperty(name = "cors.allow-origins")
  private String allowedOrigins;

  @Inject
  @ConfigProperty(name = "manifest.path", defaultValue = "/run/confer/manifest.json")
  private String manifestPath;

  @Inject
  @ConfigProperty(name = "manifest.bundle.path", defaultValue = "/run/confer/manifest.bundle.json")
  private String manifestBundlePath;

  @Inject
  @ConfigProperty(name = "max_tool_iterations", defaultValue = "10")
  private int maxToolIterations;

  @Inject
  @ConfigProperty(name = "mcp.enabled", defaultValue = "false")
  private boolean mcpEnabled;

  @Inject
  @ConfigProperty(name = "mcp.servers.config", defaultValue = "")
  private String mcpServersConfig;

  @Inject
  @ConfigProperty(name = "mcp.request.timeout.seconds", defaultValue = "30")
  private int mcpRequestTimeoutSeconds;

  public List<String> getAllowedOrigins() {
    if (allowedOrigins == null) {
      return new LinkedList<>();
    }

    return Arrays.asList(allowedOrigins.split(","));
  }

  public String getJwtSecret() { return jwtSecret; }

  public String getItaUrl() {
    return itaUrl;
  }

  public String getItaApiKey() {
    return itaApiKey;
  }

  public String getSiteUrl() {
    return siteUrl;
  }

  public String getTogetherApiKey() {
    return togetherApiKey;
  }

  public String getTavilyApiKey() {
    return tavilyApiKey;
  }

  public String getManifestPath() {
    return manifestPath;
  }

  public String getManifestBundlePath() {
    return manifestBundlePath;
  }

  public int getMaxToolIterations() {
    return maxToolIterations;
  }

  public boolean isMcpEnabled() {
    return mcpEnabled;
  }

  public String getMcpServersConfig() {
    return mcpServersConfig;
  }

  public int getMcpRequestTimeoutSeconds() {
    return mcpRequestTimeoutSeconds;
  }
}
