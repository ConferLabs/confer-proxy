package org.moxie.confer.proxy.tools;

import org.moxie.confer.proxy.attachments.AttachmentReference;
import org.moxie.confer.proxy.images.ImageReference;

import java.util.List;
import java.util.Objects;

public record ToolResult(String modelContent,
                         String clientContent,
                         List<ImageReference> images,
                         List<AttachmentReference> attachments)
{
  public ToolResult {
    Objects.requireNonNull(modelContent, "modelContent");
    Objects.requireNonNull(clientContent, "clientContent");

    images      = List.copyOf(images     );
    attachments = List.copyOf(attachments);
  }

  public ToolResult(String               modelContent,
                    String               clientContent,
                    List<ImageReference> images)
  {
    this(modelContent, clientContent, images, List.of());
  }

  public static ToolResult text(String content) {
    return new ToolResult(content, content, List.of(), List.of());
  }

  public static ToolResult text(String modelContent, String clientContent) {
    return new ToolResult(modelContent, clientContent, List.of(), List.of());
  }
}
