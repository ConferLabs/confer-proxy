package org.moxie.confer.proxy.presenters;

import com.openai.models.chat.completions.ChatCompletionMessageParam;
import org.moxie.confer.proxy.images.ImageReference;

import java.util.List;

public record ToolResultPresentation(List<ChatCompletionMessageParam> messages,
                                     List<ImageReference> imageReferences)
{
  public ToolResultPresentation {
    messages = List.copyOf(messages);
    imageReferences = List.copyOf(imageReferences);
  }
}
