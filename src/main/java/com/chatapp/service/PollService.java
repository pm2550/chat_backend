package com.chatapp.service;

import com.chatapp.dto.PollDto;
import com.chatapp.entity.Message;
import com.chatapp.entity.Poll;
import com.chatapp.entity.PollVote;
import com.chatapp.entity.User;
import com.chatapp.repository.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class PollService {

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};
    private static final TypeReference<List<Integer>> INT_LIST = new TypeReference<>() {};

    private final ObjectMapper objectMapper;
    private final MessageService messageService;
    private final MessageRepository messageRepository;
    private final PollRepository pollRepository;
    private final PollVoteRepository pollVoteRepository;
    private final UserRepository userRepository;
    private final ChatRoomRepository chatRoomRepository;

    @Transactional
    public PollDto create(Long userId, PollDto.CreateRequest request) {
        validateCreate(userId, request);
        Message message = messageService.sendMessage(
                userId,
                request.getChatRoomId(),
                "[投票] " + request.getQuestion().trim(),
                Message.MessageType.POLL);
        Poll poll = new Poll();
        poll.setMessage(message);
        poll.setQuestion(request.getQuestion().trim());
        poll.setOptionsJson(write(request.getOptions()));
        poll.setMultiSelect(Boolean.TRUE.equals(request.getMultiSelect()));
        poll.setAnonymous(Boolean.TRUE.equals(request.getAnonymous()));
        poll.setExpiresAt(request.getExpiresAt());
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("用户不存在"));
        poll.setCreatedBy(user);
        poll = pollRepository.save(poll);
        message.setPollId(poll.getId());
        messageRepository.save(message);
        return toDto(poll);
    }

    @Transactional
    public PollDto vote(Long pollId, Long userId, List<Integer> optionIndexes) {
        Poll poll = pollRepository.findById(pollId)
                .orElseThrow(() -> new IllegalArgumentException("投票不存在"));
        validateMember(poll, userId);
        if (poll.getExpiresAt() != null && LocalDateTime.now().isAfter(poll.getExpiresAt())) {
            throw new IllegalArgumentException("投票已截止");
        }
        List<String> options = readOptions(poll);
        List<Integer> normalized = normalizeVote(optionIndexes, options.size(), Boolean.TRUE.equals(poll.getMultiSelect()));
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("用户不存在"));
        PollVote vote = pollVoteRepository.findByPollIdAndUserId(pollId, userId).orElseGet(PollVote::new);
        vote.setPoll(poll);
        vote.setUser(user);
        vote.setOptionIndexesJson(write(normalized));
        pollVoteRepository.save(vote);
        return toDto(poll);
    }

    @Transactional
    public void deleteVote(Long pollId, Long userId) {
        Poll poll = pollRepository.findById(pollId)
                .orElseThrow(() -> new IllegalArgumentException("投票不存在"));
        validateMember(poll, userId);
        pollVoteRepository.deleteByPollIdAndUserId(pollId, userId);
    }

    @Transactional(readOnly = true)
    public PollDto get(Long pollId, Long userId) {
        Poll poll = pollRepository.findById(pollId)
                .orElseThrow(() -> new IllegalArgumentException("投票不存在"));
        validateMember(poll, userId);
        return toDto(poll);
    }

    @Transactional(readOnly = true)
    public Long getChatRoomId(Long pollId) {
        return pollRepository.findById(pollId)
                .map(poll -> poll.getMessage().getChatRoom().getId())
                .orElse(null);
    }

    private PollDto toDto(Poll poll) {
        List<String> options = readOptions(poll);
        List<PollVote> votes = pollVoteRepository.findByPollId(poll.getId());
        List<PollDto.OptionInfo> optionInfos = new ArrayList<>();
        for (int i = 0; i < options.size(); i++) {
            List<Long> voterIds = new ArrayList<>();
            for (PollVote vote : votes) {
                if (readVote(vote).contains(i)) {
                    voterIds.add(vote.getUser().getId());
                }
            }
            optionInfos.add(new PollDto.OptionInfo(
                    i,
                    options.get(i),
                    voterIds.size(),
                    Boolean.TRUE.equals(poll.getAnonymous()) ? List.of() : voterIds));
        }
        return new PollDto(
                poll.getId(),
                poll.getMessage().getId(),
                poll.getQuestion(),
                optionInfos,
                Boolean.TRUE.equals(poll.getMultiSelect()),
                Boolean.TRUE.equals(poll.getAnonymous()),
                poll.getExpiresAt(),
                votes.size());
    }

    private void validateCreate(Long userId, PollDto.CreateRequest request) {
        if (request.getChatRoomId() == null || !chatRoomRepository.isMember(request.getChatRoomId(), userId)) {
            throw new IllegalArgumentException("您不是该聊天室成员");
        }
        if (request.getQuestion() == null || request.getQuestion().trim().isEmpty()) {
            throw new IllegalArgumentException("投票问题不能为空");
        }
        if (request.getOptions() == null || request.getOptions().size() < 2 || request.getOptions().size() > 10) {
            throw new IllegalArgumentException("投票选项数量必须为 2-10");
        }
    }

    private void validateMember(Poll poll, Long userId) {
        Long roomId = poll.getMessage().getChatRoom().getId();
        if (!chatRoomRepository.isMember(roomId, userId)) {
            throw new IllegalArgumentException("您不是该聊天室成员");
        }
    }

    private List<Integer> normalizeVote(List<Integer> indexes, int optionCount, boolean multiSelect) {
        if (indexes == null || indexes.isEmpty()) {
            throw new IllegalArgumentException("请选择投票选项");
        }
        List<Integer> normalized = indexes.stream()
                .distinct()
                .filter(index -> index != null && index >= 0 && index < optionCount)
                .toList();
        if (normalized.isEmpty() || (!multiSelect && normalized.size() > 1)) {
            throw new IllegalArgumentException("无效的投票选项");
        }
        return normalized;
    }

    private List<String> readOptions(Poll poll) {
        try {
            return objectMapper.readValue(poll.getOptionsJson(), STRING_LIST);
        } catch (Exception e) {
            return List.of();
        }
    }

    private List<Integer> readVote(PollVote vote) {
        try {
            return objectMapper.readValue(vote.getOptionIndexesJson(), INT_LIST);
        } catch (Exception e) {
            return List.of();
        }
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalArgumentException("投票数据序列化失败", e);
        }
    }
}
