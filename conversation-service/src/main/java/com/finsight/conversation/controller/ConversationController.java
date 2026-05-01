package com.finsight.conversation.controller;

import com.finsight.conversation.dto.ApiResponse;
import com.finsight.conversation.dto.ConversationResponse;
import com.finsight.conversation.dto.CreateConversationRequest;
import com.finsight.conversation.dto.MessageResponse;
import com.finsight.conversation.dto.SendMessageRequest;
import com.finsight.conversation.service.ConversationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/conversations")
@RequiredArgsConstructor
public class ConversationController {

    private final ConversationService conversationService;

    @PostMapping
    public ResponseEntity<ApiResponse<ConversationResponse>> createConversation(
            @RequestBody CreateConversationRequest request,
            @RequestHeader("X-User-Id") String userId) {

        ConversationResponse response = conversationService.createConversation(
                request.getTitle(), UUID.fromString(userId));

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiResponse.success("Conversation created", response));
    }

    @PostMapping("/{conversationId}/messages")
    public ResponseEntity<ApiResponse<MessageResponse>> sendMessage(
            @PathVariable UUID conversationId,
            @Valid @RequestBody SendMessageRequest request,
            @RequestHeader("X-User-Id") String userId) {

        MessageResponse response = conversationService.sendMessage(
                conversationId, request.getContent(), UUID.fromString(userId));

        return ResponseEntity.ok(ApiResponse.success("Message sent", response));
    }

    @GetMapping("/{conversationId}/messages")
    public ResponseEntity<ApiResponse<List<MessageResponse>>> getMessages(
            @PathVariable UUID conversationId,
            @RequestHeader("X-User-Id") String userId) {

        List<MessageResponse> messages = conversationService.getMessages(
                conversationId, UUID.fromString(userId));

        return ResponseEntity.ok(ApiResponse.success("Messages retrieved", messages));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<ConversationResponse>>> getConversations(
            @RequestHeader("X-User-Id") String userId) {

        List<ConversationResponse> conversations = conversationService
                .getConversations(UUID.fromString(userId));

        return ResponseEntity.ok(ApiResponse.success("Conversations retrieved", conversations));
    }

    @GetMapping("/health")
    public ResponseEntity<ApiResponse<String>> health() {
        return ResponseEntity.ok(ApiResponse.success("Conversation service is running", "OK"));
    }
}