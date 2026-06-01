package com.chatapp.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "workspace_permissions")
@Data
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(exclude = {"workspace", "createdBy"})
@ToString(exclude = {"workspace", "createdBy"})
public class WorkspacePermission {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "workspace_id", nullable = false)
    private Workspace workspace;

    @Enumerated(EnumType.STRING)
    @Column(name = "resource_type", nullable = false)
    private ResourceType resourceType = ResourceType.WORKSPACE;

    @Column(name = "resource_id")
    private Long resourceId;

    @Enumerated(EnumType.STRING)
    @Column(name = "principal_type", nullable = false)
    private PrincipalType principalType = PrincipalType.USER;

    @Column(name = "principal_id", nullable = false)
    private Long principalId;

    @Enumerated(EnumType.STRING)
    @Column(name = "access_level", nullable = false)
    private AccessLevel accessLevel = AccessLevel.VIEW;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by", nullable = false)
    private User createdBy;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public enum ResourceType {
        WORKSPACE,
        FOLDER,
        FILE
    }

    public enum PrincipalType {
        USER,
        BOT
    }

    public enum AccessLevel {
        NONE(0),
        VIEW(1),
        EDIT(2),
        MANAGE(3);

        private final int rank;

        AccessLevel(int rank) {
            this.rank = rank;
        }

        public int getRank() {
            return rank;
        }

        public boolean allows(AccessLevel required) {
            return this.rank >= required.rank;
        }
    }
}
