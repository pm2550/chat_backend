package com.chatapp.repository;

import com.chatapp.entity.Workspace;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface WorkspaceRepository extends JpaRepository<Workspace, Long> {

    List<Workspace> findByOwnerIdAndIsActiveTrueOrderByUpdatedAtDesc(Long ownerId);
}
