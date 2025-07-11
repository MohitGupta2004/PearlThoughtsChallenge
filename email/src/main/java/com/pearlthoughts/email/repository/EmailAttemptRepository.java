package com.pearlthoughts.email.repository;

import com.pearlthoughts.email.entity.EmailAttempt;
import com.pearlthoughts.email.model.EmailStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface EmailAttemptRepository extends JpaRepository<EmailAttempt, Long> {

    /**
     * Find email attempt by idempotency key
     */
    Optional<EmailAttempt> findByIdempotencyKey(String idempotencyKey);

    /**
     * Find email attempt by email ID
     */
    Optional<EmailAttempt> findByEmailId(String emailId);

    /**
     * Find all email attempts by status
     */
    List<EmailAttempt> findByStatus(EmailStatus status);

    /**
     * Find email attempts by status and created date range
     */
    List<EmailAttempt> findByStatusAndCreatedAtBetween(
            EmailStatus status,
            LocalDateTime startDate,
            LocalDateTime endDate
    );

    /**
     * Find pending email attempts for queue processing
     */
    @Query("SELECT ea FROM EmailAttempt ea WHERE ea.status = :status ORDER BY ea.createdAt ASC")
    List<EmailAttempt> findPendingEmailsForProcessing(@Param("status") EmailStatus status);

    /**
     * Count email attempts by status
     */
    long countByStatus(EmailStatus status);

    /**
     * Count email attempts by from email and created date range (for rate limiting)
     */
    @Query("SELECT COUNT(ea) FROM EmailAttempt ea WHERE ea.fromEmail = :fromEmail " +
            "AND ea.createdAt >= :startTime AND ea.status IN :statuses")
    long countByFromEmailAndCreatedAtAfterAndStatusIn(
            @Param("fromEmail") String fromEmail,
            @Param("startTime") LocalDateTime startTime,
            @Param("statuses") List<EmailStatus> statuses
    );

    /**
     * Find failed email attempts that can be retried
     */
    @Query("SELECT ea FROM EmailAttempt ea WHERE ea.status = :status " +
            "AND ea.attemptCount < :maxAttempts AND ea.updatedAt <= :retryAfter")
    List<EmailAttempt> findFailedEmailsForRetry(
            @Param("status") EmailStatus status,
            @Param("maxAttempts") int maxAttempts,
            @Param("retryAfter") LocalDateTime retryAfter
    );

    /**
     * Delete old email attempts (cleanup)
     */
    void deleteByCreatedAtBefore(LocalDateTime cutoffDate);
}
