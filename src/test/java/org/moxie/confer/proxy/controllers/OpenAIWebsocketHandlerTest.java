package org.moxie.confer.proxy.controllers;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.client.OpenAIClient;
import com.openai.core.http.StreamResponse;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionChunk;
import com.openai.models.chat.completions.ChatCompletionContentPart;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.chat.completions.ChatCompletionMessage;
import com.openai.models.chat.completions.ChatCompletionMessageParam;
import com.openai.models.chat.completions.ChatCompletionUserMessageParam;
import com.openai.models.completions.CompletionUsage;
import com.openai.services.blocking.ChatService;
import com.openai.services.blocking.chat.ChatCompletionService;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import jakarta.ws.rs.WebApplicationException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.moxie.confer.proxy.attachments.AttachmentReference;
import org.moxie.confer.proxy.websocket.WebsocketConnectionContext;
import org.moxie.confer.proxy.crypto.ImageToken;
import org.moxie.confer.proxy.documents.DocumentToolSession;
import org.moxie.confer.proxy.documents.DocumentToolSessionFactory;
import org.moxie.confer.proxy.documents.InvalidDocumentManifestException;
import org.moxie.confer.proxy.entities.ChatRequest;
import org.moxie.confer.proxy.entities.DocumentReference;
import org.moxie.confer.proxy.entities.WebsocketRequest;
import org.moxie.confer.proxy.config.Config;
import org.moxie.confer.proxy.images.ImageReference;
import org.moxie.confer.proxy.presenters.OpenAIMessagePresenter;
import org.moxie.confer.proxy.tools.Tool;
import org.moxie.confer.proxy.tools.ToolExecutionContext;
import org.moxie.confer.proxy.tools.ToolRequirement;
import org.moxie.confer.proxy.tools.ToolResult;
import org.moxie.confer.proxy.tools.registry.RequestToolSet;
import org.moxie.confer.proxy.tools.registry.ToolEligibility;
import org.moxie.confer.proxy.tools.registry.ToolRegistry;
import org.moxie.confer.proxy.websocket.WebsocketHandlerResponse;
import org.moxie.confer.proxy.workers.WorkerClient;
import org.moxie.confer.proxy.workers.WorkerWorkspace;

import com.openai.models.FunctionDefinition;
import org.moxie.confer.proxy.entities.ToolCallContent;
import org.moxie.confer.proxy.entities.ToolResponseContent;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class OpenAIWebsocketHandlerTest {

  private static ValidatorFactory validatorFactory;

  @Mock
  private OpenAIClient openAIClient;

  @Mock
  private ChatService chatService;

  @Mock
  private ChatCompletionService completionService;

  @Mock
  private ToolRegistry toolRegistry;

  @Mock
  private RequestToolSet requestTools;

  private OpenAIMessagePresenter messagePresenter;

  @Mock
  private DocumentToolSessionFactory documentToolSessions;

  @Mock
  private DocumentToolSession emptyDocumentSession;

  @Mock
  private Config config;

  @Mock
  private WebsocketConnectionContext requestContext;

  @Mock
  private WorkerClient workerClient;

  @Mock
  private WorkerWorkspace workerWorkspace;

  private ObjectMapper mapper;
  private OpenAIWebsocketHandler handler;

  @BeforeAll
  static void createValidatorFactory() {
    validatorFactory = Validation.buildDefaultValidatorFactory();
  }

  @AfterAll
  static void closeValidatorFactory() {
    validatorFactory.close();
  }

  private static ChatCompletionChunk mockChunk() {
    ChatCompletionChunk chunk = mock(ChatCompletionChunk.class);
    lenient().when(chunk.usage()).thenReturn(Optional.empty());
    return chunk;
  }

  private static void writeStreamingResponse(
      WebsocketHandlerResponse.StreamingResponse response,
      OutputStream output)
    throws IOException
  {
    try (response) {
      response.writeTo(output);
    }
  }

  private void useServerTools(Map<String, Tool> tools) {
    lenient().when(requestTools.values()).thenReturn(List.copyOf(tools.values()));
    lenient().when(requestTools.contains(anyString()))
        .thenAnswer(invocation -> tools.containsKey(invocation.getArgument(0)));
    lenient().when(requestTools.find(anyString()))
        .thenAnswer(invocation -> Optional.ofNullable(tools.get(invocation.getArgument(0))));
  }

  @BeforeEach
  void setUp() throws InvalidDocumentManifestException {
    mapper = new ObjectMapper();
    lenient().when(config.getMaxToolIterations()).thenReturn(10);
    lenient().when(config.getMaxContextTokens()).thenReturn(262144);
    lenient().when(config.getVllmServedModelName()).thenReturn("test-model");
    lenient().when(toolRegistry.forRequest(any(ToolEligibility.class)))
        .thenReturn(requestTools);
    lenient().when(documentToolSessions.open(any())).thenReturn(emptyDocumentSession);
    lenient().when(requestContext.getWorkerWorkspace(any()))
        .thenReturn(workerWorkspace);
    useServerTools(Map.of());
    messagePresenter = new OpenAIMessagePresenter(config, new ImageToken());
    handler = new OpenAIWebsocketHandler(
        openAIClient,
        mapper,
        toolRegistry,
        config,
        messagePresenter,
        documentToolSessions,
        validatorFactory.getValidator(),
        workerClient);
  }

  @Test
  void handle_missingBody_throwsBadRequest() {
    WebsocketRequest request = new WebsocketRequest(1L, "POST", "/v1/chat/completions", Optional.empty());

    WebApplicationException exception = assertThrows(WebApplicationException.class, () -> handler.handle(requestContext, request));
    assertEquals(400, exception.getResponse().getStatus());
  }

  @Test
  void handle_invalidJson_throwsBadRequest() {
    WebsocketRequest request = new WebsocketRequest(1L, "POST", "/v1/chat/completions", Optional.of("not valid json"));

    WebApplicationException exception = assertThrows(WebApplicationException.class, () -> handler.handle(requestContext, request));
    assertEquals(400, exception.getResponse().getStatus());
  }

  @Test
  void handle_invalidDocumentManifest_throwsBadRequest() throws Exception {
    DocumentReference reference = new DocumentReference(
        "document-1",
        "Jung.pdf",
        "application/pdf",
        100,
        "opaque-namespace-7Kq/document-1",
        "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        null);
    ChatRequest chatRequest = new ChatRequest(
        List.of(new ChatRequest.Message(ChatRequest.Role.user, "Summarize section 12", null)),
        "gpt-4",
        null, null, null, null, null, null, null, null,
        true,
        null, null, null, null,
        List.of(reference));
    WebsocketRequest request = new WebsocketRequest(
        1L,
        "POST",
        "/v1/chat/completions",
        Optional.of(mapper.writeValueAsString(chatRequest)));
    when(documentToolSessions.open(List.of(reference)))
        .thenThrow(new InvalidDocumentManifestException("Document reference is invalid"));

    WebsocketHandlerResponse.StreamingResponse response =
        (WebsocketHandlerResponse.StreamingResponse) handler.handle(requestContext, request);
    WebApplicationException exception = assertThrows(WebApplicationException.class, () ->
        writeStreamingResponse(response, new ByteArrayOutputStream()));

    assertEquals(400, exception.getResponse().getStatus());
  }

  @Test
  void handle_nullModel_throwsBadRequest() throws JsonProcessingException {
    ChatRequest chatRequest = new ChatRequest(
        List.of(new ChatRequest.Message(ChatRequest.Role.user, "Hello", null)),
        null,
        null,
        null,
        null,
        null,
        null,
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

    WebApplicationException exception = assertThrows(
        WebApplicationException.class,
        () -> handler.handle(requestContext, request));

    assertEquals(400, exception.getResponse().getStatus());
  }

  @Test
  void handle_nonStreamingRequest_returnsSingleResponse() throws Exception {
    ChatRequest chatRequest = new ChatRequest(
        List.of(new ChatRequest.Message(ChatRequest.Role.user, "Hello", null)),
        "gpt-4",
        null,
        null,
        null,
        null,
        null,
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

    ChatCompletion mockCompletion = mock(ChatCompletion.class);
    ChatCompletion.Choice mockChoice = mock(ChatCompletion.Choice.class);
    ChatCompletionMessage mockMessage = mock(ChatCompletionMessage.class);

    when(openAIClient.chat()).thenReturn(chatService);
    when(chatService.completions()).thenReturn(completionService);
    when(completionService.create(any(ChatCompletionCreateParams.class))).thenReturn(mockCompletion);
    when(mockCompletion.choices()).thenReturn(List.of(mockChoice));
    when(mockChoice.message()).thenReturn(mockMessage);
    when(mockMessage.content()).thenReturn(Optional.of("Hello back!"));

    WebsocketHandlerResponse response = handler.handle(requestContext, request);

    assertInstanceOf(WebsocketHandlerResponse.SingleResponse.class, response);
    WebsocketHandlerResponse.SingleResponse singleResponse = (WebsocketHandlerResponse.SingleResponse) response;
    assertEquals(200, singleResponse.statusCode());
    assertEquals("Hello back!", singleResponse.body());
  }

  @Test
  void handle_messageWithOpaqueImageCapabilities_sendsMultimodalContent() throws Exception {
    List<ImageReference> imageRefs = List.of(
      new ImageReference("opaque-namespace-7Kq/photo", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=", "image/jpeg"),
      new ImageReference("temporary-images/crop", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=", "image/png")
    );
    ChatRequest chatRequest = new ChatRequest(
        List.of(new ChatRequest.Message(
            ChatRequest.Role.user,
            "What is in this image?",
            imageRefs)),
        "gpt-4",
        null, null, null, null, null, null, null, null,
        false,
        null, null, null, null
    );
    WebsocketRequest request = new WebsocketRequest(
        1L,
        "POST",
        "/v1/chat/completions",
        Optional.of(mapper.writeValueAsString(chatRequest)));

    ChatCompletion mockCompletion = mock(ChatCompletion.class);
    ChatCompletion.Choice mockChoice = mock(ChatCompletion.Choice.class);
    ChatCompletionMessage mockMessage = mock(ChatCompletionMessage.class);

    when(openAIClient.chat()).thenReturn(chatService);
    when(chatService.completions()).thenReturn(completionService);
    when(completionService.create(any(ChatCompletionCreateParams.class))).thenReturn(mockCompletion);
    when(mockCompletion.choices()).thenReturn(List.of(mockChoice));
    when(mockChoice.message()).thenReturn(mockMessage);
    when(mockMessage.content()).thenReturn(Optional.of("It's a cat"));
    when(config.getServerPort()).thenReturn(8080);

    org.mockito.ArgumentCaptor<ChatCompletionCreateParams> captor = org.mockito.ArgumentCaptor.forClass(ChatCompletionCreateParams.class);

    handler.handle(requestContext, request);

    verify(completionService).create(captor.capture());
    ChatCompletionCreateParams params = captor.getValue();

    // The user message should be multimodal (array of content parts)
    var messages = params.messages();
    assertEquals(1, messages.size());

    // Inspect the user message content: should be array of content parts, not plain text
    var userMessage = messages.getFirst().asUser();
    var content = userMessage.content();
    assertTrue(content.isArrayOfContentParts(), "Should be multipart content");

    var parts = content.asArrayOfContentParts();
    assertEquals(3, parts.size(), "Should have text + persisted image + document crop parts");
    assertTrue(parts.get(0).isText(), "First part should be text");
    assertTrue(parts.get(1).isImageUrl(), "Second part should be image_url");
    assertTrue(parts.get(2).isImageUrl(), "Third part should be image_url");

    String textContent = parts.get(0).asText().text();
    assertEquals("What is in this image?", textContent);

    String url = parts.get(1).asImageUrl().imageUrl().url();
    assertTrue(url.startsWith("http://localhost:8080/v1/images?"), "URL should point to localhost image endpoint");
    assertTrue(
        url.contains("opaque-namespace-7Kq%2Fphoto"),
        "URL should contain encoded opaque S3 key");
    assertTrue(url.contains("ek="), "URL should contain encryption key param");
    assertTrue(url.contains("token="), "URL should contain token param");
    assertTrue(url.contains("type=image%2Fjpeg"), "URL should contain media type param");
    String generatedImageUrl = parts.get(2).asImageUrl().imageUrl().url();
    assertTrue(generatedImageUrl.contains("key=temporary-images%2Fcrop"));
    assertTrue(generatedImageUrl.contains("type=image%2Fpng"));
    assertTrue(generatedImageUrl.contains("token="));
  }

  @Test
  void handle_streamingRequest_returnsStreamingResponse() throws Exception {
    ChatRequest chatRequest = new ChatRequest(
        List.of(new ChatRequest.Message(ChatRequest.Role.user, "Hello", null)),
        "gpt-4",
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        true,
        null,
        null,
        null,
        null
    );
    WebsocketRequest request = new WebsocketRequest(1L, "POST", "/v1/chat/completions", Optional.of(mapper.writeValueAsString(chatRequest)));

    WebsocketHandlerResponse response = handler.handle(requestContext, request);

    assertInstanceOf(WebsocketHandlerResponse.StreamingResponse.class, response);
  }

  @Test
  @SuppressWarnings("unchecked")
  void handle_streamingRequest_streamsTokensToOutput() throws Exception {
    ChatRequest chatRequest = new ChatRequest(
        List.of(new ChatRequest.Message(ChatRequest.Role.user, "Hello", null)),
        "gpt-4",
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        true,
        null,
        null,
        null,
        null
    );
    WebsocketRequest request = new WebsocketRequest(1L, "POST", "/v1/chat/completions", Optional.of(mapper.writeValueAsString(chatRequest)));

    ChatCompletionChunk chunk1 = mockChunk();
    ChatCompletionChunk.Choice choice1 = mock(ChatCompletionChunk.Choice.class);
    ChatCompletionChunk.Choice.Delta delta1 = mock(ChatCompletionChunk.Choice.Delta.class);

    when(chunk1.choices()).thenReturn(List.of(choice1));
    when(choice1.delta()).thenReturn(delta1);
    when(choice1.finishReason()).thenReturn(Optional.empty());
    when(delta1.content()).thenReturn(Optional.of("Hello"));
    when(delta1.toolCalls()).thenReturn(Optional.empty());

    ChatCompletionChunk chunk2 = mockChunk();
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

    WebsocketHandlerResponse response = handler.handle(requestContext, request);
    WebsocketHandlerResponse.StreamingResponse streamingResponse = (WebsocketHandlerResponse.StreamingResponse) response;

    ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
    writeStreamingResponse(streamingResponse, outputStream);

    String output = outputStream.toString();
    assertTrue(output.contains("\"type\":\"token\""));
    assertTrue(output.contains("\"content\":\"Hello\""));
    assertTrue(output.contains("\"content\":\" world!\""));
    assertTrue(output.contains("\"type\":\"completion\""));
  }

  @Test
  void handle_withTemperature_passesTemperatureToClient() throws Exception {
    ChatRequest chatRequest = new ChatRequest(
        List.of(new ChatRequest.Message(ChatRequest.Role.user, "Hello", null)),
        "gpt-4",
        0.7,
        null,
        null,
        null,
        null,
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

    ChatCompletion mockCompletion = mock(ChatCompletion.class);
    ChatCompletion.Choice mockChoice = mock(ChatCompletion.Choice.class);
    ChatCompletionMessage mockMessage = mock(ChatCompletionMessage.class);

    when(openAIClient.chat()).thenReturn(chatService);
    when(chatService.completions()).thenReturn(completionService);
    when(completionService.create(any(ChatCompletionCreateParams.class))).thenReturn(mockCompletion);
    when(mockCompletion.choices()).thenReturn(List.of(mockChoice));
    when(mockChoice.message()).thenReturn(mockMessage);
    when(mockMessage.content()).thenReturn(Optional.of("response"));

    handler.handle(requestContext, request);

    verify(completionService).create(argThat((ChatCompletionCreateParams params) ->
        params.temperature().isPresent() && params.temperature().get().equals(0.7)
    ));
  }

  @Test
  void handle_withMaxTokens_passesMaxTokensToClient() throws Exception {
    ChatRequest chatRequest = new ChatRequest(
        List.of(new ChatRequest.Message(ChatRequest.Role.user, "Hello", null)),
        "gpt-4",
        null,
        null,
        null,
        null,
        null,
        null,
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

    handler.handle(requestContext, request);

    verify(completionService).create(argThat((ChatCompletionCreateParams params) ->
        params.maxTokens().isPresent() && params.maxTokens().get().equals(100L)
    ));
  }

  @Test
  void handle_withJsonMode_passesResponseFormatToClient() throws Exception {
    ChatRequest chatRequest = new ChatRequest(
        List.of(new ChatRequest.Message(ChatRequest.Role.user, "Hello", null)),
        "gpt-4",
        null,
        null,
        null,
        null,
        null,
        null,
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

    handler.handle(requestContext, request);

    verify(completionService).create(argThat((ChatCompletionCreateParams params) ->
        params.responseFormat().isPresent()
    ));
  }

  @Test
  void handle_multipleMessageRoles_buildsCorrectParams() throws Exception {
    ChatRequest chatRequest = new ChatRequest(
        List.of(
            new ChatRequest.Message(ChatRequest.Role.system, "You are helpful", null),
            new ChatRequest.Message(ChatRequest.Role.user, "Hello", null),
            new ChatRequest.Message(ChatRequest.Role.assistant, "Hi there", null),
            new ChatRequest.Message(ChatRequest.Role.user, "How are you?", null)
        ),
        "gpt-4",
        null,
        null,
        null,
        null,
        null,
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

    ChatCompletion mockCompletion = mock(ChatCompletion.class);
    ChatCompletion.Choice mockChoice = mock(ChatCompletion.Choice.class);
    ChatCompletionMessage mockMessage = mock(ChatCompletionMessage.class);

    when(openAIClient.chat()).thenReturn(chatService);
    when(chatService.completions()).thenReturn(completionService);
    when(completionService.create(any(ChatCompletionCreateParams.class))).thenReturn(mockCompletion);
    when(mockCompletion.choices()).thenReturn(List.of(mockChoice));
    when(mockChoice.message()).thenReturn(mockMessage);
    when(mockMessage.content()).thenReturn(Optional.of("response"));

    handler.handle(requestContext, request);

    verify(completionService).create(argThat((ChatCompletionCreateParams params) ->
        params.messages().size() == 4
    ));
  }

  @Test
  void handle_emptyChoices_returnsEmptyContent() throws Exception {
    ChatRequest chatRequest = new ChatRequest(
        List.of(new ChatRequest.Message(ChatRequest.Role.user, "Hello", null)),
        "gpt-4",
        null,
        null,
        null,
        null,
        null,
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

    ChatCompletion mockCompletion = mock(ChatCompletion.class);
    ChatCompletion.Choice mockChoice = mock(ChatCompletion.Choice.class);
    ChatCompletionMessage mockMessage = mock(ChatCompletionMessage.class);

    when(openAIClient.chat()).thenReturn(chatService);
    when(chatService.completions()).thenReturn(completionService);
    when(completionService.create(any(ChatCompletionCreateParams.class))).thenReturn(mockCompletion);
    when(mockCompletion.choices()).thenReturn(List.of(mockChoice));
    when(mockChoice.message()).thenReturn(mockMessage);
    when(mockMessage.content()).thenReturn(Optional.empty());

    WebsocketHandlerResponse response = handler.handle(requestContext, request);

    WebsocketHandlerResponse.SingleResponse singleResponse = (WebsocketHandlerResponse.SingleResponse) response;
    assertEquals("", singleResponse.body());
  }

  @Test
  void handle_developerRole_buildsCorrectParams() throws Exception {
    ChatRequest chatRequest = new ChatRequest(
        List.of(
            new ChatRequest.Message(ChatRequest.Role.developer, "You are a coding assistant", null),
            new ChatRequest.Message(ChatRequest.Role.user, "Write hello world", null)
        ),
        "gpt-4",
        null,
        null,
        null,
        null,
        null,
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

    ChatCompletion mockCompletion = mock(ChatCompletion.class);
    ChatCompletion.Choice mockChoice = mock(ChatCompletion.Choice.class);
    ChatCompletionMessage mockMessage = mock(ChatCompletionMessage.class);

    when(openAIClient.chat()).thenReturn(chatService);
    when(chatService.completions()).thenReturn(completionService);
    when(completionService.create(any(ChatCompletionCreateParams.class))).thenReturn(mockCompletion);
    when(mockCompletion.choices()).thenReturn(List.of(mockChoice));
    when(mockChoice.message()).thenReturn(mockMessage);
    when(mockMessage.content()).thenReturn(Optional.of("print('hello')"));

    WebsocketHandlerResponse response = handler.handle(requestContext, request);

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
            new ChatRequest.Message(ChatRequest.Role.user, "Search for test", null),
            new ChatRequest.Message(ChatRequest.Role.tool_call, mapper.writeValueAsString(toolCallContent), null)
        ),
        "gpt-4",
        null,
        null,
        null,
        null,
        null,
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

    ChatCompletion mockCompletion = mock(ChatCompletion.class);
    ChatCompletion.Choice mockChoice = mock(ChatCompletion.Choice.class);
    ChatCompletionMessage mockMessage = mock(ChatCompletionMessage.class);

    when(openAIClient.chat()).thenReturn(chatService);
    when(chatService.completions()).thenReturn(completionService);
    when(completionService.create(any(ChatCompletionCreateParams.class))).thenReturn(mockCompletion);
    when(mockCompletion.choices()).thenReturn(List.of(mockChoice));
    when(mockChoice.message()).thenReturn(mockMessage);
    when(mockMessage.content()).thenReturn(Optional.of("Here are the results"));

    WebsocketHandlerResponse response = handler.handle(requestContext, request);

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
            new ChatRequest.Message(ChatRequest.Role.user, "Search for test", null),
            new ChatRequest.Message(ChatRequest.Role.tool_call, mapper.writeValueAsString(toolCallContent), null),
            new ChatRequest.Message(ChatRequest.Role.tool_response, mapper.writeValueAsString(toolResponseContent), null)
        ),
        "gpt-4",
        null,
        null,
        null,
        null,
        null,
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

    ChatCompletion mockCompletion = mock(ChatCompletion.class);
    ChatCompletion.Choice mockChoice = mock(ChatCompletion.Choice.class);
    ChatCompletionMessage mockMessage = mock(ChatCompletionMessage.class);

    when(openAIClient.chat()).thenReturn(chatService);
    when(chatService.completions()).thenReturn(completionService);
    when(completionService.create(any(ChatCompletionCreateParams.class))).thenReturn(mockCompletion);
    when(mockCompletion.choices()).thenReturn(List.of(mockChoice));
    when(mockChoice.message()).thenReturn(mockMessage);
    when(mockMessage.content()).thenReturn(Optional.of("Based on the search results..."));

    WebsocketHandlerResponse response = handler.handle(requestContext, request);

    assertInstanceOf(WebsocketHandlerResponse.SingleResponse.class, response);
    verify(completionService).create(argThat((ChatCompletionCreateParams params) ->
        params.messages().size() == 3
    ));
  }

  @Test
  void handle_invalidToolCallContent_throwsBadRequest() throws Exception {
    ChatRequest chatRequest = new ChatRequest(
        List.of(
            new ChatRequest.Message(ChatRequest.Role.user, "Search for test", null),
            new ChatRequest.Message(ChatRequest.Role.tool_call, "not valid json", null)
        ),
        "gpt-4",
        null,
        null,
        null,
        null,
        null,
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

    WebApplicationException exception = assertThrows(WebApplicationException.class, () -> handler.handle(requestContext, request));
    assertEquals(400, exception.getResponse().getStatus());
  }

  @Test
  void handle_invalidToolResponseContent_throwsBadRequest() throws Exception {
    ToolCallContent toolCallContent = new ToolCallContent("call_123", "web_search", "{\"query\":\"test\"}");

    ChatRequest chatRequest = new ChatRequest(
        List.of(
            new ChatRequest.Message(ChatRequest.Role.user, "Search for test", null),
            new ChatRequest.Message(ChatRequest.Role.tool_call, mapper.writeValueAsString(toolCallContent), null),
            new ChatRequest.Message(ChatRequest.Role.tool_response, "not valid json", null)
        ),
        "gpt-4",
        null,
        null,
        null,
        null,
        null,
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

    WebApplicationException exception = assertThrows(WebApplicationException.class, () -> handler.handle(requestContext, request));
    assertEquals(400, exception.getResponse().getStatus());
  }

  @Test
  @SuppressWarnings("unchecked")
  void handle_streamingWithToolCall_forwardsResultAndAttachments() throws Exception {
    ChatRequest chatRequest = new ChatRequest(
        List.of(new ChatRequest.Message(ChatRequest.Role.user, "Search for cats", null)),
        "gpt-4",
        null,
        null,
        null,
        null,
        null,
        null,
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
    ChatCompletionChunk toolCallChunk = mockChunk();
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
    ChatCompletionChunk answerChunk = mockChunk();
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
    when(mockTool.execute(anyString(), any(ToolExecutionContext.class)))
        .thenReturn(new ToolResult(
            "Full search results for cats",
            "Search complete",
            List.of(new ImageReference(
                "temporary-images/image-1",
                "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
                "image/png")),
            List.of(new AttachmentReference(
                "01ARZ3NDEKTSV4RRFFQ69G5FAV",
                "report.pdf",
                "application/pdf",
                42,
                "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
                "00000000-0000-0000-0000-000000000002/"
                    + "01ARZ3NDEKTSV4RRFFQ69G5FAV",
                120L))));

    useServerTools(Map.of("web_search", mockTool));

    WebsocketHandlerResponse response = handler.handle(requestContext, request);
    WebsocketHandlerResponse.StreamingResponse streamingResponse = (WebsocketHandlerResponse.StreamingResponse) response;

    ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
    writeStreamingResponse(streamingResponse, outputStream);

    String output = outputStream.toString();

    // Verify tool was called
    verify(mockTool).execute(
        eq("{\"query\":\"cats\"}"),
        argThat(context -> context.getDocumentSession() == emptyDocumentSession
            && context.getWorkerWorkspace() == workerWorkspace));
    ArgumentCaptor<ChatCompletionCreateParams> params =
        ArgumentCaptor.forClass(ChatCompletionCreateParams.class);
    verify(completionService, times(2)).createStreaming(params.capture());
    List<ChatCompletionMessageParam> secondRequest = params.getAllValues().get(1).messages();
    ChatCompletionUserMessageParam imageMessage = secondRequest.getLast().asUser();
    List<ChatCompletionContentPart> imageParts = imageMessage.content().asArrayOfContentParts();

    // Verify output contains tool call, tool response, content, and completion
    assertTrue(output.contains("\"type\":\"tool_call\""));
    assertTrue(output.contains("\"tool_name\":\"web_search\""));
    assertTrue(output.contains("\"type\":\"tool_response\""));
    assertTrue(output.contains("Search complete"));
    assertFalse(output.contains("Full search results for cats"));
    assertTrue(output.contains("\"attachments\":[{"));
    assertTrue(output.contains("\"filename\":\"report.pdf\""));
    assertTrue(output.contains("\"extractedTextLength\":120"));
    assertTrue(output.contains(
        "00000000-0000-0000-0000-000000000002/"
            + "01ARZ3NDEKTSV4RRFFQ69G5FAV"));
    assertTrue(output.contains("\"type\":\"token\""));
    assertTrue(output.contains("Cats are great pets!"));
    assertTrue(output.contains("\"type\":\"completion\""));
    assertEquals(2, imageParts.size());
    assertEquals(
        "Images returned by tools for visual verification.",
        imageParts.getFirst().asText().text());
    assertTrue(imageParts.getLast().asImageUrl().imageUrl().url().contains(
        "key=temporary-images%2Fimage-1"));
  }

  @Test
  @SuppressWarnings("unchecked")
  void handle_executesToolCallsInParallelAndPresentsResultsInModelOrder() throws Exception {
    ChatRequest chatRequest = new ChatRequest(
        List.of(new ChatRequest.Message(ChatRequest.Role.user, "Search both sources", null)),
        "gpt-4",
        null, null, null, null, null, null, null, null,
        true,
        null, null, null, null);
    WebsocketRequest request = new WebsocketRequest(
        1L,
        "POST",
        "/v1/chat/completions",
        Optional.of(mapper.writeValueAsString(chatRequest)));
    ChatCompletionChunk toolCallChunk = mockChunk();
    ChatCompletionChunk.Choice toolCallChoice = mock(ChatCompletionChunk.Choice.class);
    ChatCompletionChunk.Choice.Delta toolCallDelta = mock(ChatCompletionChunk.Choice.Delta.class);
    ChatCompletionChunk.Choice.Delta.ToolCall firstCall = mock(
        ChatCompletionChunk.Choice.Delta.ToolCall.class);
    ChatCompletionChunk.Choice.Delta.ToolCall.Function firstFunction = mock(
        ChatCompletionChunk.Choice.Delta.ToolCall.Function.class);
    ChatCompletionChunk.Choice.Delta.ToolCall secondCall = mock(
        ChatCompletionChunk.Choice.Delta.ToolCall.class);
    ChatCompletionChunk.Choice.Delta.ToolCall.Function secondFunction = mock(
        ChatCompletionChunk.Choice.Delta.ToolCall.Function.class);

    when(toolCallChunk.choices()).thenReturn(List.of(toolCallChoice));
    when(toolCallChoice.delta()).thenReturn(toolCallDelta);
    when(toolCallChoice.finishReason()).thenReturn(Optional.of(
        ChatCompletionChunk.Choice.FinishReason.TOOL_CALLS));
    when(toolCallDelta.toolCalls()).thenReturn(Optional.of(List.of(firstCall, secondCall)));
    when(firstCall.index()).thenReturn(0L);
    when(firstCall.id()).thenReturn(Optional.of("call_1"));
    when(firstCall.function()).thenReturn(Optional.of(firstFunction));
    when(firstFunction.name()).thenReturn(Optional.of("first_tool"));
    when(firstFunction.arguments()).thenReturn(Optional.of("{}"));
    when(secondCall.index()).thenReturn(1L);
    when(secondCall.id()).thenReturn(Optional.of("call_2"));
    when(secondCall.function()).thenReturn(Optional.of(secondFunction));
    when(secondFunction.name()).thenReturn(Optional.of("second_tool"));
    when(secondFunction.arguments()).thenReturn(Optional.of("{}"));

    StreamResponse<ChatCompletionChunk> firstResponse = mock(StreamResponse.class);
    when(firstResponse.stream()).thenReturn(Stream.of(toolCallChunk));

    ChatCompletionChunk answerChunk = mockChunk();
    ChatCompletionChunk.Choice answerChoice = mock(ChatCompletionChunk.Choice.class);
    ChatCompletionChunk.Choice.Delta answerDelta = mock(ChatCompletionChunk.Choice.Delta.class);
    when(answerChunk.choices()).thenReturn(List.of(answerChoice));
    when(answerChoice.delta()).thenReturn(answerDelta);
    when(answerChoice.finishReason()).thenReturn(Optional.of(
        ChatCompletionChunk.Choice.FinishReason.STOP));
    when(answerDelta.content()).thenReturn(Optional.of("Combined answer"));
    when(answerDelta.toolCalls()).thenReturn(Optional.empty());

    StreamResponse<ChatCompletionChunk> secondResponse = mock(StreamResponse.class);
    when(secondResponse.stream()).thenReturn(Stream.of(answerChunk));
    when(openAIClient.chat()).thenReturn(chatService);
    when(chatService.completions()).thenReturn(completionService);
    when(completionService.createStreaming(any(ChatCompletionCreateParams.class)))
        .thenReturn(firstResponse)
        .thenReturn(secondResponse);

    Tool firstTool = mock(Tool.class);
    Tool secondTool = mock(Tool.class);
    when(firstTool.getFunctionDefinition()).thenReturn(
        FunctionDefinition.builder().name("first_tool").build());
    when(secondTool.getFunctionDefinition()).thenReturn(
        FunctionDefinition.builder().name("second_tool").build());
    AtomicInteger active = new AtomicInteger();
    AtomicInteger maximum = new AtomicInteger();
    CountDownLatch bothStarted = new CountDownLatch(2);
    when(firstTool.execute(anyString(), any(ToolExecutionContext.class)))
        .thenAnswer(invocation -> parallelToolResult(
            "result-1",
            active,
            maximum,
            bothStarted));
    when(secondTool.execute(anyString(), any(ToolExecutionContext.class)))
        .thenAnswer(invocation -> parallelToolResult(
            "result-2",
            active,
            maximum,
            bothStarted));
    useServerTools(Map.of(
        "first_tool", firstTool,
        "second_tool", secondTool));

    WebsocketHandlerResponse response = handler.handle(requestContext, request);
    WebsocketHandlerResponse.StreamingResponse streaming =
        (WebsocketHandlerResponse.StreamingResponse) response;
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    writeStreamingResponse(streaming, output);

    String messages = output.toString();
    int firstResult  = messages.indexOf("\"content\":\"result-1\"");
    int secondResult = messages.indexOf("\"content\":\"result-2\"");
    assertEquals(2, maximum.get());
    assertTrue(firstResult >= 0);
    assertTrue(secondResult > firstResult);
    verify(firstTool).execute(eq("{}"), any(ToolExecutionContext.class));
    verify(secondTool).execute(eq("{}"), any(ToolExecutionContext.class));
  }

  @Test
  @SuppressWarnings("unchecked")
  void handle_streamingWithUnknownTool_logsWarningAndContinues() throws Exception {
    ChatRequest chatRequest = new ChatRequest(
        List.of(new ChatRequest.Message(ChatRequest.Role.user, "Do something", null)),
        "gpt-4",
        null,
        null,
        null,
        null,
        null,
        null,
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
    ChatCompletionChunk toolCallChunk = mockChunk();
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
    ChatCompletionChunk answerChunk = mockChunk();
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

    WebsocketHandlerResponse response = handler.handle(requestContext, request);
    WebsocketHandlerResponse.StreamingResponse streamingResponse = (WebsocketHandlerResponse.StreamingResponse) response;

    ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
    writeStreamingResponse(streamingResponse, outputStream);

    String output = outputStream.toString();

    // Should still complete, even with unknown tool
    assertTrue(output.contains("\"type\":\"tool_call\""));
    assertTrue(output.contains("\"type\":\"completion\""));
  }

  @Test
  @SuppressWarnings("unchecked")
  void handle_emptyChunkChoices_skipsProcessing() throws Exception {
    ChatRequest chatRequest = new ChatRequest(
        List.of(new ChatRequest.Message(ChatRequest.Role.user, "Hello", null)),
        "gpt-4",
        null,
        null,
        null,
        null,
        null,
        null,
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
    ChatCompletionChunk emptyChunk = mockChunk();
    when(emptyChunk.choices()).thenReturn(List.of());

    // Normal content chunk
    ChatCompletionChunk contentChunk = mockChunk();
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

    WebsocketHandlerResponse response = handler.handle(requestContext, request);
    WebsocketHandlerResponse.StreamingResponse streamingResponse = (WebsocketHandlerResponse.StreamingResponse) response;

    ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
    writeStreamingResponse(streamingResponse, outputStream);

    String output = outputStream.toString();
    assertTrue(output.contains("\"content\":\"Hello!\""));
    assertTrue(output.contains("\"type\":\"completion\""));
  }

  @Test
  @SuppressWarnings("unchecked")
  void handle_emptyContentInChunk_skipsOutput() throws Exception {
    ChatRequest chatRequest = new ChatRequest(
        List.of(new ChatRequest.Message(ChatRequest.Role.user, "Hello", null)),
        "gpt-4",
        null,
        null,
        null,
        null,
        null,
        null,
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
    ChatCompletionChunk emptyContentChunk = mockChunk();
    ChatCompletionChunk.Choice emptyContentChoice = mock(ChatCompletionChunk.Choice.class);
    ChatCompletionChunk.Choice.Delta emptyContentDelta = mock(ChatCompletionChunk.Choice.Delta.class);

    when(emptyContentChunk.choices()).thenReturn(List.of(emptyContentChoice));
    when(emptyContentChoice.delta()).thenReturn(emptyContentDelta);
    when(emptyContentChoice.finishReason()).thenReturn(Optional.empty());
    when(emptyContentDelta.content()).thenReturn(Optional.of(""));
    when(emptyContentDelta.toolCalls()).thenReturn(Optional.empty());

    // Normal content chunk
    ChatCompletionChunk contentChunk = mockChunk();
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

    WebsocketHandlerResponse response = handler.handle(requestContext, request);
    WebsocketHandlerResponse.StreamingResponse streamingResponse = (WebsocketHandlerResponse.StreamingResponse) response;

    ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
    writeStreamingResponse(streamingResponse, outputStream);

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
        List.of(new ChatRequest.Message(ChatRequest.Role.user, "Search for cats", null)),
        "gpt-4",
        null,
        null,
        null,
        null,
        null,
        null,
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
    ChatCompletionChunk toolCallChunk1 = mockChunk();
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
    ChatCompletionChunk answerChunk = mockChunk();
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
    when(mockTool.execute(anyString(), any(ToolExecutionContext.class)))
        .thenReturn(ToolResult.text("Search results"));

    useServerTools(Map.of("web_search", mockTool));

    WebsocketHandlerResponse response = handler.handle(requestContext, request);
    WebsocketHandlerResponse.StreamingResponse streamingResponse = (WebsocketHandlerResponse.StreamingResponse) response;

    ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
    writeStreamingResponse(streamingResponse, outputStream);

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
        List.of(new ChatRequest.Message(ChatRequest.Role.user, "Search for dogs", null)),
        "gpt-4",
        null,
        null,
        null,
        null,
        null,
        null,
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
    ChatCompletionChunk toolCallChunk = mockChunk();
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
    ChatCompletionChunk answerChunk = mockChunk();
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
    when(mockTool.execute(anyString(), any(ToolExecutionContext.class)))
        .thenReturn(ToolResult.text("Search results"));

    useServerTools(Map.of("web_search", mockTool));

    WebsocketHandlerResponse response = handler.handle(requestContext, request);
    WebsocketHandlerResponse.StreamingResponse streamingResponse = (WebsocketHandlerResponse.StreamingResponse) response;

    ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
    writeStreamingResponse(streamingResponse, outputStream);

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
        List.of(new ChatRequest.Message(ChatRequest.Role.user, "Hello", null)),
        "gpt-4",
        null,
        null,
        null,
        null,
        null,
        null,
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
    ChatCompletionChunk answerChunk = mockChunk();
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

    WebsocketHandlerResponse response = handler.handle(requestContext, request);
    WebsocketHandlerResponse.StreamingResponse streamingResponse = (WebsocketHandlerResponse.StreamingResponse) response;

    ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
    writeStreamingResponse(streamingResponse, outputStream);

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
  @SuppressWarnings("unchecked")
  void handle_streamingResponse_includesContextTokensInCompletion() throws Exception {
    ChatRequest chatRequest = new ChatRequest(
        List.of(new ChatRequest.Message(ChatRequest.Role.user, "Hello", null)),
        "gpt-4",
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        true,
        null,
        null,
        null,
        null
    );
    WebsocketRequest request = new WebsocketRequest(1L, "POST", "/v1/chat/completions", Optional.of(mapper.writeValueAsString(chatRequest)));

    // Content chunk
    ChatCompletionChunk contentChunk = mockChunk();
    ChatCompletionChunk.Choice contentChoice = mock(ChatCompletionChunk.Choice.class);
    ChatCompletionChunk.Choice.Delta contentDelta = mock(ChatCompletionChunk.Choice.Delta.class);

    when(contentChunk.choices()).thenReturn(List.of(contentChoice));
    when(contentChoice.delta()).thenReturn(contentDelta);
    when(contentChoice.finishReason()).thenReturn(Optional.of(ChatCompletionChunk.Choice.FinishReason.STOP));
    when(contentDelta.content()).thenReturn(Optional.of("Hello!"));
    when(contentDelta.toolCalls()).thenReturn(Optional.empty());

    // Usage chunk (empty choices, has usage)
    ChatCompletionChunk usageChunk = mock(ChatCompletionChunk.class);
    CompletionUsage     usage      = mock(CompletionUsage.class);

    when(usageChunk.choices()).thenReturn(List.of());
    when(usageChunk.usage()).thenReturn(Optional.of(usage));
    when(usage.totalTokens()).thenReturn(1500L);

    StreamResponse<ChatCompletionChunk> streamResponse = mock(StreamResponse.class);
    when(streamResponse.stream()).thenReturn(Stream.of(contentChunk, usageChunk));

    when(openAIClient.chat()).thenReturn(chatService);
    when(chatService.completions()).thenReturn(completionService);
    when(completionService.createStreaming(any(ChatCompletionCreateParams.class))).thenReturn(streamResponse);
    when(config.getMaxContextTokens()).thenReturn(262144);

    WebsocketHandlerResponse response = handler.handle(requestContext, request);
    WebsocketHandlerResponse.StreamingResponse streamingResponse = (WebsocketHandlerResponse.StreamingResponse) response;

    ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
    writeStreamingResponse(streamingResponse, outputStream);

    String output = outputStream.toString();
    assertTrue(output.contains("\"context_tokens\":1500"));
    assertTrue(output.contains("\"max_context_tokens\":262144"));
  }

  @Test
  @SuppressWarnings("unchecked")
  void handle_streamingResponseNoUsage_includesZeroContextTokens() throws Exception {
    ChatRequest chatRequest = new ChatRequest(
        List.of(new ChatRequest.Message(ChatRequest.Role.user, "Hello", null)),
        "gpt-4",
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        true,
        null,
        null,
        null,
        null
    );
    WebsocketRequest request = new WebsocketRequest(1L, "POST", "/v1/chat/completions", Optional.of(mapper.writeValueAsString(chatRequest)));

    ChatCompletionChunk contentChunk = mockChunk();
    ChatCompletionChunk.Choice contentChoice = mock(ChatCompletionChunk.Choice.class);
    ChatCompletionChunk.Choice.Delta contentDelta = mock(ChatCompletionChunk.Choice.Delta.class);

    when(contentChunk.choices()).thenReturn(List.of(contentChoice));
    when(contentChoice.delta()).thenReturn(contentDelta);
    when(contentChoice.finishReason()).thenReturn(Optional.of(ChatCompletionChunk.Choice.FinishReason.STOP));
    when(contentDelta.content()).thenReturn(Optional.of("Hi"));
    when(contentDelta.toolCalls()).thenReturn(Optional.empty());

    StreamResponse<ChatCompletionChunk> streamResponse = mock(StreamResponse.class);
    when(streamResponse.stream()).thenReturn(Stream.of(contentChunk));

    when(openAIClient.chat()).thenReturn(chatService);
    when(chatService.completions()).thenReturn(completionService);
    when(completionService.createStreaming(any(ChatCompletionCreateParams.class))).thenReturn(streamResponse);
    when(config.getMaxContextTokens()).thenReturn(262144);

    WebsocketHandlerResponse response = handler.handle(requestContext, request);
    WebsocketHandlerResponse.StreamingResponse streamingResponse = (WebsocketHandlerResponse.StreamingResponse) response;

    ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
    writeStreamingResponse(streamingResponse, outputStream);

    String output = outputStream.toString();
    assertTrue(output.contains("\"context_tokens\":0"));
    assertTrue(output.contains("\"max_context_tokens\":262144"));
  }

  @Test
  @SuppressWarnings("unchecked")
  void handle_streamingRequestSetsIncludeUsage() throws Exception {
    ChatRequest chatRequest = new ChatRequest(
        List.of(new ChatRequest.Message(ChatRequest.Role.user, "Hello", null)),
        "gpt-4",
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        true,
        null,
        null,
        null,
        null
    );
    WebsocketRequest request = new WebsocketRequest(1L, "POST", "/v1/chat/completions", Optional.of(mapper.writeValueAsString(chatRequest)));

    ChatCompletionChunk contentChunk = mockChunk();
    ChatCompletionChunk.Choice contentChoice = mock(ChatCompletionChunk.Choice.class);
    ChatCompletionChunk.Choice.Delta contentDelta = mock(ChatCompletionChunk.Choice.Delta.class);

    when(contentChunk.choices()).thenReturn(List.of(contentChoice));
    when(contentChoice.delta()).thenReturn(contentDelta);
    when(contentChoice.finishReason()).thenReturn(Optional.of(ChatCompletionChunk.Choice.FinishReason.STOP));
    when(contentDelta.content()).thenReturn(Optional.of("Hi"));
    when(contentDelta.toolCalls()).thenReturn(Optional.empty());

    StreamResponse<ChatCompletionChunk> streamResponse = mock(StreamResponse.class);
    when(streamResponse.stream()).thenReturn(Stream.of(contentChunk));

    when(openAIClient.chat()).thenReturn(chatService);
    when(chatService.completions()).thenReturn(completionService);
    when(completionService.createStreaming(any(ChatCompletionCreateParams.class))).thenReturn(streamResponse);
    when(config.getMaxContextTokens()).thenReturn(262144);

    WebsocketHandlerResponse response = handler.handle(requestContext, request);
    WebsocketHandlerResponse.StreamingResponse streamingResponse = (WebsocketHandlerResponse.StreamingResponse) response;

    ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
    writeStreamingResponse(streamingResponse, outputStream);

    verify(completionService).createStreaming(argThat((ChatCompletionCreateParams params) ->
        params.streamOptions().isPresent() &&
        params.streamOptions().get().includeUsage().isPresent() &&
        params.streamOptions().get().includeUsage().get()
    ));
  }

  @Test
  void handle_nonStreamingRequestOmitsServerAndClientTools() throws Exception {
    ChatRequest chatRequest = new ChatRequest(
        List.of(new ChatRequest.Message(ChatRequest.Role.user, "Hello", null)),
        "gpt-4",
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        false,
        null,
        null,
        null,
        List.of(new ChatRequest.ClientTool(
            "client_tool",
            "Client-provided tool",
            Map.of("type", "object")))
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

    Tool tool1 = mock(Tool.class);
    Tool tool2 = mock(Tool.class);
    useServerTools(Map.of("tool1", tool1, "tool2", tool2));

    handler.handle(requestContext, request);

    verify(completionService).create(argThat((ChatCompletionCreateParams params) ->
        params.tools().isEmpty() || params.tools().get().isEmpty()
    ));
    verifyNoInteractions(toolRegistry);
  }

  @Test
  void handle_webSearchDisabledSelectsToolsWithoutWebAccess() throws Exception {
    ChatRequest chatRequest = new ChatRequest(
        List.of(new ChatRequest.Message(ChatRequest.Role.user, "Hello", null)),
        "gpt-4",
        null, null, null, null, null, null, null, null,
        true,
        null,
        null,
        false,
        null);
    WebsocketRequest request = new WebsocketRequest(
        1L,
        "POST",
        "/v1/chat/completions",
        Optional.of(mapper.writeValueAsString(chatRequest)));
    ChatCompletionChunk contentChunk = mockChunk();
    ChatCompletionChunk.Choice choice = mock(ChatCompletionChunk.Choice.class);
    ChatCompletionChunk.Choice.Delta delta = mock(ChatCompletionChunk.Choice.Delta.class);
    StreamResponse<ChatCompletionChunk> streamResponse = mock(StreamResponse.class);

    when(openAIClient.chat()).thenReturn(chatService);
    when(chatService.completions()).thenReturn(completionService);
    when(contentChunk.choices()).thenReturn(List.of(choice));
    when(choice.delta()).thenReturn(delta);
    when(choice.finishReason()).thenReturn(Optional.of(ChatCompletionChunk.Choice.FinishReason.STOP));
    when(delta.content()).thenReturn(Optional.of("response"));
    when(delta.toolCalls()).thenReturn(Optional.empty());
    when(streamResponse.stream()).thenReturn(Stream.of(contentChunk));
    when(completionService.createStreaming(any(ChatCompletionCreateParams.class)))
        .thenReturn(streamResponse);

    WebsocketHandlerResponse response = handler.handle(requestContext, request);
    WebsocketHandlerResponse.StreamingResponse streaming =
        (WebsocketHandlerResponse.StreamingResponse) response;
    writeStreamingResponse(streaming, new ByteArrayOutputStream());

    verify(toolRegistry).forRequest(
        argThat(eligibility -> !eligibility.satisfies(Set.of(ToolRequirement.WEB_ACCESS))));
  }

  @Test
  @SuppressWarnings("unchecked")
  void handle_withDocumentsSelectsRegisteredFileTools() throws Exception {
    DocumentToolSessionFactory documentToolSessions = mock(DocumentToolSessionFactory.class);
    DocumentToolSession documentSession = mock(DocumentToolSession.class);
    Tool fileSearch = mock(Tool.class);
    FunctionDefinition function = FunctionDefinition.builder().name("file_search").build();
    DocumentReference reference = new DocumentReference(
        "document-1",
        "Jung.pdf",
        "application/pdf",
        100,
        "opaque-namespace-7Kq/document-1",
        "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        null);
    ChatRequest chatRequest = new ChatRequest(
        List.of(new ChatRequest.Message(ChatRequest.Role.user, "Summarize section 12", null)),
        "gpt-4",
        null, null, null, null, null, null, null, null,
        true,
        null, null, null, null,
        List.of(reference));
    WebsocketRequest request = new WebsocketRequest(
        1L,
        "POST",
        "/v1/chat/completions",
        Optional.of(mapper.writeValueAsString(chatRequest)));
    ChatCompletionChunk contentChunk = mockChunk();
    ChatCompletionChunk.Choice choice = mock(ChatCompletionChunk.Choice.class);
    ChatCompletionChunk.Choice.Delta delta = mock(ChatCompletionChunk.Choice.Delta.class);
    StreamResponse<ChatCompletionChunk> streamResponse = mock(StreamResponse.class);

    when(documentToolSessions.open(List.of(reference))).thenReturn(documentSession);
    when(fileSearch.getFunctionDefinition()).thenReturn(function);
    useServerTools(Map.of("file_search", fileSearch));
    when(openAIClient.chat()).thenReturn(chatService);
    when(chatService.completions()).thenReturn(completionService);
    when(contentChunk.choices()).thenReturn(List.of(choice));
    when(choice.delta()).thenReturn(delta);
    when(choice.finishReason()).thenReturn(Optional.of(ChatCompletionChunk.Choice.FinishReason.STOP));
    when(delta.content()).thenReturn(Optional.of("Summary"));
    when(delta.toolCalls()).thenReturn(Optional.empty());
    when(streamResponse.stream()).thenReturn(Stream.of(contentChunk));
    when(completionService.createStreaming(any(ChatCompletionCreateParams.class)))
        .thenReturn(streamResponse);
    handler = new OpenAIWebsocketHandler(
        openAIClient,
        mapper,
        toolRegistry,
        config,
        messagePresenter,
        documentToolSessions,
        validatorFactory.getValidator(),
        workerClient);

    WebsocketHandlerResponse response = handler.handle(requestContext, request);
    WebsocketHandlerResponse.StreamingResponse streaming =
        (WebsocketHandlerResponse.StreamingResponse) response;
    writeStreamingResponse(streaming, new ByteArrayOutputStream());

    verify(completionService).createStreaming(argThat((ChatCompletionCreateParams params) ->
        params.tools().isPresent()
            && params.tools().get().size() == 1));
    verify(toolRegistry).forRequest(
        argThat(eligibility -> eligibility.satisfies(Set.of(ToolRequirement.DOCUMENTS))));
  }

  private static ToolResult parallelToolResult(String content,
                                               AtomicInteger active,
                                               AtomicInteger maximum,
                                               CountDownLatch bothStarted)
  {
    int activeNow = active.incrementAndGet();
    maximum.accumulateAndGet(activeNow, Math::max);
    bothStarted.countDown();

    try {
      if (!bothStarted.await(2, TimeUnit.SECONDS)) {
        throw new IllegalStateException("Tool calls did not execute in parallel");
      }
      return ToolResult.text(content);
    } catch (InterruptedException error) {
      throw new IllegalStateException("Interrupted while awaiting parallel tool calls", error);
    } finally {
      active.decrementAndGet();
    }
  }

  @SuppressWarnings("unchecked")
  private static ChatCompletionChunk getToolCallChunk(String id,
                                                      String name,
                                                      String arguments)
  {
    ChatCompletionChunk chunk = mockChunk();
    ChatCompletionChunk.Choice choice = mock(ChatCompletionChunk.Choice.class);
    ChatCompletionChunk.Choice.Delta delta = mock(ChatCompletionChunk.Choice.Delta.class);
    ChatCompletionChunk.Choice.Delta.ToolCall toolCall = mock(
        ChatCompletionChunk.Choice.Delta.ToolCall.class);
    ChatCompletionChunk.Choice.Delta.ToolCall.Function function = mock(
        ChatCompletionChunk.Choice.Delta.ToolCall.Function.class);

    when(chunk.choices()).thenReturn(List.of(choice));
    when(choice.delta()).thenReturn(delta);
    when(choice.finishReason()).thenReturn(Optional.of(
        ChatCompletionChunk.Choice.FinishReason.TOOL_CALLS));
    when(delta.toolCalls()).thenReturn(Optional.of(List.of(toolCall)));
    when(toolCall.index()).thenReturn(0L);
    when(toolCall.id()).thenReturn(Optional.of(id));
    when(toolCall.function()).thenReturn(Optional.of(function));
    when(function.name()).thenReturn(Optional.of(name));
    when(function.arguments()).thenReturn(Optional.of(arguments));
    return chunk;
  }

  private static ChatCompletionChunk getContentChunk(String content) {
    ChatCompletionChunk chunk = mockChunk();
    ChatCompletionChunk.Choice choice = mock(ChatCompletionChunk.Choice.class);
    ChatCompletionChunk.Choice.Delta delta = mock(ChatCompletionChunk.Choice.Delta.class);

    when(chunk.choices()).thenReturn(List.of(choice));
    when(choice.delta()).thenReturn(delta);
    when(choice.finishReason()).thenReturn(Optional.of(
        ChatCompletionChunk.Choice.FinishReason.STOP));
    when(delta.content()).thenReturn(Optional.of(content));
    when(delta.toolCalls()).thenReturn(Optional.empty());
    return chunk;
  }
}
