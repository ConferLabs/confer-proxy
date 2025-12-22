package org.moxie.confer.proxy.controllers;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.client.OpenAIClient;
import com.openai.core.http.StreamResponse;
import com.openai.models.ChatModel;
import com.openai.models.ResponseFormatJsonObject;
import com.openai.models.chat.completions.ChatCompletionAssistantMessageParam;
import com.openai.models.chat.completions.ChatCompletionChunk;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.chat.completions.ChatCompletionMessageFunctionToolCall;
import com.openai.models.chat.completions.ChatCompletionMessageParam;
import com.openai.models.chat.completions.ChatCompletionMessageToolCall;
import com.openai.models.chat.completions.ChatCompletionToolMessageParam;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.StreamingOutput;
import org.moxie.confer.proxy.entities.ChatRequest;
import org.moxie.confer.proxy.entities.WebsocketRequest;
import org.moxie.confer.proxy.entities.ToolCallContent;
import org.moxie.confer.proxy.entities.ToolResponseContent;
import org.moxie.confer.proxy.tools.Tool;
import org.moxie.confer.proxy.tools.ToolRegistry;
import org.moxie.confer.proxy.websocket.WebsocketHandler;
import org.moxie.confer.proxy.websocket.WebsocketHandlerResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.*;

public class OpenAIWebsocketHandler implements WebsocketHandler {

  private static final Logger log = LoggerFactory.getLogger(OpenAIWebsocketHandler.class);

  private static final int MAX_TOOL_ITERATIONS = 10;

  private final OpenAIClient client;
  private final ObjectMapper mapper;
  private final ToolRegistry toolRegistry;

  @Inject
  public OpenAIWebsocketHandler(OpenAIClient client, ObjectMapper mapper, ToolRegistry toolRegistry) {
    this.client       = client;
    this.mapper       = mapper;
    this.toolRegistry = toolRegistry;
  }

  @Override
  public WebsocketHandlerResponse handle(WebsocketRequest request) {
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
    ChatCompletionCreateParams params = buildCompletionParams(model, chatRequest, new ArrayList<>());
    return client.chat().completions().create(params).choices().getFirst().message().content().orElse("");
  }

  private StreamingOutput handleStreamingResponse(ChatModel model, ChatRequest chatRequest) {
    return output -> {
      List<ChatCompletionMessageParam> conversationHistory = new ArrayList<>();
      int iteration = 0;

      while (iteration++ < MAX_TOOL_ITERATIONS) {
        ChatCompletionCreateParams params    = buildCompletionParams(model, chatRequest, conversationHistory);
        ChunkProcessor             processor = new ChunkProcessor(mapper);

        try (StreamResponse<ChatCompletionChunk> response = client.chat().completions().createStreaming(params)) {
          response.stream().forEach(chunk -> processor.processChunk(chunk, output));
        }

        Map<Integer, ToolCallAccumulator> toolCalls = processor.getToolCalls();

        if (!toolCalls.isEmpty()) {
          for (ToolCallAccumulator acc : toolCalls.values()) {
            sendToolCallToClient(acc, output);
          }

          conversationHistory.add(buildAssistantMessageWithToolCalls(toolCalls));

          for (ToolCallAccumulator acc : toolCalls.values()) {
            Optional<Tool> tool = toolRegistry.getTool(acc.functionName);

            if (tool.isPresent()) {
              String toolResult = tool.get().execute(acc.arguments.toString(), acc.id, output);

              ChatCompletionToolMessageParam toolMessage = ChatCompletionToolMessageParam.builder()
                                                                                         .toolCallId(acc.id)
                                                                                         .content(toolResult)
                                                                                         .build();
              conversationHistory.add(ChatCompletionMessageParam.ofTool(toolMessage));

              // Client-friendly result (possibly summarized) goes to client for persistence
              String clientResult = tool.get().getClientResult(toolResult);
              sendToolResponseToClient(acc.id, acc.functionName, clientResult, output);
            } else {
              log.warn("Unknown tool function: {}", acc.functionName);
            }
          }
        } else {
          sendCompletion(output);
          break;
        }
      }

      if (iteration >= MAX_TOOL_ITERATIONS) {
        log.warn("Reached maximum tool calling iterations ({})", MAX_TOOL_ITERATIONS);
        sendCompletion(output);
      }
    };
  }

  private ChatCompletionCreateParams buildCompletionParams(ChatModel model, ChatRequest chatRequest, List<ChatCompletionMessageParam> additionalMessages) {
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

    addToolsToBuilder(builder);

    return builder.build();
  }

  private void addToolsToBuilder(ChatCompletionCreateParams.Builder builder) {
    for (Tool tool : toolRegistry.getAllTools().values()) {
      builder.addTool(com.openai.models.chat.completions.ChatCompletionFunctionTool.builder()
                                                                                   .function(tool.getFunctionDefinition())
                                                                                   .build());
    }
  }


  private ChatCompletionMessageParam buildAssistantMessageWithToolCalls(Map<Integer, ToolCallAccumulator> toolCalls) {
    List<ChatCompletionMessageToolCall> toolCallsList = new ArrayList<>();

    for (ToolCallAccumulator acc : toolCalls.values()) {
      ChatCompletionMessageFunctionToolCall functionToolCall = ChatCompletionMessageFunctionToolCall.builder()
          .id(acc.id)
          .function(ChatCompletionMessageFunctionToolCall.Function.builder()
              .name(acc.functionName)
              .arguments(acc.arguments.toString())
              .build())
          .build();
      toolCallsList.add(ChatCompletionMessageToolCall.ofFunction(functionToolCall));
    }

    ChatCompletionAssistantMessageParam assistantMessageParam = ChatCompletionAssistantMessageParam.builder()
        .toolCalls(toolCallsList)
        .build();

    return ChatCompletionMessageParam.ofAssistant(assistantMessageParam);
  }

  private void sendToolCallToClient(ToolCallAccumulator acc, java.io.OutputStream output) {
    try {
      Map<String, Object> toolCallMessage = Map.of(
          "type", "tool_call",
          "tool_call_id", acc.id,
          "tool_name", acc.functionName,
          "tool_arguments", acc.arguments.toString()
      );
      String message = mapper.writeValueAsString(toolCallMessage);
      output.write(message.getBytes());
      output.flush();
    } catch (IOException e) {
      log.warn("Error sending tool call message: {}", e.getMessage());
      throw new WebApplicationException("Streaming error", Response.Status.INTERNAL_SERVER_ERROR);
    }
  }

  private void sendToolResponseToClient(String toolCallId, String toolName, String toolResult, java.io.OutputStream output) {
    try {
      Map<String, Object> toolResponseMessage = Map.of(
          "type", "tool_response",
          "tool_call_id", toolCallId,
          "tool_name", toolName,
          "content", toolResult
      );
      String message = mapper.writeValueAsString(toolResponseMessage);
      output.write(message.getBytes());
      output.flush();
    } catch (IOException e) {
      log.warn("Error sending tool response message: {}", e.getMessage());
      throw new WebApplicationException("Streaming error", Response.Status.INTERNAL_SERVER_ERROR);
    }
  }

  private void sendCompletion(java.io.OutputStream output) {
    try {
      String completionMessage = mapper.writeValueAsString(Map.of("type", "completion"));
      output.write(completionMessage.getBytes());
      output.flush();
    } catch (IOException e) {
      log.warn("Error sending stream completion signal: {}", e.getMessage());
      throw new WebApplicationException("Streaming error", Response.Status.INTERNAL_SERVER_ERROR);
    }
  }

  private static class ChunkProcessor {

    private final Map<Integer, ToolCallAccumulator> toolCalls = new HashMap<>();
    private final ObjectMapper mapper;

    public ChunkProcessor(ObjectMapper mapper) {
      this.mapper = mapper;
    }

    public void processChunk(ChatCompletionChunk chunk, java.io.OutputStream output) {
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

    private void streamContentToOutput(String content, java.io.OutputStream output) throws IOException {
      if (!content.isEmpty()) {
        String tokenMessage = mapper.writeValueAsString(Map.of("type", "token", "content", content));
        output.write(tokenMessage.getBytes());
        output.flush();
      }
    }

    public Map<Integer, ToolCallAccumulator> getToolCalls() {
      return toolCalls;
    }
  }

  private static class ToolCallAccumulator {
    String id;
    String functionName;
    StringBuilder arguments = new StringBuilder();
  }
}
