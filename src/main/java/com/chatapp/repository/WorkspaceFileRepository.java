package com.chatapp.repository;

import com.chatapp.entity.WorkspaceFile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.Set;

@Repository
public interface WorkspaceFileRepository extends JpaRepository<WorkspaceFile, Long> {

    Optional<WorkspaceFile> findByIdAndWorkspaceIdAndIsDeletedFalse(Long id, Long workspaceId);

    List<WorkspaceFile> findByWorkspaceIdAndFolderIsNullAndIsDeletedFalseOrderByUpdatedAtDesc(Long workspaceId);

    List<WorkspaceFile> findByWorkspaceIdAndFolderIdAndIsDeletedFalseOrderByUpdatedAtDesc(
            Long workspaceId,
            Long folderId);

    Optional<WorkspaceFile> findByIdAndWorkspaceId(Long id, Long workspaceId);

    List<WorkspaceFile> findByWorkspaceIdAndIsDeletedTrueOrderByUpdatedAtDesc(Long workspaceId);

    List<WorkspaceFile> findByWorkspaceIdAndIsDeletedFalse(Long workspaceId);

    @Query("select f.currentStorageName from WorkspaceFile f where f.currentStorageName is not null")
    Set<String> findAllCurrentStorageNames();

    @Query("""
            select f from WorkspaceFile f
            where upper(f.storageProvider) in ('MINIO', 'S3')
              and f.objectKey is not null
              and f.objectKey <> ''
            """)
    List<WorkspaceFile> findObjectStoredFiles();
}
