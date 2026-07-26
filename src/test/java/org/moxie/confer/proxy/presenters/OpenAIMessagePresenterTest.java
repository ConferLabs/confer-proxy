package org.moxie.confer.proxy.presenters;

import com.openai.models.chat.completions.ChatCompletionContentPart;
import com.openai.models.chat.completions.ChatCompletionMessageParam;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.moxie.confer.proxy.config.Config;
import org.moxie.confer.proxy.crypto.ImageToken;
import org.moxie.confer.proxy.images.ImageReference;
import org.moxie.confer.proxy.tools.ToolImageAttachment;
import org.moxie.confer.proxy.tools.ToolResult;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OpenAIMessagePresenterTest {

  private static final String KEY = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";

  @Mock
  private Config config;

  private OpenAIMessagePresenter presenter;

  @BeforeEach
  void setUp() {
    presenter = new OpenAIMessagePresenter(config, new ImageToken());
  }

  @Test
  void presentsTextResultAsOneCorrelatedToolMessage() {
    ToolResultPresentation presentation = presenter.presentToolResult(
        "call-1",
        ToolResult.text("result"));

    assertEquals(1, presentation.messages().size());
    assertTrue(presentation.imageReferences().isEmpty());
    assertTrue(presentation.messages().getFirst().isTool());
    assertEquals("call-1", presentation.messages().getFirst().asTool().toolCallId());
    assertEquals("result", presentation.messages().getFirst().asTool().content().asText());
  }

  @Test
  void presentsImageResultAsOneSemanticResultWithTwoProtocolMessages() throws Exception {
    when(config.getServerPort()).thenReturn(8080);
    ImageReference image = new ImageReference("temporary-images/region", KEY, "image/png");
    ToolResult result = new ToolResult(
        "model result",
        "client result",
        List.of(new ToolImageAttachment(image)));

    ToolResultPresentation presentation = presenter.presentToolResult("call-1", result);

    assertEquals(List.of(image), presentation.imageReferences());
    assertEquals(2, presentation.messages().size());
    assertTrue(presentation.messages().getFirst().isTool());
    assertTrue(presentation.messages().getLast().isUser());

    List<ChatCompletionContentPart> parts = presentation.messages()
        .getLast()
        .asUser()
        .content()
        .asArrayOfContentParts();
    assertEquals(2, parts.size());
    assertEquals("Images returned by tools for visual verification.",
        parts.getFirst().asText().text());
    assertTrue(parts.getLast().asImageUrl().imageUrl().url().contains(
        "key=temporary-images%2Fregion"));
  }
}
