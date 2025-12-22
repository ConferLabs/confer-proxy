package org.moxie.confer.proxy.entities;

import java.util.List;

public record ChatRequest(
  List<Message> messages,
  String model,
  Double temperature,
  Integer maxTokens,
  Boolean stream,
  Boolean json,
  Boolean thinking,
  Boolean webSearch
) {
  public enum Role {
    user, assistant, system, developer, tool_call, tool_response
  }

  public record Message(
    Role role,
    String content
  ) {}
}