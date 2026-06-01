package com.chatapp.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "stickers")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Sticker {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "pack_id", nullable = false)
    private StickerPack pack;

    @Column(name = "url", length = 1024)
    private String url;

    @Column(name = "keyword", length = 80)
    private String keyword;

    @Column(name = "index_in_pack", nullable = false)
    private Integer indexInPack = 0;
}
