package org.moxie.confer.proxy.controllers;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.client.OpenAIClient;
import com.openai.core.http.StreamResponse;
import com.openai.models.ChatModel;
import com.openai.models.FunctionDefinition;
import com.openai.models.FunctionParameters;
import com.openai.models.ResponseFormatJsonObject;
import com.openai.models.chat.completions.ChatCompletionAssistantMessageParam;
import com.openai.models.chat.completions.ChatCompletionChunk;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.chat.completions.ChatCompletionFunctionTool;
import com.openai.models.chat.completions.ChatCompletionMessageFunctionToolCall;
import com.openai.models.chat.completions.ChatCompletionMessageParam;
import com.openai.models.chat.completions.ChatCompletionMessageToolCall;
import com.openai.models.chat.completions.ChatCompletionToolMessageParam;
import com.openai.models.chat.completions.ChatCompletionUserMessageParam;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.StreamingOutput;
import org.moxie.confer.proxy.entities.ChatRequest;
import org.moxie.confer.proxy.entities.WebsocketRequest;
import org.moxie.confer.proxy.entities.ToolCallContent;
import org.moxie.confer.proxy.entities.ToolResponseContent;
import org.moxie.confer.proxy.config.Config;
import org.moxie.confer.proxy.streaming.StreamRegistry;
import org.moxie.confer.proxy.tools.Tool;
import org.moxie.confer.proxy.tools.ToolRegistry;
import org.moxie.confer.proxy.websocket.WebsocketHandler;
import org.moxie.confer.proxy.websocket.WebsocketHandlerResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.util.*;
import java.util.stream.Collectors;

public class OpenAIWebsocketHandler implements WebsocketHandler {

  private static final Logger log = LoggerFactory.getLogger(OpenAIWebsocketHandler.class);

  private final OpenAIClient client;
  private final ObjectMapper mapper;
  private final ToolRegistry toolRegistry;
  private final Config       config;

  public OpenAIWebsocketHandler(OpenAIClient client, ObjectMapper mapper, ToolRegistry toolRegistry, Config config) {
    this.client       = client;
    this.mapper       = mapper;
    this.toolRegistry = toolRegistry;
    this.config       = config;
  }

  @Override
  public WebsocketHandlerResponse handle(WebsocketRequest request, StreamRegistry streamRegistry) {
    ChatRequest chatRequest = parseChatRequest(request);
    ChatModel   model       = parseModel(chatRequest);

    if (chatRequest.stream()) {
      return new WebsocketHandlerResponse.StreamingResponse(handleStreamingResponse(model, chatRequest));
    } else {
      return new WebsocketHandlerResponse.SingleResponse(200, handleNonStreamingRequest(model, chatRequest));
    }
  }

  private ChatRequest parseChatRequest(WebsocketRequest request) {
    if (request.body().isEmpty()) {
      throw new WebApplicationException("Request body is required", 400);
    }

    try {
      return mapper.readValue(request.body().get(), ChatRequest.class);
    } catch (JsonProcessingException e) {
      throw new WebApplicationException("Invalid ChatRequest body", 400);
    }
  }

  private ChatModel parseModel(ChatRequest chatRequest) {
    try {
      return ChatModel.of(chatRequest.model());
    } catch (IllegalArgumentException e) {
      throw new WebApplicationException("Invalid model: " + chatRequest.model(), 400);
    }
  }

  private String handleNonStreamingRequest(ChatModel model, ChatRequest chatRequest) {
    ChatCompletionCreateParams params = buildCompletionParams(model, chatRequest, new ArrayList<>(), true);
    return client.chat().completions().create(params).choices().getFirst().message().content().orElse("");
  }

  private StreamingOutput handleStreamingResponse(ChatModel model, ChatRequest chatRequest) {
    return output -> {
      List<ChatCompletionMessageParam> conversationHistory = new ArrayList<>();
      int                              maxIterations       = config.getMaxToolIterations();
      int                              iteration           = 0;

      Set<String> clientToolNames = chatRequest.clientTools() != null ? chatRequest.clientTools().stream().map(ChatRequest.ClientTool::name).collect(Collectors.toSet()) : Set.of();

      while (iteration++ < maxIterations) {
        boolean isLastIteration = iteration >= maxIterations;

        if (isLastIteration) {
          ChatCompletionUserMessageParam wrapUpMessage = ChatCompletionUserMessageParam.builder()
              .content("[System: You have used all available tool calls. Please provide your final response to the user now based on the information you have gathered. Do not attempt to use any tools.]")
              .build();
          conversationHistory.add(ChatCompletionMessageParam.ofUser(wrapUpMessage));
        }

        ChatCompletionCreateParams params    = buildCompletionParams(model, chatRequest, conversationHistory, !isLastIteration);
        ChunkProcessor             processor = new ChunkProcessor(mapper);

        try (StreamResponse<ChatCompletionChunk> response = client.chat().completions().createStreaming(params)) {
          response.stream().forEach(chunk -> processor.processChunk(chunk, output));
        }

        ToolCallRequests toolCalls = processor.getToolCallRequests(clientToolNames);

        if (!toolCalls.hasToolCalls()) {
          sendCompletion(output, false);
          return;
        }

        conversationHistory.add(buildAssistantMessageWithToolCalls(toolCalls.all()));

        for (ToolCallRequest req : toolCalls.serverToolCalls()) {
          sendToolCallToClient(req, output);
        }

        for (ToolCallRequest req : toolCalls.serverToolCalls()) {
          Optional<Tool> tool = toolRegistry.getTool(req.functionName());

          if (tool.isPresent()) {
            String fullResult   = tool.get().execute(req.arguments(), req.id(), output);
            String clientResult = tool.get().getClientResult(fullResult);

            ChatCompletionToolMessageParam toolMessage = ChatCompletionToolMessageParam.builder()
                                                                                       .toolCallId(req.id())
                                                                                       .content(fullResult)
                                                                                       .build();
            conversationHistory.add(ChatCompletionMessageParam.ofTool(toolMessage));

            sendToolResponseToClient(req.id(), req.functionName(), clientResult, toolCalls.hasClientToolCalls() ? fullResult : null, output);
          } else {
            log.warn("Unknown tool function: {}", req.functionName());
          }
        }

        // Send client tool calls after server tools are done
        if (toolCalls.hasClientToolCalls()) {
          for (ToolCallRequest req : toolCalls.clientToolCalls()) {
            sendClientToolCallToClient(req, output);
          }

          sendCompletion(output, true);
          return;
        }
      }

      log.warn("Reached maximum tool calling iterations ({})", maxIterations);
      sendCompletion(output, false);
    };
  }

  private ChatCompletionCreateParams buildCompletionParams(ChatModel model, ChatRequest chatRequest, List<ChatCompletionMessageParam> additionalMessages, boolean includeTools) {
    ChatCompletionCreateParams.Builder builder = ChatCompletionCreateParams.builder()
                                                                           .model(model);

    if (chatRequest.temperature() != null) {
      builder.temperature(chatRequest.temperature());
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
        case user      -> builder.addUserMessage(message.content());
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
            log.error("Failed to parse tool_call content: {}", message.content(), e);
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
            log.error("Failed to parse tool_response content: {}", message.content(), e);
            throw new WebApplicationException("Invalid tool_response message content", 400);
          }
        }
      }
    }

    for (ChatCompletionMessageParam message : additionalMessages) {
      builder.addMessage(message);
    }

    if (includeTools) {
      addToolsToBuilder(builder, chatRequest.webSearch(), chatRequest.clientTools());
    }

    return builder.build();
  }

  private void addToolsToBuilder(ChatCompletionCreateParams.Builder builder, Boolean webSearch, List<ChatRequest.ClientTool> clientTools) {
    boolean includeExternalTools = webSearch == null || webSearch;

    for (Tool tool : toolRegistry.getAllTools().values()) {
      if (includeExternalTools || !tool.hasExternalRequests()) {
        builder.addTool(ChatCompletionFunctionTool.builder()
                                                  .function(tool.getFunctionDefinition())
                                                  .build());
      }
    }

    if (clientTools != null) {
      for (ChatRequest.ClientTool ct : clientTools) {
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

  private void sendToolResponseToClient(String toolCallId, String toolName, String content, String fullContent, OutputStream output) {
    try {
      Map<String, Object> toolResponseMessage = new LinkedHashMap<>();
      toolResponseMessage.put("type", "tool_response");
      toolResponseMessage.put("tool_call_id", toolCallId);
      toolResponseMessage.put("tool_name", toolName);
      toolResponseMessage.put("content", content);

      if (fullContent != null) {
        toolResponseMessage.put("full_content", fullContent);
      }

      String message = mapper.writeValueAsString(toolResponseMessage);
      output.write(message.getBytes());
      output.flush();
    } catch (IOException e) {
      log.warn("Error sending tool response message: {}", e.getMessage());
      throw new WebApplicationException("Streaming error", Response.Status.INTERNAL_SERVER_ERROR);
    }
  }

  private void sendCompletion(OutputStream output, boolean pendingToolCalls) {
    try {
      Map<String, Object> completion = new LinkedHashMap<>();
      completion.put("type", "completion");

      if (pendingToolCalls) {
        completion.put("pending_tool_calls", true);
      }

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

  private static class ChunkProcessor {

    private final Map<Integer, ToolCallAccumulator> toolCalls = new HashMap<>();
    private final ObjectMapper mapper;

    public ChunkProcessor(ObjectMapper mapper) {
      this.mapper = mapper;
    }

    public void processChunk(ChatCompletionChunk chunk, OutputStream output) {
      try {
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
