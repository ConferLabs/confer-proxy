package org.moxie.confer.proxy.entities;

public record ToolCallContent(
  String toolCallId,
  String toolName,
  String toolArguments
) {}
