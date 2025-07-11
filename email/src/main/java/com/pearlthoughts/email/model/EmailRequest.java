package com.pearlthoughts.email.model;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

/**
 * Represents an email request with validation constraints
 */
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class EmailRequest {

    @NotBlank(message = "Sender email is required")
    @Email(message = "Invalid sender email format")
    private String from;

    @NotNull(message = "Recipient list cannot be null")
    @Size(min = 1, message = "At least one recipient is required")
    private List<@Email(message = "Invalid recipient email format") String> to;

    private List<@Email(message = "Invalid CC email format") String> cc;

    private List<@Email(message = "Invalid BCC email format") String> bcc;

    @NotBlank(message = "Subject is required")
    @Size(max = 998, message = "Subject cannot exceed 998 characters")
    private String subject;

    @NotBlank(message = "Body is required")
    private String body;

    private boolean isHtml = false;

    private String idempotencyKey;

    //Constructor
    public EmailRequest(String from, List<String> to, String subject, String body) {
        this.from = from;
        this.to = to;
        this.subject = subject;
        this.body = body;
    }
}
