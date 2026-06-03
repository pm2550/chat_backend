package com.chatapp.repository;

import com.chatapp.entity.AnonymousTheme;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AnonymousThemeRepository extends JpaRepository<AnonymousTheme, Long> {

    Optional<AnonymousTheme> findByThemeKeyAndIsEnabledTrue(String themeKey);

    List<AnonymousTheme> findByIsEnabledTrueOrderByIdAsc();
}
