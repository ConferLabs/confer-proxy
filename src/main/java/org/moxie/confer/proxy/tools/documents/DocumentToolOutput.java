package org.moxie.confer.proxy.tools.documents;

import org.moxie.confer.proxy.images.ImageReference;

import java.util.List;
import java.util.Objects;

public record DocumentToolOutput<T>(T content, List<ImageReference> images) {

  public DocumentToolOutput {
    Objects.requireNonNull(content, "content");
    images = List.copyOf(images);
  }

  public DocumentToolOutput(T content) {
    this(content, List.of());
  }
}
