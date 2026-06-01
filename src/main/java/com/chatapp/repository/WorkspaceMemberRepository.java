package com.chatapp.repository;

import com.chatapp.entity.WorkspaceMember;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface WorkspaceMemberRepository extends JpaRepository<WorkspaceMember, Long> {

    Optional<WorkspaceMember> findByWorkspaceIdAndUserId(Long workspaceId, Long userId);

    List<WorkspaceMember> findByUserId(Long userId);

    List<WorkspaceMember> findByWorkspaceIdOrderByRoleAscCreatedAtAsc(Long workspaceId);

    boolean existsByWorkspaceIdAndUserId(Long workspaceId, Long userId);
}
