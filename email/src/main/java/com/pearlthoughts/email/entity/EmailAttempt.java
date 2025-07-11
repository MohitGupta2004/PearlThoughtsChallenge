package com.pearlthoughts.email.entity;

import com.pearlthoughts.email.model.EmailStatus;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Entity representing an email sending attempt for tracking and idempotency
 */
@Entity
@Table(name = "email_attempts", indexes = {
        @Index(name = "idx_idempotency_key", columnList = "idempotencyKey", unique = true),
        @Index(name = "idx_status", columnList = "status"),
        @Index(name = "idx_created_at", columnList = "createdAt")
})
@AllArgsConstructor
@NoArgsConstructor
@Data
@Getter
@Setter
public class EmailAttempt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String emailId;

    @Column(unique = true)
    private String idempotencyKey;

    @Column(nullable = false)
    private String fromEmail;

    @Column(nullable = false, length = 1000)
    private String toEmails;

    @Column(length = 1000)
    private String ccEmails;

    @Column(length = 1000)
    private String bccEmails;

    @Column(nullable = false, length = 998)
    private String subject;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String body;

    @Column(nullable = false)
    private boolean isHtml = false;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private EmailStatus status;

    @Column
    private String providerUsed;

    @Column(nullable = false)
    private int attemptCount = 0;

    @Column(columnDefinition = "TEXT")
    private String errorMessage;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @Column
    private LocalDateTime sentAt;
}
