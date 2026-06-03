package com.chatapp.repository;

import com.chatapp.entity.WorkspaceFileVersion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.Set;

@Repository
public interface WorkspaceFileVersionRepository extends JpaRepository<WorkspaceFileVersion, Long> {

    List<WorkspaceFileVersion> findByFileIdOrderByVersionNumberDesc(Long fileId);

    Optional<WorkspaceFileVersion> findByFileIdAndVersionNumber(Long fileId, Integer versionNumber);

    @Query("select v.storageName from WorkspaceFileVersion v where v.storageName is not null")
    Set<String> findAllStorageNames();
}
