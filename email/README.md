# Resilient Email Service

A robust, production-ready email sending service built with Spring Boot that provides retry logic, fallback mechanisms, idempotency, rate limiting, and status tracking.

## Features

### Core Features
- ✅ **Retry Logic with Exponential Backoff**: Automatically retries failed email attempts with configurable backoff
- ✅ **Provider Fallback**: Seamlessly switches between email providers when one fails
- ✅ **Idempotency**: Prevents duplicate email sends using idempotency keys
- ✅ **Rate Limiting**: Sliding window rate limiting to prevent spam and abuse
- ✅ **Status Tracking**: Comprehensive tracking of email sending attempts and statuses

### Bonus Features
- ✅ **Circuit Breaker Pattern**: Prevents cascading failures by temporarily disabling failing providers
- ✅ **Simple Logging**: Structured logging throughout the application
- ✅ **Basic Queue System**: Asynchronous email processing with queue management

## Technology Stack

- **Java 21**: Latest LTS Java version with modern language features
- **Spring Boot 3.2.1**: Latest Spring Boot with enhanced performance and features
- **Gradle 8.5**: Modern build automation and dependency management
- **H2 Database**: In-memory database for development and testing
- **JUnit 5**: Modern testing framework with advanced features
- **Mockito**: Mocking framework for unit tests
- **Spring Boot Actuator**: Production-ready monitoring and health checks
- **SLF4J + Logback**: Structured logging framework

## Architecture

### High-Level Design
```
┌─────────────────┐    ┌──────────────────┐    ┌─────────────────┐
│   REST API      │───▶│   Email Service  │───▶│   Providers     │
│   Controller    │    │   (Core Logic)   │    │   (Mock A & B)  │
└─────────────────┘    └──────────────────┘    └─────────────────┘
         │                        │                       │
         ▼                        ▼                       ▼
┌─────────────────┐    ┌──────────────────┐    ┌─────────────────┐
│   Validation    │    │   Rate Limiting  │    │ Circuit Breaker │
│   & Error       │    │   Service        │    │   Service       │
│   Handling      │    └──────────────────┘    └─────────────────┘
└─────────────────┘              │                       │
                                 ▼                       ▼
                      ┌──────────────────┐    ┌─────────────────┐
                      │   Database       │    │   Queue System  │
                      │   (H2 In-Memory) │    │   (In-Memory)   │
                      └──────────────────┘    └─────────────────┘
```

### Components

1. **EmailService**: Core service handling retry logic and provider fallback
2. **EmailProvider**: Interface for email providers with mock implementations
3. **CircuitBreakerService**: Implements circuit breaker pattern for provider failures
4. **RateLimitingService**: Sliding window rate limiting implementation
5. **EmailQueueService**: Asynchronous email processing with queue management
6. **EmailController**: REST API endpoints for email operations

## Quick Start

### Prerequisites
- Java 21 or higher
- Gradle 8.5 or higher (included via wrapper)

### Installation & Setup

1. **Clone the repository**
   ```bash
   git clone <repository-url>
   cd resilient-email-service
   ```

2. **Build the project**
   ```bash
   ./gradlew build
   ```

3. **Run tests**
   ```bash
   ./gradlew test
   ```

4. **Start the application**
   ```bash
   ./gradlew bootRun
   ```

The application will start on `http://localhost:8080`

### Verify Installation
```bash
# Quick verification script
./verify-setup.sh

# Or manually check health endpoint
curl http://localhost:8080/api/v1/emails/health
```

## API Documentation

### Base URL
```
http://localhost:8080/api/v1/emails
```

### Endpoints

#### 1. Send Email (Synchronous)
```http
POST /send
Content-Type: application/json

{
  "from": "sender@example.com",
  "to": ["recipient@example.com"],
  "cc": ["cc@example.com"],
  "bcc": ["bcc@example.com"],
  "subject": "Test Email",
  "body": "This is a test email",
  "isHtml": false,
  "idempotencyKey": "optional-unique-key"
}
```

**Response:**
```json
{
  "id": "email-uuid",
  "status": "SENT",
  "message": "Email sent successfully",
  "timestamp": "2024-01-15T10:30:00",
  "providerUsed": "MockProviderA",
  "attemptCount": 1,
  "idempotencyKey": "generated-or-provided-key"
}
```

#### 2. Queue Email (Asynchronous)
```http
POST /queue
Content-Type: application/json

{
  "from": "sender@example.com",
  "to": ["recipient@example.com"],
  "subject": "Queued Email",
  "body": "This email will be processed asynchronously"
}
```

#### 3. Get Email Status
```http
GET /{emailId}/status
```

#### 4. Get Email Attempts
```http
GET /?status=SENT&page=0&size=10
```

#### 5. Get Queue Statistics
```http
GET /queue/stats
```

#### 6. Get Rate Limit Info
```http
GET /rate-limit/{fromEmail}
```

#### 7. Get Circuit Breaker Status
```http
GET /circuit-breaker/status
```

#### 8. Health Check
```http
GET /health
```

### Email Status Values
- `PENDING`: Email is queued for sending
- `SENDING`: Email is currently being sent
- `SENT`: Email was successfully sent
- `FAILED`: Email sending failed after all retries
- `RATE_LIMITED`: Email sending was rate limited
- `DUPLICATE`: Email was not sent due to duplicate detection

## Configuration

### Application Configuration (`application.yml`)

```yaml
# Email Service Configuration
email:
  service:
    retry:
      max-attempts: 3
      initial-delay: 1000  # 1 second
      max-delay: 10000     # 10 seconds
      multiplier: 2.0
    rate-limit:
      max-requests: 100
      window-seconds: 60
    circuit-breaker:
      failure-threshold: 5
      timeout-seconds: 30
      recovery-timeout-seconds: 60
    queue:
      max-size: 1000
      processing-interval: 5000  # 5 seconds
```

### Configuration Properties

| Property | Default | Description |
|----------|---------|-------------|
| `email.service.retry.max-attempts` | 3 | Maximum retry attempts per provider |
| `email.service.retry.initial-delay` | 1000 | Initial delay between retries (ms) |
| `email.service.retry.max-delay` | 10000 | Maximum delay between retries (ms) |
| `email.service.retry.multiplier` | 2.0 | Exponential backoff multiplier |
| `email.service.rate-limit.max-requests` | 100 | Max requests per time window |
| `email.service.rate-limit.window-seconds` | 60 | Time window for rate limiting |
| `email.service.circuit-breaker.failure-threshold` | 5 | Failures before opening circuit |
| `email.service.circuit-breaker.recovery-timeout-seconds` | 60 | Time before attempting recovery |
| `email.service.queue.max-size` | 1000 | Maximum queue size |
| `email.service.queue.processing-interval` | 5000 | Queue processing interval (ms) |

## Usage Examples

### Basic Email Sending
```bash
curl -X POST http://localhost:8080/api/v1/emails/send \
  -H "Content-Type: application/json" \
  -d '{
    "from": "sender@example.com",
    "to": ["recipient@example.com"],
    "subject": "Hello World",
    "body": "This is a test email"
  }'
```

### Email with Idempotency
```bash
curl -X POST http://localhost:8080/api/v1/emails/send \
  -H "Content-Type: application/json" \
  -d '{
    "from": "sender@example.com",
    "to": ["recipient@example.com"],
    "subject": "Important Email",
    "body": "This email should only be sent once",
    "idempotencyKey": "unique-business-key-123"
  }'
```

### Queue Email for Async Processing
```bash
curl -X POST http://localhost:8080/api/v1/emails/queue \
  -H "Content-Type: application/json" \
  -d '{
    "from": "sender@example.com",
    "to": ["recipient@example.com"],
    "subject": "Async Email",
    "body": "This email will be processed in the background"
  }'
```

### Check Email Status
```bash
curl http://localhost:8080/api/v1/emails/{email-id}/status
```

### Monitor System Health
```bash
curl http://localhost:8080/api/v1/emails/health
```

## Testing

### Running Tests
```bash
# Run all tests
./gradlew test

# Run specific test class
./gradlew test --tests EmailServiceTest

# Run tests with coverage
./gradlew test jacocoTestReport
```

### Test Coverage
The project includes comprehensive tests covering:
- Unit tests for all service classes
- Integration tests for the email flow
- Controller tests for API endpoints
- Circuit breaker behavior tests
- Rate limiting functionality tests

### Mock Providers
The application includes two mock email providers for testing:
- **MockProviderA**: 80% success rate, higher priority
- **MockProviderB**: 90% success rate, lower priority

## Design Decisions & Assumptions

### Design Decisions

1. **Provider Priority**: Providers are ordered by priority (lower number = higher priority)
2. **Idempotency**: Generated automatically based on email content if not provided
3. **Rate Limiting**: Implemented using sliding window with both in-memory and database checks
4. **Circuit Breaker**: Per-provider circuit breakers with configurable thresholds
5. **Database**: H2 in-memory database for simplicity and testing
6. **Queue**: In-memory blocking queue for simplicity

### Assumptions

1. **Email Content**: Email bodies are stored as plain text in the database
2. **Provider Selection**: Providers are tried in priority order until one succeeds
3. **Retry Logic**: Exponential backoff applies per provider, not globally
4. **Idempotency Keys**: Must be unique across all emails
5. **Rate Limiting**: Applied per sender email address
6. **Queue Processing**: Single-threaded queue processing for simplicity

### Production Considerations

For production deployment, consider:

1. **Database**: Replace H2 with PostgreSQL or MySQL
2. **Queue**: Use Redis, RabbitMQ, or Apache Kafka for distributed queuing
3. **Monitoring**: Add metrics with Micrometer and Prometheus
4. **Security**: Add authentication and authorization
5. **Scaling**: Consider horizontal scaling and load balancing
6. **Email Providers**: Integrate with real providers (SendGrid, AWS SES, etc.)
7. **Configuration**: Use externalized configuration management
8. **Logging**: Centralized logging with ELK stack or similar

## Error Handling

### Error Types
- **Validation Errors**: Invalid email format, missing required fields
- **Rate Limiting**: Too many requests from the same sender
- **Provider Failures**: Temporary or permanent provider issues
- **Circuit Breaker**: Provider temporarily unavailable
- **Queue Full**: Email queue at capacity

### Error Responses
All errors return structured JSON responses:
```json
{
  "error": "Error type",
  "message": "Detailed error message",
  "details": ["Additional error details"],
  "timestamp": "2024-01-15T10:30:00"
}
```

## Monitoring & Observability

### Health Checks
- Application health: `/api/v1/emails/health`
- Actuator endpoints: `/actuator/health`, `/actuator/metrics`

### Key Metrics
- Email send success/failure rates
- Provider availability and performance
- Queue size and processing time
- Rate limiting statistics
- Circuit breaker state changes

### Logging
Structured logging with different levels:
- `DEBUG`: Detailed operation logs
- `INFO`: Important state changes
- `WARN`: Recoverable errors and rate limiting
- `ERROR`: Unrecoverable errors and failures

## Contributing

### Development Setup
1. Fork the repository
2. Create a feature branch
3. Make changes with tests
4. Run tests and ensure coverage
5. Submit a pull request

### Code Style
- Follow Java naming conventions
- Use Spring Boot best practices
- Write comprehensive tests
- Document public APIs
- Use meaningful commit messages

## License

This project is licensed under the MIT License - see the LICENSE file for details.

## Support

For questions, issues, or contributions:
- Create an issue in the repository
- Submit a pull request
- Contact the development team

---

**Note**: This is a demonstration project showcasing resilient email service patterns. For production use, additional security, monitoring, and infrastructure considerations are required. 