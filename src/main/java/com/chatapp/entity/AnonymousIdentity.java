package com.chatapp.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

@Entity
@Table(name = "anonymous_identities",
       uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "chat_room_id", "assigned_date"}))
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AnonymousIdentity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "chat_room_id", nullable = false)
    private ChatRoom chatRoom;

    @Column(name = "anonymous_name", nullable = false, length = 50)
    private String anonymousName;

    @Column(name = "anonymous_avatar", length = 500)
    private String anonymousAvatar;

    @Column(name = "assigned_date", nullable = false)
    private LocalDate assignedDate;

    @Column(name = "custom_name_used")
    private Boolean customNameUsed = false;
}
