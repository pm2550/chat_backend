package com.chatapp.repository;

import com.chatapp.entity.E2eeUserState;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface E2eeUserStateRepository extends JpaRepository<E2eeUserState, Long> {
}
