package org.moxie.confer.proxy.documents;

import org.moxie.confer.proxy.documents.responses.DocumentPageViewContent;
import org.moxie.confer.proxy.images.ImageReference;

import java.util.List;
import java.util.Objects;

public record DocumentPageViewResult(DocumentPageViewContent content,
                                     List<ImageReference> images)
{

  public DocumentPageViewResult {
    Objects.requireNonNull(content, "content");
    images = List.copyOf(images);
  }

}
