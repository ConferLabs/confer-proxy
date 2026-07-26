package org.moxie.confer.proxy.tools;

import java.util.List;
import java.util.Objects;

public record ToolResult(String modelContent,
                         String clientContent,
                         List<ToolAttachment> attachments)
{
  public ToolResult {
    Objects.requireNonNull(modelContent, "modelContent");
    Objects.requireNonNull(clientContent, "clientContent");
    attachments = List.copyOf(attachments);
  }

  public static ToolResult text(String content) {
    return new ToolResult(content, content, List.of());
  }

  public static ToolResult text(String modelContent, String clientContent) {
    return new ToolResult(modelContent, clientContent, List.of());
  }
}
