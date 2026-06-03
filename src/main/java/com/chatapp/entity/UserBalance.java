package com.chatapp.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "user_balance")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserBalance {

    @Id
    @Column(name = "user_id")
    private Long userId;

    @Column(name = "paid_points", nullable = false)
    private Integer paidPoints = 0;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
