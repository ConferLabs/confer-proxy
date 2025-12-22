package org.moxie.confer.proxy.entities;

public record ToolResponseContent(
  String toolCallId,
  String toolName,
  String content
) {}
