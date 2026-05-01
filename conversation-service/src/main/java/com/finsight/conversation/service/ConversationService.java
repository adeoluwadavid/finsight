package com.finsight.conversation.service;

import com.finsight.conversation.dto.ConversationResponse;
import com.finsight.conversation.dto.MessageResponse;
import com.finsight.conversation.entity.Conversation;
import com.finsight.conversation.entity.Message;
import com.finsight.conversation.repository.ConversationRepository;
import com.finsight.conversation.repository.MessageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class ConversationService {

    private final ConversationRepository conversationRepository;
    private final MessageRepository messageRepository;
    private final RagService ragService;

    @Transactional
    public ConversationResponse createConversation(String title, UUID userId) {
        Conversation conversation = Conversation.builder()
                .userId(userId)
                .title(title != null ? title : "New Conversation")
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        conversation = conversationRepository.save(conversation);
        log.info("Created conversation: {} for user: {}", conversation.getId(), userId);

        return toConversationResponse(conversation);
    }

    @Transactional
    public MessageResponse sendMessage(UUID conversationId, String content, UUID userId) {
        Conversation conversation = conversationRepository
                .findByIdAndUserId(conversationId, userId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Conversation not found: " + conversationId));

        // Save user message
        Message userMessage = Message.builder()
                .conversation(conversation)
                .role("user")
                .content(content)
                .createdAt(LocalDateTime.now())
                .build();
        messageRepository.save(userMessage);

        // Get recent conversation history for context
        List<Map<String, String>> history = messageRepository
                .findTop10ByConversationIdOrderByCreatedAtDesc(conversationId)
                .stream()
                .map(m -> {
                    Map<String, String> msg = new HashMap<>();
                    msg.put("role", m.getRole());
                    msg.put("content", m.getContent());
                    return msg;
                })
                .collect(Collectors.toList());

        // Get RAG answer
        String answer = ragService.answer(content, userId.toString(), history);

        // Save assistant message
        Message assistantMessage = Message.builder()
                .conversation(conversation)
                .role("assistant")
                .content(answer)
                .createdAt(LocalDateTime.now())
                .build();
        assistantMessage = messageRepository.save(assistantMessage);

        log.info("Message processed for conversation: {}", conversationId);

        return toMessageResponse(assistantMessage);
    }

    public List<MessageResponse> getMessages(UUID conversationId, UUID userId) {
        conversationRepository.findByIdAndUserId(conversationId, userId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Conversation not found: " + conversationId));

        return messageRepository
                .findByConversationIdOrderByCreatedAtAsc(conversationId)
                .stream()
                .map(this::toMessageResponse)
                .collect(Collectors.toList());
    }

    public List<ConversationResponse> getConversations(UUID userId) {
        return conversationRepository
                .findByUserIdOrderByUpdatedAtDesc(userId)
                .stream()
                .map(this::toConversationResponse)
                .collect(Collectors.toList());
    }

    private ConversationResponse toConversationResponse(Conversation conversation) {
        return ConversationResponse.builder()
                .id(conversation.getId())
                .title(conversation.getTitle())
                .createdAt(conversation.getCreatedAt())
                .updatedAt(conversation.getUpdatedAt())
                .build();
    }

    private MessageResponse toMessageResponse(Message message) {
        return MessageResponse.builder()
                .id(message.getId())
                .role(message.getRole())
                .content(message.getContent())
                .chartSpec(message.getChartSpec())
                .createdAt(message.getCreatedAt())
                .build();
    }
}