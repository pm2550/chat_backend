package com.chatapp.repository;

import com.chatapp.entity.Sticker;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface StickerRepository extends JpaRepository<Sticker, Long> {

    @EntityGraph(attributePaths = {"pack"})
    List<Sticker> findByPackIdOrderByIndexInPackAscIdAsc(Long packId);
}
