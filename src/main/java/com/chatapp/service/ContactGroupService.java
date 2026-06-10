package com.chatapp.service;

import com.chatapp.dto.ContactGroupDto;
import com.chatapp.entity.ContactGroup;
import com.chatapp.entity.ContactGroupItem;
import com.chatapp.entity.ContactGroupItem.TargetType;
import com.chatapp.entity.User;
import com.chatapp.repository.ChatRoomRepository;
import com.chatapp.repository.ContactGroupItemRepository;
import com.chatapp.repository.ContactGroupRepository;
import com.chatapp.repository.FriendshipRepository;
import com.chatapp.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class ContactGroupService {

    private final ContactGroupRepository contactGroupRepository;
    private final ContactGroupItemRepository contactGroupItemRepository;
    private final UserRepository userRepository;
    private final FriendshipRepository friendshipRepository;
    private final ChatRoomRepository chatRoomRepository;

    @Transactional(readOnly = true)
    public ContactGroupDto.Bundle listGroups(Long userId) {
        List<ContactGroupDto.GroupSummary> groups =
                contactGroupRepository.findByUserIdOrderBySortOrderAscNameAsc(userId)
                        .stream()
                        .map(ContactGroupDto.GroupSummary::fromEntity)
                        .toList();
        List<ContactGroupDto.ItemAssignment> assignments =
                contactGroupItemRepository.findByUserId(userId)
                        .stream()
                        .map(ContactGroupDto.ItemAssignment::fromEntity)
                        .toList();
        return new ContactGroupDto.Bundle(groups, assignments);
    }

    public ContactGroupDto.GroupSummary createGroup(
            Long userId,
            ContactGroupDto.UpsertGroupRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("用户不存在"));
        String name = normalizeName(request.getName());
        if (contactGroupRepository.existsByUserIdAndName(userId, name)) {
            throw new IllegalArgumentException("分组名称已存在");
        }

        ContactGroup group = new ContactGroup();
        group.setUser(user);
        group.setName(name);
        group.setSortOrder(request.getSortOrder() != null
                ? request.getSortOrder()
                : contactGroupRepository.maxSortOrderForUser(userId) + 1);

        ContactGroup saved = contactGroupRepository.save(group);
        log.info("用户 {} 创建通讯录分组 {}", userId, saved.getId());
        return ContactGroupDto.GroupSummary.fromEntity(saved);
    }

    public ContactGroupDto.GroupSummary updateGroup(
            Long userId,
            Long groupId,
            ContactGroupDto.UpsertGroupRequest request) {
        ContactGroup group = ownedGroup(userId, groupId);
        String name = normalizeName(request.getName());
        if (!name.equals(group.getName()) && contactGroupRepository.existsByUserIdAndName(userId, name)) {
            throw new IllegalArgumentException("分组名称已存在");
        }
        group.setName(name);
        if (request.getSortOrder() != null) {
            group.setSortOrder(request.getSortOrder());
        }
        return ContactGroupDto.GroupSummary.fromEntity(contactGroupRepository.save(group));
    }

    public void deleteGroup(Long userId, Long groupId) {
        ContactGroup group = ownedGroup(userId, groupId);
        contactGroupItemRepository.deleteByGroupIdAndUserId(groupId, userId);
        contactGroupRepository.delete(group);
        log.info("用户 {} 删除通讯录分组 {}, assignments 已回到未分组", userId, groupId);
    }

    public List<ContactGroupDto.GroupSummary> reorderGroups(Long userId, List<Long> groupIds) {
        Map<Long, ContactGroup> owned = new HashMap<>();
        for (ContactGroup group : contactGroupRepository.findByUserIdOrderBySortOrderAscNameAsc(userId)) {
            owned.put(group.getId(), group);
        }
        if (groupIds.size() != owned.size() || !owned.keySet().containsAll(groupIds)) {
            throw new IllegalArgumentException("分组排序列表与当前用户分组不匹配");
        }
        for (int i = 0; i < groupIds.size(); i++) {
            owned.get(groupIds.get(i)).setSortOrder(i);
        }
        return contactGroupRepository.saveAll(owned.values())
                .stream()
                .sorted((a, b) -> {
                    int byOrder = Integer.compare(a.getSortOrder(), b.getSortOrder());
                    return byOrder != 0 ? byOrder : a.getName().compareTo(b.getName());
                })
                .map(ContactGroupDto.GroupSummary::fromEntity)
                .toList();
    }

    public ContactGroupDto.ItemAssignment assignItem(
            Long userId,
            ContactGroupDto.AssignItemRequest request) {
        TargetType targetType = parseTargetType(request.getTargetType());
        Long targetId = request.getTargetId();
        verifyTargetVisibleToUser(userId, targetType, targetId);

        if (request.getGroupId() == null || request.getGroupId() <= 0) {
            contactGroupItemRepository.deleteByUserIdAndTargetTypeAndTargetId(userId, targetType, targetId);
            return null;
        }

        ContactGroup group = ownedGroup(userId, request.getGroupId());
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("用户不存在"));
        ContactGroupItem item = contactGroupItemRepository
                .findByUserIdAndTargetTypeAndTargetId(userId, targetType, targetId)
                .orElseGet(ContactGroupItem::new);
        item.setUser(user);
        item.setGroup(group);
        item.setTargetType(targetType);
        item.setTargetId(targetId);

        ContactGroupItem saved = contactGroupItemRepository.save(item);
        return ContactGroupDto.ItemAssignment.fromEntity(saved);
    }

    private ContactGroup ownedGroup(Long userId, Long groupId) {
        return contactGroupRepository.findByIdAndUserId(groupId, userId)
                .orElseThrow(() -> new AccessDeniedException("分组不存在或无权访问"));
    }

    private void verifyTargetVisibleToUser(Long userId, TargetType targetType, Long targetId) {
        boolean allowed = switch (targetType) {
            case FRIEND -> friendshipRepository.areFriends(userId, targetId);
            case ROOM -> chatRoomRepository.isMember(targetId, userId);
        };
        if (!allowed) {
            throw new AccessDeniedException("不能移动不属于你的联系人或会话");
        }
    }

    private TargetType parseTargetType(String value) {
        try {
            return TargetType.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (Exception e) {
            throw new IllegalArgumentException("targetType 只能是 FRIEND 或 ROOM");
        }
    }

    private String normalizeName(String value) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("分组名称不能为空");
        }
        if (trimmed.length() > 100) {
            throw new IllegalArgumentException("分组名称不能超过 100 个字符");
        }
        return trimmed;
    }
}
