package org.moxie.confer.proxy.tools;

import org.moxie.confer.proxy.images.ImageReference;

import java.util.Objects;

public record ToolImageAttachment(ImageReference reference) implements ToolAttachment {

  public ToolImageAttachment {
    Objects.requireNonNull(reference, "reference");
  }
}
