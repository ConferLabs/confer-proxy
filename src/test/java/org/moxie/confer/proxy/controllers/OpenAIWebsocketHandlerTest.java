package org.moxie.confer.proxy.controllers;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.client.OpenAIClient;
import com.openai.core.http.StreamResponse;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionChunk;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.chat.completions.ChatCompletionMessage;
import com.openai.services.blocking.ChatService;
import com.openai.services.blocking.chat.ChatCompletionService;
import jakarta.ws.rs.WebApplicationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.moxie.confer.proxy.entities.ChatRequest;
import org.moxie.confer.proxy.entities.WebsocketRequest;
import org.moxie.confer.proxy.config.Config;
import org.moxie.confer.proxy.streaming.StreamRegistry;
import org.moxie.confer.proxy.tools.Tool;
import org.moxie.confer.proxy.tools.ToolRegistry;
import org.moxie.confer.proxy.websocket.WebsocketHandlerResponse;

import com.openai.models.FunctionDefinition;
import org.moxie.confer.proxy.entities.ToolCallContent;
import org.moxie.confer.proxy.entities.ToolResponseContent;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class OpenAIWebsocketHandlerTest {

  @Mock
  private OpenAIClient openAIClient;

  @Mock
  private ChatService chatService;

  @Mock
  private ChatCompletionService completionService;

  @Mock
  private ToolRegistry toolRegistry;

  @Mock
  private Config config;

  private ObjectMapper mapper;
  private OpenAIWebsocketHandler handler;
  private StreamRegistry streamRegistry;

  @BeforeEach
  void setUp() {
    mapper = new ObjectMapper();
    streamRegistry = new StreamRegistry();
    lenient().when(config.getMaxToolIterations()).thenReturn(10);
    handler = new OpenAIWebsocketHandler(openAIClient, mapper, toolRegistry, config);
  }

  @Test
  void handle_missingBody_throwsBadRequest() {
    WebsocketRequest request = new WebsocketRequest(1L, "POST", "/v1/chat/completions", Optional.empty());

    WebApplicationException exception = assertThrows(WebApplicationException.class, () -> handler.handle(request, streamRegistry));
    assertEquals(400, exception.getResponse().getStatus());
  }

  @Test
  void handle_invalidJson_throwsBadRequest() {
    WebsocketRequest request = new WebsocketRequest(1L, "POST", "/v1/chat/completions", Optional.of("not valid json"));

    WebApplicationException exception = assertThrows(WebApplicationException.class, () -> handler.handle(request, streamRegistry));
    assertEquals(400, exception.getResponse().getStatus());
  }

  @Test
  void handle_nullModel_throwsBadRequest() throws JsonProcessingException {
    ChatRequest chatRequest = new ChatRequest(
        List.of(new ChatRequest.Message(ChatRequest.Role.user, "Hello")),
        null,
        null,
        null,
        false,
        null,
        null,
        null,
        null
    );
    WebsocketRequest request = new WebsocketRequest(1L, "POST", "/v1/chat/completions", Optional.of(mapper.writeValueAsString(chatRequest)));

    assertThrows(Exception.class, () -> handler.handle(request, streamRegistry));
  }

  @Test
  void handle_nonStreamingRequest_returnsSingleResponse() throws Exception {
    ChatRequest chatRequest = new ChatRequest(
        List.of(new ChatRequest.Message(ChatRequest.Role.user, "Hello")),
        "gpt-4",
        null,
        null,
        false,
        null,
        null,
        null,
        null
    );
    WebsocketRequest request = new WebsocketRequest(1L, "POST", "/v1/chat/completions", Optional.of(mapper.writeValueAsString(chatRequest)));

    ChatCompletion mockCompletion = mock(ChatCompletion.class);
    ChatCompletion.Choice mockChoice = mock(ChatCompletion.Choice.class);
    ChatCompletionMessage mockMessage = mock(ChatCompletionMessage.class);

    when(openAIClient.chat()).thenReturn(chatService);
    when(chatService.completions()).thenReturn(completionService);
    when(completionService.create(any(ChatCompletionCreateParams.class))).thenReturn(mockCompletion);
    when(mockCompletion.choices()).thenReturn(List.of(mockChoice));
    when(mockChoice.message()).thenReturn(mockMessage);
    when(mockMessage.content()).thenReturn(Optional.of("Hello back!"));
    when(toolRegistry.getAllTools()).thenReturn(java.util.Map.of());

    WebsocketHandlerResponse response = handler.handle(request, streamRegistry);

    assertInstanceOf(WebsocketHandlerResponse.SingleResponse.class, response);
    WebsocketHandlerResponse.SingleResponse singleResponse = (WebsocketHandlerResponse.SingleResponse) response;
    assertEquals(200, singleResponse.statusCode());
    assertEquals("Hello back!", singleResponse.body());
  }

  @Test
  void handle_streamingRequest_returnsStreamingResponse() throws Exception {
    ChatRequest chatRequest = new ChatRequest(
        List.of(new ChatRequest.Message(ChatRequest.Role.user, "Hello")),
        "gpt-4",
        null,
        null,
        true,
        null,
        null,
        null,
        null
    );
    WebsocketRequest request = new WebsocketRequest(1L, "POST", "/v1/chat/completions", Optional.of(mapper.writeValueAsString(chatRequest)));

    WebsocketHandlerResponse response = handler.handle(request, streamRegistry);

    assertInstanceOf(WebsocketHandlerResponse.StreamingResponse.class, response);
  }

  @Test
  @SuppressWarnings("unchecked")
  void handle_streamingRequest_streamsTokensToOutput() throws Exception {
    ChatRequest chatRequest = new ChatRequest(
        List.of(new ChatRequest.Message(ChatRequest.Role.user, "Hello")),
        "gpt-4",
        null,
        null,
        true,
        null,
        null,
        null,
        null
    );
    WebsocketRequest request = new WebsocketRequest(1L, "POST", "/v1/chat/completions", Optional.of(mapper.writeValueAsString(chatRequest)));

    ChatCompletionChunk chunk1 = mock(ChatCompletionChunk.class);
    ChatCompletionChunk.Choice choice1 = mock(ChatCompletionChunk.Choice.class);
    ChatCompletionChunk.Choice.Delta delta1 = mock(ChatCompletionChunk.Choice.Delta.class);

    when(chunk1.choices()).thenReturn(List.of(choice1));
    when(choice1.delta()).thenReturn(delta1);
    when(choice1.finishReason()).thenReturn(Optional.empty());
    when(delta1.content()).thenReturn(Optional.of("Hello"));
    when(delta1.toolCalls()).thenReturn(Optional.empty());

    ChatCompletionChunk chunk2 = mock(ChatCompletionChunk.class);
    ChatCompletionChunk.Choice choice2 = mock(ChatCompletionChunk.Choice.class);
    ChatCompletionChunk.Choice.Delta delta2 = mock(ChatCompletionChunk.Choice.Delta.class);

    when(chunk2.choices()).thenReturn(List.of(choice2));
    when(choice2.delta()).thenReturn(delta2);
    when(choice2.finishReason()).thenReturn(Optional.of(ChatCompletionChunk.Choice.FinishReason.STOP));
    when(delta2.content()).thenReturn(Optional.of(" world!"));
    when(delta2.toolCalls()).thenReturn(Optional.empty());

    StreamResponse<ChatCompletionChunk> streamResponse = mock(StreamResponse.class);
    when(streamResponse.stream()).thenReturn(Stream.of(chunk1, chunk2));

    when(openAIClient.chat()).thenReturn(chatService);
    when(chatService.completions()).thenReturn(completionService);
    when(completionService.createStreaming(any(ChatCompletionCreateParams.class))).thenReturn(streamResponse);
    when(toolRegistry.getAllTools()).thenReturn(java.util.Map.of());

    WebsocketHandlerResponse response = handler.handle(request, streamRegistry);
    WebsocketHandlerResponse.StreamingResponse streamingResponse = (WebsocketHandlerResponse.StreamingResponse) response;

    ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
    streamingResponse.stream().write(outputStream);

    String output = outputStream.toString();
    assertTrue(output.contains("\"type\":\"token\""));
    assertTrue(output.contains("\"content\":\"Hello\""));
    assertTrue(output.contains("\"content\":\" world!\""));
    assertTrue(output.contains("\"type\":\"completion\""));
  }

  @Test
  void handle_withTemperature_passesTemperatureToClient() throws Exception {
    ChatRequest chatRequest = new ChatRequest(
        List.of(new ChatRequest.Message(ChatRequest.Role.user, "Hello")),
        "gpt-4",
        0.7,
        null,
        false,
        null,
        null,
        null,
        null
    );
    WebsocketRequest request = new WebsocketRequest(1L, "POST", "/v1/chat/completions", Optional.of(mapper.writeValueAsString(chatRequest)));

    ChatCompletion mockCompletion = mock(ChatCompletion.class);
    ChatCompletion.Choice mockChoice = mock(ChatCompletion.Choice.class);
    ChatCompletionMessage mockMessage = mock(ChatCompletionMessage.class);

    when(openAIClient.chat()).thenReturn(chatService);
    when(chatService.completions()).thenReturn(completionService);
    when(completionService.create(any(ChatCompletionCreateParams.class))).thenReturn(mockCompletion);
    when(mockCompletion.choices()).thenReturn(List.of(mockChoice));
    when(mockChoice.message()).thenReturn(mockMessage);
    when(mockMessage.content()).thenReturn(Optional.of("response"));
    when(toolRegistry.getAllTools()).thenReturn(java.util.Map.of());

    handler.handle(request, streamRegistry);

    verify(completionService).create(argThat((ChatCompletionCreateParams params) ->
        params.temperature().isPresent() && params.temperature().get().equals(0.7)
    ));
  }

  @Test
  void handle_withMaxTokens_passesMaxTokensToClient() throws Exception {
    ChatRequest chatRequest = new ChatRequest(
        List.of(new ChatRequest.Message(ChatRequest.Role.user, "Hello")),
        "gpt-4",
        null,
        100,
        false,
        null,
        null,
        null,
        null
    );
    WebsocketRequest request = new WebsocketRequest(1L, "POST", "/v1/chat/completions", Optional.of(mapper.writeValueAsString(chatRequest)));

    ChatCompletion mockCompletion = mock(ChatCompletion.class);
    ChatCompletion.Choice mockChoice = mock(ChatCompletion.Choice.class);
    ChatCompletionMessage mockMessage = mock(ChatCompletionMessage.class);

    when(openAIClient.chat()).thenReturn(chatService);
    when(chatService.completions()).thenReturn(completionService);
    when(completionService.create(any(ChatCompletionCreateParams.class))).thenReturn(mockCompletion);
    when(mockCompletion.choices()).thenReturn(List.of(mockChoice));
    when(mockChoice.message()).thenReturn(mockMessage);
    when(mockMessage.content()).thenReturn(Optional.of("response"));
    when(toolRegistry.getAllTools()).thenReturn(java.util.Map.of());

    handler.handle(request, streamRegistry);

    verify(completionService).create(argThat((ChatCompletionCreateParams params) ->
        params.maxTokens().isPresent() && params.maxTokens().get().equals(100L)
    ));
  }

  @Test
  void handle_withJsonMode_passesResponseFormatToClient() throws Exception {
    ChatRequest chatRequest = new ChatRequest(
        List.of(new ChatRequest.Message(ChatRequest.Role.user, "Hello")),
        "gpt-4",
        null,
        null,
        false,
        true,
        null,
        null,
        null
    );
    WebsocketRequest request = new WebsocketRequest(1L, "POST", "/v1/chat/completions", Optional.of(mapper.writeValueAsString(chatRequest)));

    ChatCompletion mockCompletion = mock(ChatCompletion.class);
    ChatCompletion.Choice mockChoice = mock(ChatCompletion.Choice.class);
    ChatCompletionMessage mockMessage = mock(ChatCompletionMessage.class);

    when(openAIClient.chat()).thenReturn(chatService);
    when(chatService.completions()).thenReturn(completionService);
    when(completionService.create(any(ChatCompletionCreateParams.class))).thenReturn(mockCompletion);
    when(mockCompletion.choices()).thenReturn(List.of(mockChoice));
    when(mockChoice.message()).thenReturn(mockMessage);
    when(mockMessage.content()).thenReturn(Optional.of("{}"));
    when(toolRegistry.getAllTools()).thenReturn(java.util.Map.of());

    handler.handle(request, streamRegistry);

    verify(completionService).create(argThat((ChatCompletionCreateParams params) ->
        params.responseFormat().isPresent()
    ));
  }

  @Test
  void handle_multipleMessageRoles_buildsCorrectParams() throws Exception {
    ChatRequest chatRequest = new ChatRequest(
        List.of(
            new ChatRequest.Message(ChatRequest.Role.system, "You are helpful"),
            new ChatRequest.Message(ChatRequest.Role.user, "Hello"),
            new ChatRequest.Message(ChatRequest.Role.assistant, "Hi there"),
            new ChatRequest.Message(ChatRequest.Role.user, "How are you?")
        ),
        "gpt-4",
        null,
        null,
        false,
        null,
        null,
        null,
        null
    );
    WebsocketRequest request = new WebsocketRequest(1L, "POST", "/v1/chat/completions", Optional.of(mapper.writeValueAsString(chatRequest)));

    ChatCompletion mockCompletion = mock(ChatCompletion.class);
    ChatCompletion.Choice mockChoice = mock(ChatCompletion.Choice.class);
    ChatCompletionMessage mockMessage = mock(ChatCompletionMessage.class);

    when(openAIClient.chat()).thenReturn(chatService);
    when(chatService.completions()).thenReturn(completionService);
    when(completionService.create(any(ChatCompletionCreateParams.class))).thenReturn(mockCompletion);
    when(mockCompletion.choices()).thenReturn(List.of(mockChoice));
    when(mockChoice.message()).thenReturn(mockMessage);
    when(mockMessage.content()).thenReturn(Optional.of("response"));
    when(toolRegistry.getAllTools()).thenReturn(java.util.Map.of());

    handler.handle(request, streamRegistry);

    verify(completionService).create(argThat((ChatCompletionCreateParams params) ->
        params.messages().size() == 4
    ));
  }

  @Test
  void handle_emptyChoices_returnsEmptyContent() throws Exception {
    ChatRequest chatRequest = new ChatRequest(
        List.of(new ChatRequest.Message(ChatRequest.Role.user, "Hello")),
        "gpt-4",
        null,
        null,
        false,
        null,
        null,
        null,
        null
    );
    WebsocketRequest request = new WebsocketRequest(1L, "POST", "/v1/chat/completions", Optional.of(mapper.writeValueAsString(chatRequest)));

    ChatCompletion mockCompletion = mock(ChatCompletion.class);
    ChatCompletion.Choice mockChoice = mock(ChatCompletion.Choice.class);
    ChatCompletionMessage mockMessage = mock(ChatCompletionMessage.class);

    when(openAIClient.chat()).thenReturn(chatService);
    when(chatService.completions()).thenReturn(completionService);
    when(completionService.create(any(ChatCompletionCreateParams.class))).thenReturn(mockCompletion);
    when(mockCompletion.choices()).thenReturn(List.of(mockChoice));
    when(mockChoice.message()).thenReturn(mockMessage);
    when(mockMessage.content()).thenReturn(Optional.empty());
    when(toolRegistry.getAllTools()).thenReturn(java.util.Map.of());

    WebsocketHandlerResponse response = handler.handle(request, streamRegistry);

    WebsocketHandlerResponse.SingleResponse singleResponse = (WebsocketHandlerResponse.SingleResponse) response;
    assertEquals("", singleResponse.body());
  }

  @Test
  void handle_developerRole_buildsCorrectParams() throws Exception {
    ChatRequest chatRequest = new ChatRequest(
        List.of(
            new ChatRequest.Message(ChatRequest.Role.developer, "You are a coding assistant"),
            new ChatRequest.Message(ChatRequest.Role.user, "Write hello world")
        ),
        "gpt-4",
        null,
        null,
        false,
        null,
        null,
        null,
        null
    );
    WebsocketRequest request = new WebsocketRequest(1L, "POST", "/v1/chat/completions", Optional.of(mapper.writeValueAsString(chatRequest)));

    ChatCompletion mockCompletion = mock(ChatCompletion.class);
    ChatCompletion.Choice mockChoice = mock(ChatCompletion.Choice.class);
    ChatCompletionMessage mockMessage = mock(ChatCompletionMessage.class);

    when(openAIClient.chat()).thenReturn(chatService);
    when(chatService.completions()).thenReturn(completionService);
    when(completionService.create(any(ChatCompletionCreateParams.class))).thenReturn(mockCompletion);
    when(mockCompletion.choices()).thenReturn(List.of(mockChoice));
    when(mockChoice.message()).thenReturn(mockMessage);
    when(mockMessage.content()).thenReturn(Optional.of("print('hello')"));
    when(toolRegistry.getAllTools()).thenReturn(Map.of());

    WebsocketHandlerResponse response = handler.handle(request, streamRegistry);

    assertInstanceOf(WebsocketHandlerResponse.SingleResponse.class, response);
    verify(completionService).create(argThat((ChatCompletionCreateParams params) ->
        params.messages().size() == 2
    ));
  }

  @Test
  void handle_toolCallMessage_buildsCorrectParams() throws Exception {
    ToolCallContent toolCallContent = new ToolCallContent("call_123", "web_search", "{\"query\":\"test\"}");

    ChatRequest chatRequest = new ChatRequest(
        List.of(
            new ChatRequest.Message(ChatRequest.Role.user, "Search for test"),
            new ChatRequest.Message(ChatRequest.Role.tool_call, mapper.writeValueAsString(toolCallContent))
        ),
        "gpt-4",
        null,
        null,
        false,
        null,
        null,
        null,
        null
    );
    WebsocketRequest request = new WebsocketRequest(1L, "POST", "/v1/chat/completions", Optional.of(mapper.writeValueAsString(chatRequest)));

    ChatCompletion mockCompletion = mock(ChatCompletion.class);
    ChatCompletion.Choice mockChoice = mock(ChatCompletion.Choice.class);
    ChatCompletionMessage mockMessage = mock(ChatCompletionMessage.class);

    when(openAIClient.chat()).thenReturn(chatService);
    when(chatService.completions()).thenReturn(completionService);
    when(completionService.create(any(ChatCompletionCreateParams.class))).thenReturn(mockCompletion);
    when(mockCompletion.choices()).thenReturn(List.of(mockChoice));
    when(mockChoice.message()).thenReturn(mockMessage);
    when(mockMessage.content()).thenReturn(Optional.of("Here are the results"));
    when(toolRegistry.getAllTools()).thenReturn(Map.of());

    WebsocketHandlerResponse response = handler.handle(request, streamRegistry);

    assertInstanceOf(WebsocketHandlerResponse.SingleResponse.class, response);
    verify(completionService).create(argThat((ChatCompletionCreateParams params) ->
        params.messages().size() == 2
    ));
  }

  @Test
  void handle_toolResponseMessage_buildsCorrectParams() throws Exception {
    ToolCallContent toolCallContent = new ToolCallContent("call_123", "web_search", "{\"query\":\"test\"}");
    ToolResponseContent toolResponseContent = new ToolResponseContent("call_123", "web_search", "Search results here");

    ChatRequest chatRequest = new ChatRequest(
        List.of(
            new ChatRequest.Message(ChatRequest.Role.user, "Search for test"),
            new ChatRequest.Message(ChatRequest.Role.tool_call, mapper.writeValueAsString(toolCallContent)),
            new ChatRequest.Message(ChatRequest.Role.tool_response, mapper.writeValueAsString(toolResponseContent))
        ),
        "gpt-4",
        null,
        null,
        false,
        null,
        null,
        null,
        null
    );
    WebsocketRequest request = new WebsocketRequest(1L, "POST", "/v1/chat/completions", Optional.of(mapper.writeValueAsString(chatRequest)));

    ChatCompletion mockCompletion = mock(ChatCompletion.class);
    ChatCompletion.Choice mockChoice = mock(ChatCompletion.Choice.class);
    ChatCompletionMessage mockMessage = mock(ChatCompletionMessage.class);

    when(openAIClient.chat()).thenReturn(chatService);
    when(chatService.completions()).thenReturn(completionService);
    when(completionService.create(any(ChatCompletionCreateParams.class))).thenReturn(mockCompletion);
    when(mockCompletion.choices()).thenReturn(List.of(mockChoice));
    when(mockChoice.message()).thenReturn(mockMessage);
    when(mockMessage.content()).thenReturn(Optional.of("Based on the search results..."));
    when(toolRegistry.getAllTools()).thenReturn(Map.of());

    WebsocketHandlerResponse response = handler.handle(request, streamRegistry);

    assertInstanceOf(WebsocketHandlerResponse.SingleResponse.class, response);
    verify(completionService).create(argThat((ChatCompletionCreateParams params) ->
        params.messages().size() == 3
    ));
  }

  @Test
  void handle_invalidToolCallContent_throwsBadRequest() throws Exception {
    ChatRequest chatRequest = new ChatRequest(
        List.of(
            new ChatRequest.Message(ChatRequest.Role.user, "Search for test"),
            new ChatRequest.Message(ChatRequest.Role.tool_call, "not valid json")
        ),
        "gpt-4",
        null,
        null,
        false,
        null,
        null,
        null,
        null
    );
    WebsocketRequest request = new WebsocketRequest(1L, "POST", "/v1/chat/completions", Optional.of(mapper.writeValueAsString(chatRequest)));

    WebApplicationException exception = assertThrows(WebApplicationException.class, () -> handler.handle(request, streamRegistry));
    assertEquals(400, exception.getResponse().getStatus());
  }

  @Test
  void handle_invalidToolResponseContent_throwsBadRequest() throws Exception {
    ToolCallContent toolCallContent = new ToolCallContent("call_123", "web_search", "{\"query\":\"test\"}");

    ChatRequest chatRequest = new ChatRequest(
        List.of(
            new ChatRequest.Message(ChatRequest.Role.user, "Search for test"),
            new ChatRequest.Message(ChatRequest.Role.tool_call, mapper.writeValueAsString(toolCallContent)),
            new ChatRequest.Message(ChatRequest.Role.tool_response, "not valid json")
        ),
        "gpt-4",
        null,
        null,
        false,
        null,
        null,
        null,
        null
    );
    WebsocketRequest request = new WebsocketRequest(1L, "POST", "/v1/chat/completions", Optional.of(mapper.writeValueAsString(chatRequest)));

    WebApplicationException exception = assertThrows(WebApplicationException.class, () -> handler.handle(request, streamRegistry));
    assertEquals(400, exception.getResponse().getStatus());
  }

  @Test
  @SuppressWarnings("unchecked")
  void handle_streamingWithToolCall_executesToolAndContinues() throws Exception {
    ChatRequest chatRequest = new ChatRequest(
        List.of(new ChatRequest.Message(ChatRequest.Role.user, "Search for cats")),
        "gpt-4",
        null,
        null,
        true,
        null,
        null,
        null,
        null
    );
    WebsocketRequest request = new WebsocketRequest(1L, "POST", "/v1/chat/completions", Optional.of(mapper.writeValueAsString(chatRequest)));

    // First response: tool call
    ChatCompletionChunk toolCallChunk = mock(ChatCompletionChunk.class);
    ChatCompletionChunk.Choice toolCallChoice = mock(ChatCompletionChunk.Choice.class);
    ChatCompletionChunk.Choice.Delta toolCallDelta = mock(ChatCompletionChunk.Choice.Delta.class);
    ChatCompletionChunk.Choice.Delta.ToolCall toolCall = mock(ChatCompletionChunk.Choice.Delta.ToolCall.class);
    ChatCompletionChunk.Choice.Delta.ToolCall.Function toolCallFunction = mock(ChatCompletionChunk.Choice.Delta.ToolCall.Function.class);

    when(toolCallChunk.choices()).thenReturn(List.of(toolCallChoice));
    when(toolCallChoice.delta()).thenReturn(toolCallDelta);
    when(toolCallChoice.finishReason()).thenReturn(Optional.of(ChatCompletionChunk.Choice.FinishReason.TOOL_CALLS));
    when(toolCallDelta.toolCalls()).thenReturn(Optional.of(List.of(toolCall)));
    when(toolCall.index()).thenReturn(0L);
    when(toolCall.id()).thenReturn(Optional.of("call_abc123"));
    when(toolCall.function()).thenReturn(Optional.of(toolCallFunction));
    when(toolCallFunction.name()).thenReturn(Optional.of("web_search"));
    when(toolCallFunction.arguments()).thenReturn(Optional.of("{\"query\":\"cats\"}"));

    StreamResponse<ChatCompletionChunk> firstStreamResponse = mock(StreamResponse.class);
    when(firstStreamResponse.stream()).thenReturn(Stream.of(toolCallChunk));

    // Second response: final answer
    ChatCompletionChunk answerChunk = mock(ChatCompletionChunk.class);
    ChatCompletionChunk.Choice answerChoice = mock(ChatCompletionChunk.Choice.class);
    ChatCompletionChunk.Choice.Delta answerDelta = mock(ChatCompletionChunk.Choice.Delta.class);

    when(answerChunk.choices()).thenReturn(List.of(answerChoice));
    when(answerChoice.delta()).thenReturn(answerDelta);
    when(answerChoice.finishReason()).thenReturn(Optional.of(ChatCompletionChunk.Choice.FinishReason.STOP));
    when(answerDelta.content()).thenReturn(Optional.of("Cats are great pets!"));
    when(answerDelta.toolCalls()).thenReturn(Optional.empty());

    StreamResponse<ChatCompletionChunk> secondStreamResponse = mock(StreamResponse.class);
    when(secondStreamResponse.stream()).thenReturn(Stream.of(answerChunk));

    when(openAIClient.chat()).thenReturn(chatService);
    when(chatService.completions()).thenReturn(completionService);
    when(completionService.createStreaming(any(ChatCompletionCreateParams.class)))
        .thenReturn(firstStreamResponse)
        .thenReturn(secondStreamResponse);

    // Mock tool
    Tool mockTool = mock(Tool.class);
    FunctionDefinition funcDef = FunctionDefinition.builder().name("web_search").build();
    when(mockTool.getFunctionDefinition()).thenReturn(funcDef);
    when(mockTool.execute(anyString(), anyString(), any(OutputStream.class))).thenReturn("Search results for cats");
    when(mockTool.getClientResult(anyString())).thenReturn("Search results for cats");

    when(toolRegistry.getAllTools()).thenReturn(Map.of("web_search", mockTool));
    when(toolRegistry.getTool("web_search")).thenReturn(Optional.of(mockTool));

    WebsocketHandlerResponse response = handler.handle(request, streamRegistry);
    WebsocketHandlerResponse.StreamingResponse streamingResponse = (WebsocketHandlerResponse.StreamingResponse) response;

    ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
    streamingResponse.stream().write(outputStream);

    String output = outputStream.toString();

    // Verify tool was called
    verify(mockTool).execute(eq("{\"query\":\"cats\"}"), eq("call_abc123"), any(OutputStream.class));

    // Verify output contains tool call, tool response, content, and completion
    assertTrue(output.contains("\"type\":\"tool_call\""));
    assertTrue(output.contains("\"tool_name\":\"web_search\""));
    assertTrue(output.contains("\"type\":\"tool_response\""));
    assertTrue(output.contains("\"type\":\"token\""));
    assertTrue(output.contains("Cats are great pets!"));
    assertTrue(output.contains("\"type\":\"completion\""));
  }

  @Test
  @SuppressWarnings("unchecked")
  void handle_streamingWithUnknownTool_logsWarningAndContinues() throws Exception {
    ChatRequest chatRequest = new ChatRequest(
        List.of(new ChatRequest.Message(ChatRequest.Role.user, "Do something")),
        "gpt-4",
        null,
        null,
        true,
        null,
        null,
        null,
        null
    );
    WebsocketRequest request = new WebsocketRequest(1L, "POST", "/v1/chat/completions", Optional.of(mapper.writeValueAsString(chatRequest)));

    // Tool call for unknown tool
    ChatCompletionChunk toolCallChunk = mock(ChatCompletionChunk.class);
    ChatCompletionChunk.Choice toolCallChoice = mock(ChatCompletionChunk.Choice.class);
    ChatCompletionChunk.Choice.Delta toolCallDelta = mock(ChatCompletionChunk.Choice.Delta.class);
    ChatCompletionChunk.Choice.Delta.ToolCall toolCall = mock(ChatCompletionChunk.Choice.Delta.ToolCall.class);
    ChatCompletionChunk.Choice.Delta.ToolCall.Function toolCallFunction = mock(ChatCompletionChunk.Choice.Delta.ToolCall.Function.class);

    when(toolCallChunk.choices()).thenReturn(List.of(toolCallChoice));
    when(toolCallChoice.delta()).thenReturn(toolCallDelta);
    when(toolCallChoice.finishReason()).thenReturn(Optional.of(ChatCompletionChunk.Choice.FinishReason.TOOL_CALLS));
    when(toolCallDelta.toolCalls()).thenReturn(Optional.of(List.of(toolCall)));
    when(toolCall.index()).thenReturn(0L);
    when(toolCall.id()).thenReturn(Optional.of("call_unknown"));
    when(toolCall.function()).thenReturn(Optional.of(toolCallFunction));
    when(toolCallFunction.name()).thenReturn(Optional.of("unknown_tool"));
    when(toolCallFunction.arguments()).thenReturn(Optional.of("{}"));

    StreamResponse<ChatCompletionChunk> firstStreamResponse = mock(StreamResponse.class);
    when(firstStreamResponse.stream()).thenReturn(Stream.of(toolCallChunk));

    // Second response after unknown tool (would normally continue but let's end)
    ChatCompletionChunk answerChunk = mock(ChatCompletionChunk.class);
    ChatCompletionChunk.Choice answerChoice = mock(ChatCompletionChunk.Choice.class);
    ChatCompletionChunk.Choice.Delta answerDelta = mock(ChatCompletionChunk.Choice.Delta.class);

    when(answerChunk.choices()).thenReturn(List.of(answerChoice));
    when(answerChoice.delta()).thenReturn(answerDelta);
    when(answerChoice.finishReason()).thenReturn(Optional.of(ChatCompletionChunk.Choice.FinishReason.STOP));
    when(answerDelta.content()).thenReturn(Optional.of("Done"));
    when(answerDelta.toolCalls()).thenReturn(Optional.empty());

    StreamResponse<ChatCompletionChunk> secondStreamResponse = mock(StreamResponse.class);
    when(secondStreamResponse.stream()).thenReturn(Stream.of(answerChunk));

    when(openAIClient.chat()).thenReturn(chatService);
    when(chatService.completions()).thenReturn(completionService);
    when(completionService.createStreaming(any(ChatCompletionCreateParams.class)))
        .thenReturn(firstStreamResponse)
        .thenReturn(secondStreamResponse);

    when(toolRegistry.getAllTools()).thenReturn(Map.of());
    when(toolRegistry.getTool("unknown_tool")).thenReturn(Optional.empty());

    WebsocketHandlerResponse response = handler.handle(request, streamRegistry);
    WebsocketHandlerResponse.StreamingResponse streamingResponse = (WebsocketHandlerResponse.StreamingResponse) response;

    ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
    streamingResponse.stream().write(outputStream);

    String output = outputStream.toString();

    // Should still complete, even with unknown tool
    assertTrue(output.contains("\"type\":\"tool_call\""));
    assertTrue(output.contains("\"type\":\"completion\""));
  }

  @Test
  @SuppressWarnings("unchecked")
  void handle_emptyChunkChoices_skipsProcessing() throws Exception {
    ChatRequest chatRequest = new ChatRequest(
        List.of(new ChatRequest.Message(ChatRequest.Role.user, "Hello")),
        "gpt-4",
        null,
        null,
        true,
        null,
        null,
        null,
        null
    );
    WebsocketRequest request = new WebsocketRequest(1L, "POST", "/v1/chat/completions", Optional.of(mapper.writeValueAsString(chatRequest)));

    // Empty choices chunk (should be skipped)
    ChatCompletionChunk emptyChunk = mock(ChatCompletionChunk.class);
    when(emptyChunk.choices()).thenReturn(List.of());

    // Normal content chunk
    ChatCompletionChunk contentChunk = mock(ChatCompletionChunk.class);
    ChatCompletionChunk.Choice contentChoice = mock(ChatCompletionChunk.Choice.class);
    ChatCompletionChunk.Choice.Delta contentDelta = mock(ChatCompletionChunk.Choice.Delta.class);

    when(contentChunk.choices()).thenReturn(List.of(contentChoice));
    when(contentChoice.delta()).thenReturn(contentDelta);
    when(contentChoice.finishReason()).thenReturn(Optional.of(ChatCompletionChunk.Choice.FinishReason.STOP));
    when(contentDelta.content()).thenReturn(Optional.of("Hello!"));
    when(contentDelta.toolCalls()).thenReturn(Optional.empty());

    StreamResponse<ChatCompletionChunk> streamResponse = mock(StreamResponse.class);
    when(streamResponse.stream()).thenReturn(Stream.of(emptyChunk, contentChunk));

    when(openAIClient.chat()).thenReturn(chatService);
    when(chatService.completions()).thenReturn(completionService);
    when(completionService.createStreaming(any(ChatCompletionCreateParams.class))).thenReturn(streamResponse);
    when(toolRegistry.getAllTools()).thenReturn(Map.of());

    WebsocketHandlerResponse response = handler.handle(request, streamRegistry);
    WebsocketHandlerResponse.StreamingResponse streamingResponse = (WebsocketHandlerResponse.StreamingResponse) response;

    ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
    streamingResponse.stream().write(outputStream);

    String output = outputStream.toString();
    assertTrue(output.contains("\"content\":\"Hello!\""));
    assertTrue(output.contains("\"type\":\"completion\""));
  }

  @Test
  @SuppressWarnings("unchecked")
  void handle_emptyContentInChunk_skipsOutput() throws Exception {
    ChatRequest chatRequest = new ChatRequest(
        List.of(new ChatRequest.Message(ChatRequest.Role.user, "Hello")),
        "gpt-4",
        null,
        null,
        true,
        null,
        null,
        null,
        null
    );
    WebsocketRequest request = new WebsocketRequest(1L, "POST", "/v1/chat/completions", Optional.of(mapper.writeValueAsString(chatRequest)));

    // Chunk with empty content
    ChatCompletionChunk emptyContentChunk = mock(ChatCompletionChunk.class);
    ChatCompletionChunk.Choice emptyContentChoice = mock(ChatCompletionChunk.Choice.class);
    ChatCompletionChunk.Choice.Delta emptyContentDelta = mock(ChatCompletionChunk.Choice.Delta.class);

    when(emptyContentChunk.choices()).thenReturn(List.of(emptyContentChoice));
    when(emptyContentChoice.delta()).thenReturn(emptyContentDelta);
    when(emptyContentChoice.finishReason()).thenReturn(Optional.empty());
    when(emptyContentDelta.content()).thenReturn(Optional.of(""));
    when(emptyContentDelta.toolCalls()).thenReturn(Optional.empty());

    // Normal content chunk
    ChatCompletionChunk contentChunk = mock(ChatCompletionChunk.class);
    ChatCompletionChunk.Choice contentChoice = mock(ChatCompletionChunk.Choice.class);
    ChatCompletionChunk.Choice.Delta contentDelta = mock(ChatCompletionChunk.Choice.Delta.class);

    when(contentChunk.choices()).thenReturn(List.of(contentChoice));
    when(contentChoice.delta()).thenReturn(contentDelta);
    when(contentChoice.finishReason()).thenReturn(Optional.of(ChatCompletionChunk.Choice.FinishReason.STOP));
    when(contentDelta.content()).thenReturn(Optional.of("Hi"));
    when(contentDelta.toolCalls()).thenReturn(Optional.empty());

    StreamResponse<ChatCompletionChunk> streamResponse = mock(StreamResponse.class);
    when(streamResponse.stream()).thenReturn(Stream.of(emptyContentChunk, contentChunk));

    when(openAIClient.chat()).thenReturn(chatService);
    when(chatService.completions()).thenReturn(completionService);
    when(completionService.createStreaming(any(ChatCompletionCreateParams.class))).thenReturn(streamResponse);
    when(toolRegistry.getAllTools()).thenReturn(Map.of());

    WebsocketHandlerResponse response = handler.handle(request, streamRegistry);
    WebsocketHandlerResponse.StreamingResponse streamingResponse = (WebsocketHandlerResponse.StreamingResponse) response;

    ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
    streamingResponse.stream().write(outputStream);

    String output = outputStream.toString();
    // Should only have "Hi", not empty content
    assertFalse(output.contains("\"content\":\"\""));
    assertTrue(output.contains("\"content\":\"Hi\""));
  }

  @Test
  @SuppressWarnings("unchecked")
  void handle_streamingMaxIterations_removesToolsOnLastIteration() throws Exception {
    // Set max iterations to 2
    when(config.getMaxToolIterations()).thenReturn(2);

    ChatRequest chatRequest = new ChatRequest(
        List.of(new ChatRequest.Message(ChatRequest.Role.user, "Search for cats")),
        "gpt-4",
        null,
        null,
        true,
        null,
        null,
        null,
        null
    );
    WebsocketRequest request = new WebsocketRequest(1L, "POST", "/v1/chat/completions", Optional.of(mapper.writeValueAsString(chatRequest)));

    // First response: tool call
    ChatCompletionChunk toolCallChunk1 = mock(ChatCompletionChunk.class);
    ChatCompletionChunk.Choice toolCallChoice1 = mock(ChatCompletionChunk.Choice.class);
    ChatCompletionChunk.Choice.Delta toolCallDelta1 = mock(ChatCompletionChunk.Choice.Delta.class);
    ChatCompletionChunk.Choice.Delta.ToolCall toolCall1 = mock(ChatCompletionChunk.Choice.Delta.ToolCall.class);
    ChatCompletionChunk.Choice.Delta.ToolCall.Function toolCallFunction1 = mock(ChatCompletionChunk.Choice.Delta.ToolCall.Function.class);

    when(toolCallChunk1.choices()).thenReturn(List.of(toolCallChoice1));
    when(toolCallChoice1.delta()).thenReturn(toolCallDelta1);
    when(toolCallChoice1.finishReason()).thenReturn(Optional.of(ChatCompletionChunk.Choice.FinishReason.TOOL_CALLS));
    when(toolCallDelta1.toolCalls()).thenReturn(Optional.of(List.of(toolCall1)));
    when(toolCall1.index()).thenReturn(0L);
    when(toolCall1.id()).thenReturn(Optional.of("call_1"));
    when(toolCall1.function()).thenReturn(Optional.of(toolCallFunction1));
    when(toolCallFunction1.name()).thenReturn(Optional.of("web_search"));
    when(toolCallFunction1.arguments()).thenReturn(Optional.of("{\"query\":\"cats\"}"));

    StreamResponse<ChatCompletionChunk> firstStreamResponse = mock(StreamResponse.class);
    when(firstStreamResponse.stream()).thenReturn(Stream.of(toolCallChunk1));

    // Second response (last iteration, no tools): final answer
    ChatCompletionChunk answerChunk = mock(ChatCompletionChunk.class);
    ChatCompletionChunk.Choice answerChoice = mock(ChatCompletionChunk.Choice.class);
    ChatCompletionChunk.Choice.Delta answerDelta = mock(ChatCompletionChunk.Choice.Delta.class);

    when(answerChunk.choices()).thenReturn(List.of(answerChoice));
    when(answerChoice.delta()).thenReturn(answerDelta);
    when(answerChoice.finishReason()).thenReturn(Optional.of(ChatCompletionChunk.Choice.FinishReason.STOP));
    when(answerDelta.content()).thenReturn(Optional.of("Here's what I found about cats."));
    when(answerDelta.toolCalls()).thenReturn(Optional.empty());

    StreamResponse<ChatCompletionChunk> secondStreamResponse = mock(StreamResponse.class);
    when(secondStreamResponse.stream()).thenReturn(Stream.of(answerChunk));

    when(openAIClient.chat()).thenReturn(chatService);
    when(chatService.completions()).thenReturn(completionService);
    when(completionService.createStreaming(any(ChatCompletionCreateParams.class)))
        .thenReturn(firstStreamResponse)
        .thenReturn(secondStreamResponse);

    // Mock tool
    Tool mockTool = mock(Tool.class);
    FunctionDefinition funcDef = FunctionDefinition.builder().name("web_search").build();
    when(mockTool.getFunctionDefinition()).thenReturn(funcDef);
    when(mockTool.execute(anyString(), anyString(), any(OutputStream.class))).thenReturn("Search results");
    when(mockTool.getClientResult(anyString())).thenReturn("Search results");

    when(toolRegistry.getAllTools()).thenReturn(Map.of("web_search", mockTool));
    when(toolRegistry.getTool("web_search")).thenReturn(Optional.of(mockTool));

    WebsocketHandlerResponse response = handler.handle(request, streamRegistry);
    WebsocketHandlerResponse.StreamingResponse streamingResponse = (WebsocketHandlerResponse.StreamingResponse) response;

    ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
    streamingResponse.stream().write(outputStream);

    // Capture the params from both calls
    var paramsCaptor = org.mockito.ArgumentCaptor.forClass(ChatCompletionCreateParams.class);
    verify(completionService, times(2)).createStreaming(paramsCaptor.capture());

    List<ChatCompletionCreateParams> allParams = paramsCaptor.getAllValues();

    // First call should have tools
    assertTrue(allParams.get(0).tools().isPresent());
    assertEquals(1, allParams.get(0).tools().get().size());

    // Second call (last iteration) should NOT have tools
    assertTrue(allParams.get(1).tools().isEmpty() || allParams.get(1).tools().get().isEmpty());

    // Second call should have the wrap-up instruction message
    List<com.openai.models.chat.completions.ChatCompletionMessageParam> secondCallMessages = allParams.get(1).messages();
    boolean hasWrapUpMessage = secondCallMessages.stream()
        .anyMatch(msg -> msg.toString().contains("You have used all available tool calls"));
    assertTrue(hasWrapUpMessage, "Second call should include wrap-up instruction message");

    // Verify output contains the final response
    String output = outputStream.toString();
    assertTrue(output.contains("Here's what I found about cats."));
    assertTrue(output.contains("\"type\":\"completion\""));
  }

  @Test
  @SuppressWarnings("unchecked")
  void handle_streamingNormalCompletion_noWrapUpMessage() throws Exception {
    // With default max iterations (10), a single tool call followed by completion
    // should NOT include the wrap-up message
    ChatRequest chatRequest = new ChatRequest(
        List.of(new ChatRequest.Message(ChatRequest.Role.user, "Search for dogs")),
        "gpt-4",
        null,
        null,
        true,
        null,
        null,
        null,
        null
    );
    WebsocketRequest request = new WebsocketRequest(1L, "POST", "/v1/chat/completions", Optional.of(mapper.writeValueAsString(chatRequest)));

    // First response: tool call
    ChatCompletionChunk toolCallChunk = mock(ChatCompletionChunk.class);
    ChatCompletionChunk.Choice toolCallChoice = mock(ChatCompletionChunk.Choice.class);
    ChatCompletionChunk.Choice.Delta toolCallDelta = mock(ChatCompletionChunk.Choice.Delta.class);
    ChatCompletionChunk.Choice.Delta.ToolCall toolCall = mock(ChatCompletionChunk.Choice.Delta.ToolCall.class);
    ChatCompletionChunk.Choice.Delta.ToolCall.Function toolCallFunction = mock(ChatCompletionChunk.Choice.Delta.ToolCall.Function.class);

    when(toolCallChunk.choices()).thenReturn(List.of(toolCallChoice));
    when(toolCallChoice.delta()).thenReturn(toolCallDelta);
    when(toolCallChoice.finishReason()).thenReturn(Optional.of(ChatCompletionChunk.Choice.FinishReason.TOOL_CALLS));
    when(toolCallDelta.toolCalls()).thenReturn(Optional.of(List.of(toolCall)));
    when(toolCall.index()).thenReturn(0L);
    when(toolCall.id()).thenReturn(Optional.of("call_1"));
    when(toolCall.function()).thenReturn(Optional.of(toolCallFunction));
    when(toolCallFunction.name()).thenReturn(Optional.of("web_search"));
    when(toolCallFunction.arguments()).thenReturn(Optional.of("{\"query\":\"dogs\"}"));

    StreamResponse<ChatCompletionChunk> firstStreamResponse = mock(StreamResponse.class);
    when(firstStreamResponse.stream()).thenReturn(Stream.of(toolCallChunk));

    // Second response: final answer (no tool calls)
    ChatCompletionChunk answerChunk = mock(ChatCompletionChunk.class);
    ChatCompletionChunk.Choice answerChoice = mock(ChatCompletionChunk.Choice.class);
    ChatCompletionChunk.Choice.Delta answerDelta = mock(ChatCompletionChunk.Choice.Delta.class);

    when(answerChunk.choices()).thenReturn(List.of(answerChoice));
    when(answerChoice.delta()).thenReturn(answerDelta);
    when(answerChoice.finishReason()).thenReturn(Optional.of(ChatCompletionChunk.Choice.FinishReason.STOP));
    when(answerDelta.content()).thenReturn(Optional.of("Dogs are loyal pets."));
    when(answerDelta.toolCalls()).thenReturn(Optional.empty());

    StreamResponse<ChatCompletionChunk> secondStreamResponse = mock(StreamResponse.class);
    when(secondStreamResponse.stream()).thenReturn(Stream.of(answerChunk));

    when(openAIClient.chat()).thenReturn(chatService);
    when(chatService.completions()).thenReturn(completionService);
    when(completionService.createStreaming(any(ChatCompletionCreateParams.class)))
        .thenReturn(firstStreamResponse)
        .thenReturn(secondStreamResponse);

    // Mock tool
    Tool mockTool = mock(Tool.class);
    FunctionDefinition funcDef = FunctionDefinition.builder().name("web_search").build();
    when(mockTool.getFunctionDefinition()).thenReturn(funcDef);
    when(mockTool.execute(anyString(), anyString(), any(OutputStream.class))).thenReturn("Search results");
    when(mockTool.getClientResult(anyString())).thenReturn("Search results");

    when(toolRegistry.getAllTools()).thenReturn(Map.of("web_search", mockTool));
    when(toolRegistry.getTool("web_search")).thenReturn(Optional.of(mockTool));

    WebsocketHandlerResponse response = handler.handle(request, streamRegistry);
    WebsocketHandlerResponse.StreamingResponse streamingResponse = (WebsocketHandlerResponse.StreamingResponse) response;

    ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
    streamingResponse.stream().write(outputStream);

    // Capture all params
    var paramsCaptor = org.mockito.ArgumentCaptor.forClass(ChatCompletionCreateParams.class);
    verify(completionService, times(2)).createStreaming(paramsCaptor.capture());

    List<ChatCompletionCreateParams> allParams = paramsCaptor.getAllValues();

    // Both calls should have tools (neither is the last iteration)
    assertTrue(allParams.get(0).tools().isPresent());
    assertTrue(allParams.get(1).tools().isPresent());

    // Neither call should have the wrap-up message
    for (ChatCompletionCreateParams params : allParams) {
      boolean hasWrapUpMessage = params.messages().stream()
          .anyMatch(msg -> msg.toString().contains("You have used all available tool calls"));
      assertFalse(hasWrapUpMessage, "Normal completion should not include wrap-up message");
    }
  }

  @Test
  @SuppressWarnings("unchecked")
  void handle_streamingMaxIterationsOne_immediateWrapUp() throws Exception {
    // Edge case: maxIterations = 1 means the first call is also the last
    when(config.getMaxToolIterations()).thenReturn(1);

    ChatRequest chatRequest = new ChatRequest(
        List.of(new ChatRequest.Message(ChatRequest.Role.user, "Hello")),
        "gpt-4",
        null,
        null,
        true,
        null,
        null,
        null,
        null
    );
    WebsocketRequest request = new WebsocketRequest(1L, "POST", "/v1/chat/completions", Optional.of(mapper.writeValueAsString(chatRequest)));

    // Response: final answer (no tools available)
    ChatCompletionChunk answerChunk = mock(ChatCompletionChunk.class);
    ChatCompletionChunk.Choice answerChoice = mock(ChatCompletionChunk.Choice.class);
    ChatCompletionChunk.Choice.Delta answerDelta = mock(ChatCompletionChunk.Choice.Delta.class);

    when(answerChunk.choices()).thenReturn(List.of(answerChoice));
    when(answerChoice.delta()).thenReturn(answerDelta);
    when(answerChoice.finishReason()).thenReturn(Optional.of(ChatCompletionChunk.Choice.FinishReason.STOP));
    when(answerDelta.content()).thenReturn(Optional.of("Hello there!"));
    when(answerDelta.toolCalls()).thenReturn(Optional.empty());

    StreamResponse<ChatCompletionChunk> streamResponse = mock(StreamResponse.class);
    when(streamResponse.stream()).thenReturn(Stream.of(answerChunk));

    when(openAIClient.chat()).thenReturn(chatService);
    when(chatService.completions()).thenReturn(completionService);
    when(completionService.createStreaming(any(ChatCompletionCreateParams.class)))
        .thenReturn(streamResponse);

    lenient().when(toolRegistry.getAllTools()).thenReturn(Map.of());

    WebsocketHandlerResponse response = handler.handle(request, streamRegistry);
    WebsocketHandlerResponse.StreamingResponse streamingResponse = (WebsocketHandlerResponse.StreamingResponse) response;

    ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
    streamingResponse.stream().write(outputStream);

    // Capture params
    var paramsCaptor = org.mockito.ArgumentCaptor.forClass(ChatCompletionCreateParams.class);
    verify(completionService, times(1)).createStreaming(paramsCaptor.capture());

    ChatCompletionCreateParams params = paramsCaptor.getValue();

    // Should NOT have tools (it's the last/only iteration)
    assertTrue(params.tools().isEmpty() || params.tools().get().isEmpty());

    // Should have wrap-up message
    boolean hasWrapUpMessage = params.messages().stream()
        .anyMatch(msg -> msg.toString().contains("You have used all available tool calls"));
    assertTrue(hasWrapUpMessage, "maxIterations=1 should include wrap-up message on first call");

    // Output should still work
    String output = outputStream.toString();
    assertTrue(output.contains("Hello there!"));
    assertTrue(output.contains("\"type\":\"completion\""));
  }

  @Test
  void handle_withRegisteredTools_addsToolsToParams() throws Exception {
    ChatRequest chatRequest = new ChatRequest(
        List.of(new ChatRequest.Message(ChatRequest.Role.user, "Hello")),
        "gpt-4",
        null,
        null,
        false,
        null,
        null,
        null,
        null
    );
    WebsocketRequest request = new WebsocketRequest(1L, "POST", "/v1/chat/completions", Optional.of(mapper.writeValueAsString(chatRequest)));

    ChatCompletion mockCompletion = mock(ChatCompletion.class);
    ChatCompletion.Choice mockChoice = mock(ChatCompletion.Choice.class);
    ChatCompletionMessage mockMessage = mock(ChatCompletionMessage.class);

    when(openAIClient.chat()).thenReturn(chatService);
    when(chatService.completions()).thenReturn(completionService);
    when(completionService.create(any(ChatCompletionCreateParams.class))).thenReturn(mockCompletion);
    when(mockCompletion.choices()).thenReturn(List.of(mockChoice));
    when(mockChoice.message()).thenReturn(mockMessage);
    when(mockMessage.content()).thenReturn(Optional.of("response"));

    // Mock two tools
    Tool tool1 = mock(Tool.class);
    Tool tool2 = mock(Tool.class);
    FunctionDefinition funcDef1 = FunctionDefinition.builder().name("tool1").build();
    FunctionDefinition funcDef2 = FunctionDefinition.builder().name("tool2").build();
    when(tool1.getFunctionDefinition()).thenReturn(funcDef1);
    when(tool2.getFunctionDefinition()).thenReturn(funcDef2);

    when(toolRegistry.getAllTools()).thenReturn(Map.of("tool1", tool1, "tool2", tool2));

    handler.handle(request, streamRegistry);

    verify(completionService).create(argThat((ChatCompletionCreateParams params) ->
        params.tools().isPresent() && params.tools().get().size() == 2
    ));
  }
}
