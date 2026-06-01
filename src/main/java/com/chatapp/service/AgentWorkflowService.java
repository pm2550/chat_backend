package com.chatapp.service;

import com.chatapp.dto.AgentTaskDto;
import com.chatapp.dto.BotDto;
import com.chatapp.dto.WorkspaceDto;
import com.chatapp.entity.AgentTask;
import com.chatapp.entity.BotConfig;
import com.chatapp.entity.ChatRoom;
import com.chatapp.entity.Message;
import com.chatapp.entity.User;
import com.chatapp.repository.AgentTaskRepository;
import com.chatapp.repository.BotConfigRepository;
import com.chatapp.repository.ChatRoomRepository;
import com.chatapp.repository.MessageRepository;
import com.chatapp.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Agent Gateway/工作流任务服务。
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class AgentWorkflowService {

    private final AgentTaskRepository agentTaskRepository;
    private final ChatRoomRepository chatRoomRepository;
    private final UserRepository userRepository;
    private final BotConfigRepository botConfigRepository;
    private final MessageRepository messageRepository;
    private final LLMService llmService;
    private final AgentGatewayService agentGatewayService;
    private final WorkspaceService workspaceService;

    public AgentTask createAndRun(Long requestedById, AgentTaskDto.CreateRequest request) {
        if (request.getChatRoomId() == null) {
            throw new IllegalArgumentException("chatRoomId 不能为空");
        }
        if (request.getPrompt() == null || request.getPrompt().isBlank()) {
            throw new IllegalArgumentException("prompt 不能为空");
        }
        if (!chatRoomRepository.isMember(request.getChatRoomId(), requestedById)) {
            throw new IllegalArgumentException("您不是该聊天室的成员");
        }

        User requestedBy = userRepository.findById(requestedById)
                .orElseThrow(() -> new RuntimeException("用户不存在"));
        ChatRoom chatRoom = chatRoomRepository.findById(request.getChatRoomId())
                .orElseThrow(() -> new RuntimeException("聊天室不存在"));
        BotConfig botConfig = request.getBotId() != null
                ? botConfigRepository.findById(request.getBotId())
                    .orElseThrow(() -> new RuntimeException("机器人不存在"))
                : null;

        AgentTask task = new AgentTask();
        task.setChatRoom(chatRoom);
        task.setRequestedBy(requestedBy);
        if (request.getBotId() != null) {
            task.setBotConfig(botConfig);
        }
        task.setPrompt(request.getPrompt().trim());
        task.setStatus(AgentTask.Status.PENDING);
        task = agentTaskRepository.save(task);

        return runTask(task, botConfig, request);
    }

    public Page<AgentTask> listRoomTasks(Long chatRoomId, Long userId, Pageable pageable) {
        if (!chatRoomRepository.isMember(chatRoomId, userId)) {
            throw new IllegalArgumentException("您不是该聊天室的成员");
        }
        return agentTaskRepository.findByChatRoomIdOrderByCreatedAtDesc(chatRoomId, pageable);
    }

    public AgentTask getTask(Long taskId, Long userId) {
        AgentTask task = agentTaskRepository.findWithDetailsById(taskId)
                .orElseThrow(() -> new RuntimeException("任务不存在"));
        if (!chatRoomRepository.isMember(task.getChatRoom().getId(), userId)) {
            throw new IllegalArgumentException("您不是该聊天室的成员");
        }
        return task;
    }

    private AgentTask runTask(AgentTask task, BotConfig botConfig, AgentTaskDto.CreateRequest request) {
        task.setStatus(AgentTask.Status.RUNNING);
        task = agentTaskRepository.save(task);

        try {
            String result = runGateway(task, botConfig);
            WorkspaceDto.FileDto artifact = saveResultArtifact(task, botConfig, request, result);
            if (artifact != null) {
                task.setArtifactWorkspaceId(artifact.getWorkspaceId());
                task.setArtifactFolderId(artifact.getFolderId());
                task.setArtifactFileId(artifact.getId());
                task.setArtifactFileName(artifact.getDisplayName());
                result = result + "\n\n[Artifact] 已保存到资料库: "
                        + artifact.getDisplayName() + " (#" + artifact.getId() + ")";
            }

            Message resultMessage = new Message();
            resultMessage.setChatRoom(task.getChatRoom());
            resultMessage.setSender(task.getRequestedBy());
            resultMessage.setBotConfig(resolveSystemAgentBot());
            resultMessage.setMessageType(Message.MessageType.SYSTEM);
            resultMessage.setMessageStatus(Message.MessageStatus.SENT);
            resultMessage.setContent(result);
            resultMessage.setCreatedAt(LocalDateTime.now());
            resultMessage = messageRepository.save(resultMessage);
            chatRoomRepository.incrementUnreadForRoomMembersExcept(
                    task.getChatRoom().getId(),
                    task.getRequestedBy().getId());

            task.setResult(result);
            task.setResultMessage(resultMessage);
            task.setStatus(AgentTask.Status.SUCCEEDED);
        } catch (Exception e) {
            log.warn("Agent task {} failed: {}", task.getId(), e.getMessage());
            task.setErrorMessage(e.getMessage());
            task.setStatus(AgentTask.Status.FAILED);
        }
        task.setCompletedAt(LocalDateTime.now());
        return agentTaskRepository.save(task);
    }

    private BotConfig resolveSystemAgentBot() {
        return botConfigRepository.findFirstByBotNameAndCreatedByIsNullOrderByIdAsc("Agent")
                .orElseGet(() -> {
                    BotConfig agent = new BotConfig();
                    agent.setBotName("Agent");
                    agent.setBotAvatar("/assets/agent-avatar.png");
                    agent.setLlmProvider(BotConfig.LLMProvider.DASHSCOPE);
                    agent.setModelName("system-agent");
                    agent.setSystemPrompt("You are a helpful agent for PM chat. Respond concisely.");
                    agent.setTemperature(0.7);
                    agent.setMaxTokens(2048);
                    agent.setIsActive(true);
                    return botConfigRepository.save(agent);
                });
    }

    private WorkspaceDto.FileDto saveResultArtifact(
            AgentTask task,
            BotConfig botConfig,
            AgentTaskDto.CreateRequest request,
            String result) throws java.io.IOException {
        if (request.getArtifactWorkspaceId() == null) {
            return null;
        }
        String fileName = request.getArtifactFileName();
        if (fileName == null || fileName.isBlank()) {
            fileName = "agent-task-" + task.getId() + "-result.txt";
        }
        return workspaceService.saveGeneratedFile(
                request.getArtifactWorkspaceId(),
                task.getRequestedBy().getId(),
                request.getArtifactFolderId(),
                botConfig != null ? botConfig.getId() : null,
                fileName,
                "text/plain",
                result.getBytes(StandardCharsets.UTF_8),
                "Agent task #" + task.getId() + " result");
    }

    private String runGateway(AgentTask task, BotConfig botConfig) {
        if (botConfig == null) {
            return agentGatewayService.isConfigured()
                    ? agentGatewayService.execute(task)
                    : "任务已接收: " + task.getPrompt();
        }

        List<BotDto.ChatMessage> messages = new ArrayList<>();
        if (botConfig.getSystemPrompt() != null && !botConfig.getSystemPrompt().isBlank()) {
            messages.add(new BotDto.ChatMessage("system", botConfig.getSystemPrompt()));
        }
        messages.add(new BotDto.ChatMessage("user", task.getPrompt()));

        BotDto.LLMResponse response = llmService.chat(botConfig, messages);
        return response.getContent() != null && !response.getContent().isBlank()
                ? response.getContent()
                : "任务已完成";
    }
}
