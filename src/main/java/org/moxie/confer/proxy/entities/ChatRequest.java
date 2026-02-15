package org.moxie.confer.proxy.entities;

import java.util.List;
import java.util.Map;

public record ChatRequest(
  List<Message> messages,
  String model,
  Double temperature,
  Integer maxTokens,
  Boolean stream,
  Boolean json,
  Boolean thinking,
  Boolean webSearch,
  List<ClientTool> clientTools
) {
  public enum Role {
    user, assistant, system, developer, tool_call, tool_response
  }

  public record Message(
    Role role,
    String content
  ) {}

  public record ClientTool(
    String name,
    String description,
    Map<String, Object> parameters
  ) {}
}