package org.moxie.confer.proxy.tools.documents;

import org.moxie.confer.proxy.tools.ToolAttachment;

import java.util.List;
import java.util.Objects;

public record DocumentToolOutput<T>(T content, List<ToolAttachment> attachments) {

  public DocumentToolOutput {
    Objects.requireNonNull(content, "content");
    attachments = List.copyOf(attachments);
  }

  public DocumentToolOutput(T content) {
    this(content, List.of());
  }
}
