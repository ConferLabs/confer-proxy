package org.moxie.confer.proxy.controllers;

import org.moxie.confer.proxy.tools.ToolResult;

record ExecutedToolCall(OpenAIWebsocketHandler.ToolCallRequest request,
                        ToolResult result)
{}
