package com.chatapp.repository;

import com.chatapp.entity.WorkspaceFolder;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface WorkspaceFolderRepository extends JpaRepository<WorkspaceFolder, Long> {

    Optional<WorkspaceFolder> findByIdAndWorkspaceIdAndIsDeletedFalse(Long id, Long workspaceId);

    List<WorkspaceFolder> findByWorkspaceIdAndParentFolderIsNullAndIsDeletedFalseOrderByNameAsc(Long workspaceId);

    List<WorkspaceFolder> findByWorkspaceIdAndParentFolderIdAndIsDeletedFalseOrderByNameAsc(
            Long workspaceId,
            Long parentFolderId);

    Optional<WorkspaceFolder> findByIdAndWorkspaceId(Long id, Long workspaceId);

    List<WorkspaceFolder> findByWorkspaceIdAndIsDeletedTrueOrderByUpdatedAtDesc(Long workspaceId);

    List<WorkspaceFolder> findByWorkspaceIdAndIsDeletedFalse(Long workspaceId);
}
