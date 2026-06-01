package com.chatapp.repository;

import com.chatapp.entity.WorkspacePermission;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface WorkspacePermissionRepository extends JpaRepository<WorkspacePermission, Long> {

    List<WorkspacePermission> findByWorkspaceIdAndPrincipalTypeAndPrincipalId(
            Long workspaceId,
            WorkspacePermission.PrincipalType principalType,
            Long principalId);

    List<WorkspacePermission> findByPrincipalTypeAndPrincipalId(
            WorkspacePermission.PrincipalType principalType,
            Long principalId);

    List<WorkspacePermission> findByPrincipalTypeAndPrincipalIdAndResourceTypeAndResourceId(
            WorkspacePermission.PrincipalType principalType,
            Long principalId,
            WorkspacePermission.ResourceType resourceType,
            Long resourceId);

    List<WorkspacePermission> findByWorkspaceIdAndResourceTypeAndResourceId(
            Long workspaceId,
            WorkspacePermission.ResourceType resourceType,
            Long resourceId);

    List<WorkspacePermission> findByWorkspaceIdOrderByResourceTypeAscResourceIdAscPrincipalTypeAscPrincipalIdAsc(
            Long workspaceId);

    Optional<WorkspacePermission> findByIdAndWorkspaceId(Long id, Long workspaceId);

    Optional<WorkspacePermission> findByWorkspaceIdAndResourceTypeAndResourceIdAndPrincipalTypeAndPrincipalId(
            Long workspaceId,
            WorkspacePermission.ResourceType resourceType,
            Long resourceId,
            WorkspacePermission.PrincipalType principalType,
            Long principalId);
}
