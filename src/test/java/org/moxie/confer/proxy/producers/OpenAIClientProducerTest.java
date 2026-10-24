package org.moxie.confer.proxy.producers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.client.OpenAIClient;
import com.openai.core.JsonValue;
import com.openai.core.http.StreamResponse;
import com.openai.models.ChatModel;
import com.openai.models.FunctionDefinition;
import com.openai.models.FunctionParameters;
import com.openai.models.chat.completions.ChatCompletionChunk;
import com.openai.models.chat.completions.ChatCompletionContentPart;
import com.openai.models.chat.completions.ChatCompletionContentPartImage;
import com.openai.models.chat.completions.ChatCompletionContentPartText;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.chat.completions.ChatCompletionFunctionTool;
import com.openai.models.chat.completions.ChatCompletionMessageParam;
import com.openai.models.chat.completions.ChatCompletionStreamOptions;
import com.openai.models.chat.completions.ChatCompletionUserMessageParam;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// The handler tests mock this client, so this is what sends a request through the real
// OkHttp transport, SSE parser and Kotlin-based JSON mapping the proxy uses to reach vLLM.
class OpenAIClientProducerTest {

  private static final String EVENT_STREAM = """
      data: {"id":"c1","object":"chat.completion.chunk","created":1,"model":"m","choices":[{"index":0,"delta":{"role":"assistant","reasoning":"Hmm"},"finish_reason":null}]}

      data: {"id":"c1","object":"chat.completion.chunk","created":1,"model":"m","choices":[{"index":0,"delta":{"content":"Hello"},"finish_reason":null}]}

      data: {"id":"c1","object":"chat.completion.chunk","created":1,"model":"m","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"web_search","arguments":"{\\"query\\":\\"x\\"}"}}]},"finish_reason":"tool_calls"}]}

      data: {"id":"c1","object":"chat.completion.chunk","created":1,"model":"m","choices":[],"usage":{"prompt_tokens":3,"completion_tokens":2,"total_tokens":5}}

      data: [DONE]

      """;

  private final AtomicReference<String> requestBody = new AtomicReference<>();

  private HttpServer server;

  @BeforeEach
  void startServer() throws IOException {
    server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    server.createContext("/v1/chat/completions", exchange -> {
      requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));

      byte[] body = EVENT_STREAM.getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
      exchange.sendResponseHeaders(200, body.length);

      try (OutputStream output = exchange.getResponseBody()) {
        output.write(body);
      }
    });
    server.start();
  }

  @AfterEach
  void stopServer() {
    server.stop(0);
  }

  @Test
  void streamsAChatCompletionThroughTheRealClient() throws IOException {
    String       baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/";
    OpenAIClient client  = new OpenAIClientProducer().getVllmAiClient()
                                                     .withOptions(options -> options.baseUrl(baseUrl));

    try (StreamResponse<ChatCompletionChunk> response = client.chat().completions().createStreaming(buildParams())) {
      List<ChatCompletionChunk> chunks = response.stream().toList();

      assertEquals(4, chunks.size());
      assertEquals("Hmm", chunks.get(0).choices().getFirst().delta()._additionalProperties().get("reasoning").asStringOrThrow());
      assertEquals("Hello", chunks.get(1).choices().getFirst().delta().content().orElseThrow());

      ChatCompletionChunk.Choice                choice   = chunks.get(2).choices().getFirst();
      ChatCompletionChunk.Choice.Delta.ToolCall toolCall = choice.delta().toolCalls().orElseThrow().getFirst();
      assertEquals(ChatCompletionChunk.Choice.FinishReason.TOOL_CALLS, choice.finishReason().orElseThrow());
      assertEquals("call_1", toolCall.id().orElseThrow());
      assertEquals("web_search", toolCall.function().orElseThrow().name().orElseThrow());
      assertEquals("{\"query\":\"x\"}", toolCall.function().orElseThrow().arguments().orElseThrow());

      assertEquals(5, chunks.get(3).usage().orElseThrow().totalTokens());
    } finally {
      client.close();
    }

    JsonNode request = new ObjectMapper().readTree(requestBody.get());
    assertEquals("m", request.path("model").asText());
    assertTrue(request.path("stream").asBoolean());
    assertTrue(request.path("stream_options").path("include_usage").asBoolean());
    assertEquals(false, request.path("chat_template_kwargs").path("enable_thinking").asBoolean(true));
    assertEquals("What is this?", request.path("messages").path(0).path("content").path(0).path("text").asText());
    assertEquals("http://localhost/v1/images?key=k", request.path("messages").path(0).path("content").path(1).path("image_url").path("url").asText());
    assertEquals("web_search", request.path("tools").path(0).path("function").path("name").asText());
  }

  private static ChatCompletionCreateParams buildParams() {
    ChatCompletionContentPartText text = ChatCompletionContentPartText.builder()
                                                                      .text("What is this?")
                                                                      .build();

    ChatCompletionContentPartImage image = ChatCompletionContentPartImage.builder()
                                                                         .imageUrl(ChatCompletionContentPartImage.ImageUrl.builder()
                                                                                                                          .url("http://localhost/v1/images?key=k")
                                                                                                                          .build())
                                                                         .build();

    ChatCompletionUserMessageParam message = ChatCompletionUserMessageParam.builder()
                                                                           .content(ChatCompletionUserMessageParam.Content.ofArrayOfContentParts(
                                                                               List.of(ChatCompletionContentPart.ofText(text),
                                                                                       ChatCompletionContentPart.ofImageUrl(image))))
                                                                           .build();

    FunctionDefinition webSearch = FunctionDefinition.builder()
                                                     .name("web_search")
                                                     .description("Search the web")
                                                     .parameters(FunctionParameters.builder()
                                                                                   .putAdditionalProperty("type", JsonValue.from("object"))
                                                                                   .build())
                                                     .build();

    return ChatCompletionCreateParams.builder()
                                     .model(ChatModel.of("m"))
                                     .streamOptions(ChatCompletionStreamOptions.builder()
                                                                               .includeUsage(true)
                                                                               .build())
                                     .putAdditionalBodyProperty("chat_template_kwargs", JsonValue.from(Map.of("enable_thinking", false)))
                                     .addMessage(ChatCompletionMessageParam.ofUser(message))
                                     .addTool(ChatCompletionFunctionTool.builder()
                                                                        .function(webSearch)
                                                                        .build())
                                     .build();
  }
}
