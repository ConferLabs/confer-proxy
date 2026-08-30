package org.moxie.confer.proxy.controllers;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.client.OpenAIClient;
import com.openai.core.JsonValue;
import com.openai.core.http.StreamResponse;
import com.openai.models.ChatModel;
import com.openai.models.FunctionDefinition;
import com.openai.models.FunctionParameters;
import com.openai.models.ResponseFormatJsonObject;
import com.openai.models.chat.completions.ChatCompletionAssistantMessageParam;
import com.openai.models.chat.completions.ChatCompletionChunk;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.chat.completions.ChatCompletionFunctionTool;
import com.openai.models.chat.completions.ChatCompletionStreamOptions;
import com.openai.models.chat.completions.ChatCompletionMessageFunctionToolCall;
import com.openai.models.chat.completions.ChatCompletionMessageParam;
import com.openai.models.chat.completions.ChatCompletionMessageToolCall;
import com.openai.models.completions.CompletionUsage;
import com.openai.models.chat.completions.ChatCompletionToolMessageParam;
import com.openai.models.chat.completions.ChatCompletionUserMessageParam;
import jakarta.validation.Validator;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import org.moxie.confer.proxy.attachments.AttachmentReference;
import org.moxie.confer.proxy.config.Config;
import org.moxie.confer.proxy.documents.DocumentToolSession;
import org.moxie.confer.proxy.documents.DocumentToolSessionFactory;
import org.moxie.confer.proxy.documents.InvalidDocumentManifestException;
import org.moxie.confer.proxy.entities.ChatRequest;
import org.moxie.confer.proxy.entities.DocumentReference;
import org.moxie.confer.proxy.entities.WebsocketRequest;
import org.moxie.confer.proxy.entities.ToolCallContent;
import org.moxie.confer.proxy.entities.ToolResponseContent;
import org.moxie.confer.proxy.images.ImageReference;
import org.moxie.confer.proxy.presenters.OpenAIMessagePresenter;
import org.moxie.confer.proxy.presenters.ToolResultPresentation;
import org.moxie.confer.proxy.tools.Tool;
import org.moxie.confer.proxy.tools.ToolExecutionContext;
import org.moxie.confer.proxy.tools.ToolRequirement;
import org.moxie.confer.proxy.tools.ToolResult;
import org.moxie.confer.proxy.tools.registry.RequestToolSet;
import org.moxie.confer.proxy.tools.registry.ToolEligibility;
import org.moxie.confer.proxy.tools.registry.ToolRegistry;
import org.moxie.confer.proxy.websocket.WebsocketConnectionContext;
import org.moxie.confer.proxy.websocket.WebsocketHandler;
import org.moxie.confer.proxy.websocket.WebsocketHandlerResponse;
import org.moxie.confer.proxy.workers.WorkerClient;
import org.moxie.confer.proxy.workers.WorkerWorkspace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Gatherers;

public class OpenAIWebsocketHandler implements WebsocketHandler {

  private static final Logger log = LoggerFactory.getLogger(OpenAIWebsocketHandler.class);

  private final OpenAIClient               client;
  private final ObjectMapper               mapper;
  private final ToolRegistry               toolRegistry;
  private final Config                     config;
  private final OpenAIMessagePresenter     messagePresenter;
  private final DocumentToolSessionFactory documentToolSessions;
  private final Validator                  validator;
  private final WorkerClient               workerClient;

  public OpenAIWebsocketHandler(OpenAIClient client,
                                ObjectMapper mapper,
                                ToolRegistry toolRegistry,
                                Config config,
                                OpenAIMessagePresenter messagePresenter,
                                DocumentToolSessionFactory documentToolSessions,
                                Validator validator,
                                WorkerClient workerClient)
  {
    this.client               = client;
    this.mapper               = mapper;
    this.toolRegistry         = toolRegistry;
    this.config               = config;
    this.messagePresenter     = messagePresenter;
    this.documentToolSessions = Objects.requireNonNull(documentToolSessions, "documentToolSessions");
    this.validator            = Objects.requireNonNull(validator, "validator");
    this.workerClient         = workerClient;
  }

  @Override
  public WebsocketHandlerResponse handle(WebsocketConnectionContext context,
                                         WebsocketRequest           request)
  {
    ChatRequest chatRequest = parseChatRequest(request);
    ChatModel   model       = ChatModel.of(config.getVllmServedModelName());

    if (chatRequest.stream()) {
      return new WebsocketHandlerResponse.StreamingResponse(
          output -> handleStreamingResponse(context, model, chatRequest, output));
    } else {
      return new WebsocketHandlerResponse.SingleResponse(
          200,
          handleNonStreamingRequest(model, chatRequest));
    }
  }

  private ChatRequest parseChatRequest(WebsocketRequest request) {
    if (request.body().isEmpty()) {
      throw new WebApplicationException("Request body is required", 400);
    }

    try {
      ChatRequest chatRequest = mapper.readValue(request.body().get(), ChatRequest.class);

      if (chatRequest == null || !validator.validate(chatRequest).isEmpty()) {
        throw new WebApplicationException("Invalid ChatRequest body", 400);
      }

      return chatRequest;
    } catch (JsonProcessingException error) {
      throw new WebApplicationException("Invalid ChatRequest body", 400);
    }
  }

  private String handleNonStreamingRequest(ChatModel model, ChatRequest chatRequest) {
    ChatCompletionCreateParams params = buildCompletionParams(model, chatRequest, new ArrayList<>(), false, RequestToolSet.empty());
    return client.chat().completions().create(params).choices().getFirst().message().content().orElse("");
  }

  private void handleStreamingResponse(WebsocketConnectionContext context,
                                       ChatModel                  model,
                                       ChatRequest                chatRequest,
                                       OutputStream               output)
  {
    try {
      DocumentToolSession documentSession = documentToolSessions.open(chatRequest.documents());
      WorkerWorkspace     workerWorkspace = context.getWorkerWorkspace(workerClient);

      List<ChatCompletionMessageParam> conversationHistory = new ArrayList<>();
      int                              maxIterations       = config.getMaxToolIterations();
      int                              iteration           = 0;
      long                             contextTokens       = 0;

      RequestToolSet       serverTools     = toolRegistry.forRequest(toolEligibility(chatRequest));
      ToolExecutionContext toolContext     = new ToolExecutionContext(documentSession, workerWorkspace);
      Set<String>          clientToolNames = chatRequest.clientTools() == null ? Set.of() :
                                             chatRequest.clientTools()
                                                        .stream()
                                                        .map(ChatRequest.ClientTool::name)
                                                        .filter(name -> !serverTools.contains(name))
                                                        .collect(Collectors.toUnmodifiableSet());

      while (iteration++ < maxIterations) {
        boolean isLastIteration = iteration >= maxIterations;

        if (isLastIteration) {
          ChatCompletionUserMessageParam wrapUpMessage = ChatCompletionUserMessageParam.builder()
              .content("[System: You have used all available tool calls. Please provide your final response to the user now based on the information you have gathered. Do not attempt to use any tools.]")
              .build();
          conversationHistory.add(ChatCompletionMessageParam.ofUser(wrapUpMessage));
        }

        ChatCompletionCreateParams params    = buildCompletionParams(model, chatRequest, conversationHistory, !isLastIteration, serverTools);
        ChunkProcessor             processor = new ChunkProcessor();

        try (StreamResponse<ChatCompletionChunk> response = client.chat().completions().createStreaming(params)) {
          response.stream().forEach(chunk -> processor.processChunk(chunk, output));
        }

        contextTokens = processor.getUsage().map(CompletionUsage::totalTokens).orElse(0L);

        ToolCallRequests toolCalls = processor.getToolCallRequests(clientToolNames);

        if (!toolCalls.hasToolCalls()) {
          sendCompletion(output, false, contextTokens);
          return;
        }

        conversationHistory.add(buildAssistantMessageWithToolCalls(toolCalls.all()));

        for (ToolCallRequest req : toolCalls.serverToolCalls()) {
          sendToolCallToClient(req, output);
        }

        toolCalls.serverToolCalls().stream()
            .gather(Gatherers.mapConcurrent(
                Math.max(1, toolCalls.serverToolCalls().size()),
                request -> executeToolCall(request, serverTools, toolContext)))
            .flatMap(Optional::stream)
            .forEachOrdered(executed -> {
              ToolCallRequest        request      = executed.request();
              ToolResult             result       = executed.result();
              ToolResultPresentation presentation = messagePresenter.presentToolResult(
                  request.id(),
                  result);
              conversationHistory.addAll(presentation.messages());

              sendToolResponseToClient(request.id(),
                                       request.functionName(),
                                       result.clientContent(),
                                       toolCalls.hasClientToolCalls() ? result.modelContent() : null,
                                       toolCalls.hasClientToolCalls()
                                           ? presentation.imageReferences()
                                           : List.of(),
                                       result.attachments(),
                                       output);
            });

        // Send client tool calls after server tools are done
        if (toolCalls.hasClientToolCalls()) {
          for (ToolCallRequest req : toolCalls.clientToolCalls()) {
            sendClientToolCallToClient(req, output);
          }

          sendCompletion(output, true, contextTokens);
          return;
        }
      }

      log.warn("Reached maximum tool calling iterations ({})", maxIterations);
      sendCompletion(output, false, contextTokens);
    } catch (InvalidDocumentManifestException error) {
      throw new WebApplicationException("Invalid document references", 400);
    }
  }

  private Optional<ExecutedToolCall> executeToolCall(ToolCallRequest request,
                                                     RequestToolSet serverTools,
                                                     ToolExecutionContext context)
  {
    Optional<Tool> tool = serverTools.find(request.functionName());

    if (tool.isEmpty()) {
      log.warn("Unknown tool function: {}", request.functionName());
      return Optional.empty();
    }

    ToolResult result = tool.orElseThrow().execute(request.arguments(), context);
    return Optional.of(new ExecutedToolCall(request, result));
  }

  private ToolEligibility toolEligibility(ChatRequest request) {
    Set<ToolRequirement> satisfiedRequirements = EnumSet.noneOf(ToolRequirement.class);

    if (!Boolean.FALSE.equals(request.webSearch())) {
      satisfiedRequirements.add(ToolRequirement.WEB_ACCESS);
    }

    if (request.documents() != null && request.documents().stream().anyMatch(DocumentReference::supportsDocumentTools)) {
      satisfiedRequirements.add(ToolRequirement.DOCUMENTS);
    }

    return new ToolEligibility(satisfiedRequirements);
  }

  private ChatCompletionCreateParams buildCompletionParams(ChatModel model,
                                                           ChatRequest chatRequest,
                                                           List<ChatCompletionMessageParam> additionalMessages,
                                                           boolean includeTools,
                                                           RequestToolSet serverTools)
  {
    ChatCompletionCreateParams.Builder builder = ChatCompletionCreateParams.builder()
                                                                           .model(model);

    if (chatRequest.stream()) {
      builder.streamOptions(ChatCompletionStreamOptions.builder()
                                                       .includeUsage(true)
                                                       .build());
    }

    if (chatRequest.temperature() != null) {
      builder.temperature(chatRequest.temperature());
    }

    if (chatRequest.topP() != null) {
      builder.topP(chatRequest.topP());
    }

    if (chatRequest.topK() != null) {
      builder.putAdditionalBodyProperty("top_k", JsonValue.from(chatRequest.topK()));
    }

    if (chatRequest.minP() != null) {
      builder.putAdditionalBodyProperty("min_p", JsonValue.from(chatRequest.minP()));
    }

    if (chatRequest.presencePenalty() != null) {
      builder.presencePenalty(chatRequest.presencePenalty());
    }

    if (chatRequest.frequencyPenalty() != null) {
      builder.frequencyPenalty(chatRequest.frequencyPenalty());
    }

    if (chatRequest.repetitionPenalty() != null) {
      builder.putAdditionalBodyProperty("repetition_penalty", JsonValue.from(chatRequest.repetitionPenalty()));
    }

    if (chatRequest.thinking() != null && !chatRequest.thinking()) {
      builder.putAdditionalBodyProperty("chat_template_kwargs", JsonValue.from(Map.of("enable_thinking", false)));
    }

    if (chatRequest.maxTokens() != null) {
      builder.maxTokens(chatRequest.maxTokens());
    }

    if (chatRequest.json() != null && chatRequest.json()) {
      builder.responseFormat(ResponseFormatJsonObject.builder().build());
    }

    for (ChatRequest.Message message : chatRequest.messages()) {
      switch (message.role()) {
        case assistant -> builder.addAssistantMessage(message.content());
        case user      -> {
          if (hasImages(message)) {
            builder.addMessage(ChatCompletionMessageParam.ofUser(messagePresenter.presentUserMessage(message.content(), message.imageRefs())));
          } else {
            builder.addUserMessage(message.content());
          }
        }
        case system    -> builder.addSystemMessage(message.content());
        case developer -> builder.addDeveloperMessage(message.content());
        case tool_call -> {
          try {
            ToolCallContent toolCallContent = mapper.readValue(message.content(), ToolCallContent.class);

            ChatCompletionMessageFunctionToolCall functionToolCall = ChatCompletionMessageFunctionToolCall.builder()
                .id(toolCallContent.toolCallId())
                .function(ChatCompletionMessageFunctionToolCall.Function.builder()
                    .name(toolCallContent.toolName())
                    .arguments(toolCallContent.toolArguments())
                    .build())
                .build();

            ChatCompletionAssistantMessageParam assistantMessageParam = ChatCompletionAssistantMessageParam.builder()
                .addToolCall(ChatCompletionMessageToolCall.ofFunction(functionToolCall))
                .build();

            builder.addMessage(ChatCompletionMessageParam.ofAssistant(assistantMessageParam));
          } catch (JsonProcessingException e) {
            log.error("Failed to parse tool_call content", e);
            throw new WebApplicationException("Invalid tool_call message content", 400);
          }
        }
        case tool_response -> {
          try {
            ToolResponseContent toolResponseContent = mapper.readValue(message.content(), ToolResponseContent.class);

            ChatCompletionToolMessageParam toolMessage = ChatCompletionToolMessageParam.builder()
                .toolCallId(toolResponseContent.toolCallId())
                .content(toolResponseContent.content())
                .build();

            builder.addMessage(ChatCompletionMessageParam.ofTool(toolMessage));
          } catch (JsonProcessingException e) {
            log.error("Failed to parse tool_response content", e);
            throw new WebApplicationException("Invalid tool_response message content", 400);
          }
        }
      }
    }

    for (ChatCompletionMessageParam message : additionalMessages) {
      builder.addMessage(message);
    }

    if (includeTools) {
      addToolsToBuilder(builder, chatRequest.clientTools(), serverTools);
    }

    return builder.build();
  }

  private static boolean hasImages(ChatRequest.Message message) {
    return message.imageRefs() != null && !message.imageRefs().isEmpty();
  }

  private void addToolsToBuilder(ChatCompletionCreateParams.Builder builder,
                                 List<ChatRequest.ClientTool> clientTools,
                                 RequestToolSet serverTools)
  {
    for (Tool tool : serverTools.values()) {
      builder.addTool(ChatCompletionFunctionTool.builder()
                                                .function(tool.getFunctionDefinition())
                                                .build());
    }

    if (clientTools != null) {
      for (ChatRequest.ClientTool ct : clientTools) {
        if (serverTools.contains(ct.name())) {
          continue;
        }

        FunctionParameters.Builder paramsBuilder = FunctionParameters.builder();

        for (Map.Entry<String, Object> entry : ct.parameters().entrySet()) {
          paramsBuilder.putAdditionalProperty(entry.getKey(), com.openai.core.JsonValue.from(entry.getValue()));
        }

        builder.addTool(ChatCompletionFunctionTool.builder()
                                                  .function(FunctionDefinition.builder()
                                                      .name(ct.name())
                                                      .description(ct.description())
                                                      .parameters(paramsBuilder.build())
                                                      .build())
                                                  .build());
      }
    }
  }

  private ChatCompletionMessageParam buildAssistantMessageWithToolCalls(List<ToolCallRequest> toolCalls) {
    List<ChatCompletionMessageToolCall> toolCallsList = new ArrayList<>();

    for (ToolCallRequest req : toolCalls) {
      ChatCompletionMessageFunctionToolCall functionToolCall = ChatCompletionMessageFunctionToolCall.builder()
          .id(req.id())
          .function(ChatCompletionMessageFunctionToolCall.Function.builder()
              .name(req.functionName())
              .arguments(req.arguments())
              .build())
          .build();
      toolCallsList.add(ChatCompletionMessageToolCall.ofFunction(functionToolCall));
    }

    ChatCompletionAssistantMessageParam assistantMessageParam = ChatCompletionAssistantMessageParam.builder()
        .toolCalls(toolCallsList)
        .build();

    return ChatCompletionMessageParam.ofAssistant(assistantMessageParam);
  }

  private void sendToolCallToClient(ToolCallRequest req, OutputStream output) {
    try {
      Map<String, Object> toolCallMessage = Map.of(
          "type", "tool_call",
          "tool_call_id", req.id(),
          "tool_name", req.functionName(),
          "tool_arguments", req.arguments()
      );
      String message = mapper.writeValueAsString(toolCallMessage);
      output.write(message.getBytes());
      output.flush();
    } catch (IOException e) {
      log.warn("Error sending tool call message: {}", e.getMessage());
      throw new WebApplicationException("Streaming error", Response.Status.INTERNAL_SERVER_ERROR);
    }
  }

  private void sendClientToolCallToClient(ToolCallRequest req, OutputStream output) {
    try {
      Map<String, Object> toolCallMessage = Map.of(
          "type", "client_tool_call",
          "tool_call_id", req.id(),
          "tool_name", req.functionName(),
          "tool_arguments", req.arguments()
      );
      String message = mapper.writeValueAsString(toolCallMessage);
      output.write(message.getBytes());
      output.flush();
    } catch (IOException e) {
      log.warn("Error sending client tool call message: {}", e.getMessage());
      throw new WebApplicationException("Streaming error", Response.Status.INTERNAL_SERVER_ERROR);
    }
  }

  private void sendToolResponseToClient(String toolCallId,
                                        String toolName,
                                        String content,
                                        String fullContent,
                                        List<ImageReference> imageRefs,
                                        List<AttachmentReference> attachments,
                                        OutputStream output)
  {
    try {
      Map<String, Object> toolResponseMessage = new LinkedHashMap<>();
      toolResponseMessage.put("type", "tool_response");
      toolResponseMessage.put("tool_call_id", toolCallId);
      toolResponseMessage.put("tool_name", toolName);
      toolResponseMessage.put("content", content);

      if (fullContent != null) {
        toolResponseMessage.put("full_content", fullContent);
      }

      if (!imageRefs.isEmpty()) {
        toolResponseMessage.put("image_refs", imageRefs);
      }

      if (!attachments.isEmpty()) {
        toolResponseMessage.put("attachments", attachments);
      }

      String message = mapper.writeValueAsString(toolResponseMessage);
      output.write(message.getBytes());
      output.flush();
    } catch (IOException e) {
      log.warn("Error sending tool response message: {}", e.getMessage());
      throw new WebApplicationException("Streaming error", Response.Status.INTERNAL_SERVER_ERROR);
    }
  }

  private void sendCompletion(OutputStream output, boolean pendingToolCalls, long contextTokens) {
    try {
      Map<String, Object> completion = new LinkedHashMap<>();
      completion.put("type", "completion");

      if (pendingToolCalls) {
        completion.put("pending_tool_calls", true);
      }

      completion.put("context_tokens", contextTokens);
      completion.put("max_context_tokens", config.getMaxContextTokens());

      String completionMessage = mapper.writeValueAsString(completion);
      output.write(completionMessage.getBytes());
      output.flush();
    } catch (IOException e) {
      log.warn("Error sending stream completion signal: {}", e.getMessage());
      throw new WebApplicationException("Streaming error", Response.Status.INTERNAL_SERVER_ERROR);
    }
  }

  record ToolCallRequest(
    String id,
    String functionName,
    String arguments
  ) {}

  record ToolCallRequests(
    List<ToolCallRequest> serverToolCalls,
    List<ToolCallRequest> clientToolCalls
  ) {
    boolean hasToolCalls() {
      return !serverToolCalls.isEmpty() || !clientToolCalls.isEmpty();
    }

    boolean hasClientToolCalls() {
      return !clientToolCalls.isEmpty();
    }

    List<ToolCallRequest> all() {
      List<ToolCallRequest> all = new ArrayList<>(serverToolCalls);
      all.addAll(clientToolCalls);
      return all;
    }
  }

  private class ChunkProcessor {

    private final Map<Integer, ToolCallAccumulator> toolCalls = new TreeMap<>();
    private CompletionUsage    usage;

    public Optional<CompletionUsage> getUsage() {
      return Optional.ofNullable(usage);
    }

    public void processChunk(ChatCompletionChunk chunk, OutputStream output) {
      try {
        chunk.usage().ifPresent(u -> this.usage = u);

        if (chunk.choices().isEmpty()) {
          return;
        }

        ChatCompletionChunk.Choice       choice = chunk.choices().getFirst();
        ChatCompletionChunk.Choice.Delta delta  = choice.delta();

        if (choice.finishReason().isPresent()) {
          log.info("Stream finished with reason: {}", choice.finishReason().get());
        }

        if (delta.toolCalls().isPresent() && !delta.toolCalls().get().isEmpty()) {
          accumulateToolCalls(delta.toolCalls().get());
          return;
        }

        JsonValue reasoningValue = delta._additionalProperties().get("reasoning");
        if (reasoningValue != null && reasoningValue.asString().isPresent()) {
          streamReasoningToOutput(reasoningValue.asStringOrThrow(), output);
        }

        if (delta.content().isPresent()) {
          String content = delta.content().get();
          streamContentToOutput(content, output);
        }
      } catch (IOException e) {
        log.error("Error streaming OpenAI response: {}", e.getMessage());
        throw new WebApplicationException("Streaming error", Response.Status.INTERNAL_SERVER_ERROR);
      }
    }

    private void accumulateToolCalls(List<ChatCompletionChunk.Choice.Delta.ToolCall> toolCallDeltas) {
      for (ChatCompletionChunk.Choice.Delta.ToolCall toolCallDelta : toolCallDeltas) {
        int                 index = (int) toolCallDelta.index();
        ToolCallAccumulator acc   = toolCalls.computeIfAbsent(index, i -> new ToolCallAccumulator());

        if (toolCallDelta.id().isPresent()) {
          acc.id = toolCallDelta.id().get();
        }

        if (toolCallDelta.function().isPresent()) {
          ChatCompletionChunk.Choice.Delta.ToolCall.Function func = toolCallDelta.function().get();

          if (func.name().isPresent()) {
            acc.functionName = func.name().get();
          }

          if (func.arguments().isPresent()) {
            acc.arguments.append(func.arguments().get());
          }
        }
      }
    }

    private void streamReasoningToOutput(String reasoning, OutputStream output) throws IOException {
      if (!reasoning.isEmpty()) {
        String thinkingMessage = mapper.writeValueAsString(Map.of("type", "thinking", "content", reasoning));
        output.write(thinkingMessage.getBytes());
        output.flush();
      }
    }

    private void streamContentToOutput(String content, OutputStream output) throws IOException {
      if (!content.isEmpty()) {
        String tokenMessage = mapper.writeValueAsString(Map.of("type", "token", "content", content));
        output.write(tokenMessage.getBytes());
        output.flush();
      }
    }

    public ToolCallRequests getToolCallRequests(Set<String> clientToolNames) {
      List<ToolCallRequest> serverCalls = new ArrayList<>();
      List<ToolCallRequest> clientCalls = new ArrayList<>();

      for (ToolCallAccumulator acc : toolCalls.values()) {
        ToolCallRequest req = new ToolCallRequest(acc.id, acc.functionName, acc.arguments.toString());

        if (clientToolNames.contains(acc.functionName)) {
          clientCalls.add(req);
        } else {
          serverCalls.add(req);
        }
      }

      return new ToolCallRequests(serverCalls, clientCalls);
    }
  }

  private static class ToolCallAccumulator {
    String id;
    String functionName;
    StringBuilder arguments = new StringBuilder();
  }
}
