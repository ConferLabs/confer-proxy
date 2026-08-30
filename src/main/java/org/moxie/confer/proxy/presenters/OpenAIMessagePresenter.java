package org.moxie.confer.proxy.presenters;

import com.openai.models.chat.completions.ChatCompletionContentPart;
import com.openai.models.chat.completions.ChatCompletionContentPartImage;
import com.openai.models.chat.completions.ChatCompletionContentPartText;
import com.openai.models.chat.completions.ChatCompletionMessageParam;
import com.openai.models.chat.completions.ChatCompletionToolMessageParam;
import com.openai.models.chat.completions.ChatCompletionUserMessageParam;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.moxie.confer.proxy.config.Config;
import org.moxie.confer.proxy.crypto.ImageToken;
import org.moxie.confer.proxy.images.ImageReference;
import org.moxie.confer.proxy.tools.ToolResult;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

@ApplicationScoped
public class OpenAIMessagePresenter {

  private static final String TOOL_IMAGE_CONTEXT =
      "Images returned by tools for visual verification.";

  private final Config     config;
  private final ImageToken imageToken;

  @Inject
  public OpenAIMessagePresenter(Config config, ImageToken imageToken) {
    this.config = config;
    this.imageToken = imageToken;
  }

  public ToolResultPresentation presentToolResult(String toolCallId, ToolResult result) {
    List<ChatCompletionMessageParam> messages        = new ArrayList<>();
    List<ImageReference>             imageReferences = result.images();

    ChatCompletionToolMessageParam toolMessage = ChatCompletionToolMessageParam.builder()
                                                                                .toolCallId(toolCallId)
                                                                                .content(result.modelContent())
                                                                                .build();
    messages.add(ChatCompletionMessageParam.ofTool(toolMessage));

    if (!imageReferences.isEmpty()) {
      messages.add(ChatCompletionMessageParam.ofUser(presentUserMessage(TOOL_IMAGE_CONTEXT, imageReferences)));
    }

    return new ToolResultPresentation(messages, imageReferences);
  }

  public ChatCompletionUserMessageParam presentUserMessage(String content,
                                                           List<ImageReference> imageReferences)
  {
    List<ChatCompletionContentPart> parts = new ArrayList<>();

    if (content != null && !content.isEmpty()) {
      parts.add(ChatCompletionContentPart.ofText(ChatCompletionContentPartText.builder()
                                                                              .text(content)
                                                                              .build()));
    }

    if (imageReferences != null) {
      for (ImageReference reference : imageReferences) {
        parts.add(presentImage(reference));
      }
    }

    return ChatCompletionUserMessageParam.builder()
                                         .content(ChatCompletionUserMessageParam.Content.ofArrayOfContentParts(parts))
                                         .build();
  }

  private ChatCompletionContentPart presentImage(ImageReference reference) {
    String imageUrl = "http://localhost:" + config.getServerPort()
        + "/v1/images?key=" + urlEncode(reference.s3Key())
        + "&ek=" + urlEncode(reference.encryptionKey())
        + "&token=" + urlEncode(imageToken.get())
        + "&type=" + urlEncode(reference.mediaType());

    ChatCompletionContentPartImage image = ChatCompletionContentPartImage.builder()
        .imageUrl(ChatCompletionContentPartImage.ImageUrl.builder().url(imageUrl).build())
        .build();

    return ChatCompletionContentPart.ofImageUrl(image);
  }

  private String urlEncode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }
}
