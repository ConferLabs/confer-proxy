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
  @ConfigProperty(name = "server.port", defaultValue = "8080")
  private int serverPort;

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
  @ConfigProperty(name = "tavily_api_key")
  private String tavilyApiKey;

  @Inject
  @ConfigProperty(name = "tavily.extract_depth", defaultValue = "advanced")
  private String tavilyExtractDepth;

  @Inject
  @ConfigProperty(name = "tavily.search_depth", defaultValue = "basic")
  private String tavilySearchDepth;

  @Inject
  @ConfigProperty(name = "corsfix_api_key", defaultValue = "")
  private String corsfixApiKey;

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
  @ConfigProperty(name = "docling.enabled", defaultValue = "false")
  private boolean doclingEnabled;

  @Inject
  @ConfigProperty(name = "docling.port", defaultValue = "5001")
  private int doclingPort;

  @Inject
  @ConfigProperty(name = "document.worker.socket", defaultValue = "/run/confer/document-worker.sock")
  private String documentWorkerSocketPath;

  @Inject
  @ConfigProperty(name = "document.worker.max-connections", defaultValue = "8")
  private int documentWorkerMaxConnections;

  @Inject
  @ConfigProperty(name = "document.worker.acquire-timeout-seconds", defaultValue = "1800")
  private long documentWorkerAcquireTimeoutSeconds;

  @Inject
  @ConfigProperty(name = "vllm.served.model.name")
  private String vllmServedModelName;

  @Inject
  @ConfigProperty(name = "vllm.max.model.len", defaultValue = "262144")
  private int maxContextTokens;

  @Inject
  @ConfigProperty(name = "s3.bucket")
  private String s3Bucket;

  @Inject
  @ConfigProperty(name = "s3.region", defaultValue = "us-east-1")
  private String s3Region;


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

  public String getTavilyApiKey() {
    return tavilyApiKey;
  }

  public String getTavilyExtractDepth() {
    return tavilyExtractDepth;
  }

  public String getTavilySearchDepth() {
    return tavilySearchDepth;
  }

  public String getCorsfixApiKey() {
    return corsfixApiKey;
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

  public boolean isDoclingEnabled() {
    return doclingEnabled;
  }

  public int getDoclingPort() {
    return doclingPort;
  }

  public String getDocumentWorkerSocketPath() {
    return documentWorkerSocketPath;
  }

  public int getDocumentWorkerMaxConnections() {
    return documentWorkerMaxConnections;
  }

  public long getDocumentWorkerAcquireTimeoutSeconds() {
    return documentWorkerAcquireTimeoutSeconds;
  }

  public String getVllmServedModelName() {
    return vllmServedModelName;
  }

  public int getMaxContextTokens() {
    return maxContextTokens;
  }

  public int getServerPort() {
    return serverPort;
  }

  public String getS3Bucket() {
    return s3Bucket;
  }

  public String getS3Region() {
    return s3Region;
  }
}
