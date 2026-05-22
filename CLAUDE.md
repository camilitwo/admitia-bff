# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

**admitia-bff** is a Spring Boot 3.4 monolith backend that consolidates multiple admission system APIs. It serves as a Backend-for-Frontend (BFF) that the React frontend connects to directly at `http://localhost:8080`.

### Tech Stack
- **Java 21** with Spring Boot 3.4.5
- **PostgreSQL** (configured via docker-compose for local development)
- **Flyway** for database migrations
- **JWT** for stateless authentication (JJWT library)
- **Firebase Admin SDK** for email verification and user management
- **Spring Data JPA + Hibernate** for persistence
- **Vercel Blob** for file storage (fallback: local `uploads/` directory)
- **Resend** for email delivery (with mock mode for development)
- **Thymeleaf** for email template rendering
- **Toku** for payment processing (invoicing, webhooks)

## Development Commands

### Build & Run
```bash
# Start development server (watches for changes, runs on port 8080 by default)
mvn spring-boot:run

# Build JAR
mvn clean package

# Skip tests during build
mvn clean package -DskipTests
```

### Database
```bash
# Start PostgreSQL locally (required for development)
docker-compose up -d postgres

# Migrations run automatically on startup via Flyway
# To check migration status, review: src/main/resources/db/migration/
```

### Code Quality & Testing
```bash
# Compile and validate
mvn clean compile

# Run all tests
mvn test

# Run a single test
mvn test -Dtest=ClassName#methodName

# Run a specific test method
mvn test -Dtest=ClassName#methodName -DfailIfNoTests=false
```

## Architecture

### Layered Structure
```
controller/     → REST endpoints, request/response handling
├── AuthController, UsersController, ApplicationsController, etc.
│
service/        → Business logic, transactions, orchestration
├── AuthService, UserService, ApplicationService, etc.
│
domain/         → JPA entities and domain objects
├── feature/    → Domain entities organized by feature (user/, student/, application/, etc.)
├── common/     → Shared enums (Role, ApplicationStatus, PaymentStatus, etc.)
│                 and BaseEntity with audit fields (created_at, updated_at)
│
repository/     → Spring Data JPA repositories for data access
│
config/         → Spring security, JWT, Firebase, JPA, CORS configuration
├── SecurityConfig, JwtService, FirebaseAuthenticationFilter, etc.
│
util/           → Shared utilities (ApiResponse, CsvUtils, RutUtils, TemplateUtils, etc.)
```

### Domain Organization
- **Entities**: Located in `src/main/java/cl/mtn/admitiabff/domain/[feature]/`
- **Base Class**: All entities extend `BaseEntity`, which provides `createdAt`, `updatedAt`, and `id` fields
- **Common Enums**: Located in `domain/common/` (e.g., `Role`, `ApplicationStatus`, `PaymentStatus`, `InterviewStatus`)
- **Soft Deletes**: Some entities use `isActive` or `deletedAt` columns; check entity definitions

### Key Service Integrations
- **AuthService**: JWT token generation, user authentication, refresh token management
- **EmailVerificationService**: Firebase email verification with public URL rewriting (hides firebaseapp.com domain)
- **EmailComposerService**: Composes emails using Thymeleaf templates from `src/main/resources/template/`
- **NotificationService**: Handles in-app and email notifications via Resend API
- **DocumentService**: Manages file uploads to Vercel Blob (with local fallback)
- **DashboardService**: Aggregates analytics and dashboard data
- **EvaluationService**: Interview and evaluation workflow
- **PaymentService**: Toku payment system integration (checkout, invoice tracking, webhook validation)

### API Routes (Prefixes)
All endpoints are namespaced:
- `/api/auth` — Login, token refresh, password reset, email verification
- `/api/users` — User CRUD and profile management
- `/api/students` — Student records and status
- `/api/applications` — Application submissions and tracking
- `/api/documents` — Document uploads and retrieval
- `/api/evaluations` — Evaluator assessments
- `/api/interviews` — Interview scheduling and results
- `/api/interviewer-schedules` — Interviewer availability
- `/api/notifications` — User notifications
- `/api/email` — Email delivery
- `/api/institutional-emails` — System email templates
- `/api/guardians` — Guardian/parent management
- `/api/dashboard` — Analytics and reporting
- `/api/payments` — Toku payment checkout and status (including webhook endpoint)

## Configuration & Environment

### Application Configuration (src/main/resources/application.yml)
- **Server Port**: `${PORT:8080}` (defaults to 8080)
- **Database**: Flyway auto-migrates from `src/main/resources/db/migration/`
- **Multipart Upload**: 20MB file size limit, 50MB request limit
- **CORS**: Configured for localhost development, customizable via env vars

### Required Environment Variables
```
SPRING_DATASOURCE_URL               # PostgreSQL: jdbc:postgresql://localhost:5432/admitia_bff
SPRING_DATASOURCE_USERNAME          # Database user (e.g., postgres)
SPRING_DATASOURCE_PASSWORD          # Database password
APP_JWT_SECRET                      # Secret key for JWT signing (at least 32 chars recommended)
APP_EMAIL_FROM                      # Sender email (verified domain in Resend)
RESEND_API_KEY                      # Resend email API key
FIREBASE_SERVICE_ACCOUNT_JSON       # Firebase service account JSON (base64 or JSON string)
```

### Optional Environment Variables
```
# Server & Networking
PORT                                    # Server port (default: 8080)
APP_CORS_ALLOWED_ORIGINS                # CORS origins (default: localhost:5173, 127.0.0.1:5173)
APP_COOKIES_SECURE                      # Secure cookies flag (default: true in prod)
APP_COOKIES_SAME_SITE                   # SameSite attribute (default: Lax)

# JWT Configuration
APP_JWT_EXPIRATION_MINUTES              # Token expiration (default: 720 = 12 hours)
APP_JWT_REFRESH_MINUTES                 # Refresh token expiration (default: 43200 = 30 days)
APP_JWT_ISSUER                          # JWT issuer claim (optional)
APP_JWT_AUDIENCE                        # JWT audience claim (optional)
APP_JWT_CLOCK_SKEW                      # Clock skew tolerance in seconds (default: 0)

# Firebase Authentication
APP_FIREBASE_ALLOWED_PROVIDERS          # Allowed auth providers (comma-separated)
APP_FIREBASE_MAX_AUTH_AGE_SECONDS       # Max auth age (default: 3600)
APP_FIREBASE_CHECK_REVOKED              # Check token revocation (default: true)

# Email Verification (Firebase)
APP_FIREBASE_VERIFICATION_PUBLIC_BASE_URL  # Public BFF URL (e.g., https://api.admitia.cl)
APP_FIREBASE_VERIFICATION_PUBLIC_PATH      # Verification path (default: /v1/auth/firebase/verify-redirect)
APP_FIREBASE_VERIFICATION_CONTINUE_URL     # Redirect URL after verification (optional)

# File Storage
APP_UPLOADS_DIR                         # Local upload directory (default: uploads/)
BLOB_READ_WRITE_TOKEN                   # Vercel Blob token for cloud storage (optional)

# Email
APP_EMAIL_MOCK_MODE                     # Mock email sending for dev (default: false in prod)
RESEND_BASE_URL                         # Resend API base URL (optional, uses default)

# Payments (Toku)
TOKU_BASE_URL                           # Toku API endpoint (default: https://api.trytoku.com)
TOKU_API_KEY                            # Toku API key
TOKU_ACCOUNT_KEY                        # Toku account key
TOKU_WEBHOOK_SECRET                     # Webhook secret for validating Toku signatures
APP_PAYMENTS_APPLICATION_FEE_CLP        # Application fee in CLP (default: 0)
APP_PAYMENTS_PROCESS_ID                 # Process ID in Toku (default: ADMISION)
APP_PAYMENTS_INVOICE_DUE_DAYS           # Invoice due date offset (default: 3)
APP_PAYMENTS_WEBHOOK_TOLERANCE_SECONDS  # Webhook timestamp tolerance (default: 300)
```

## Database & Migrations

### Migration Files
Located in `src/main/resources/db/migration/`:
- `V1__create_monolith_schema.sql` — Initial schema with all tables
- `V2__add_missing_columns_documents.sql` — Document table enhancements
- `V3__add_firebase_uid_to_users.sql` — Firebase UID support
- `V4__align_interviewer_schedule_day_names.sql` — Interview schedule day name alignment
- `V5__audit_apoderados_without_firebase_uid.sql` — Audit for guardians without Firebase UID
- `V6__refresh_tokens_and_revocation.sql` — Refresh token storage and revocation
- `V7__payments_toku.sql` — Toku payment system tables (invoices, transactions, webhooks)

**Important**: 
- Migrations follow Flyway naming convention: `V[N]__description.sql` (version number then description)
- Migrations are applied automatically on startup in version order
- JPA `ddl-auto` is set to `validate` (not `create` or `update`), so the schema must be in sync with migrations
- **Never modify applied migrations**; always create new ones for schema changes
- Use `ALTER TABLE` for existing tables, `CREATE TABLE` for new ones
- Include explicit constraints (PRIMARY KEY, FOREIGN KEY, NOT NULL, UNIQUE, CHECK) in migrations

## Local Development Setup

### First Time Setup
```bash
# 1. Start PostgreSQL
docker-compose up -d postgres

# 2. Set environment variables (create .env or set in IDE)
export SPRING_DATASOURCE_URL="jdbc:postgresql://localhost:5432/admitia_bff"
export SPRING_DATASOURCE_USERNAME="postgres"
export SPRING_DATASOURCE_PASSWORD="postgres"
export APP_JWT_SECRET="your-secret-key-for-development"
export APP_EMAIL_MOCK_MODE="true"              # Mock emails in dev
export APP_EMAIL_FROM="noreply@admitia.local"  # From address (not used if mocking)
export FIREBASE_SERVICE_ACCOUNT_JSON=""        # Empty/placeholder for dev (use real JSON for email verification)

# 3. Run the application
mvn spring-boot:run
```

### Email & Payments in Development
- **Email mocking**: With `APP_EMAIL_MOCK_MODE=true`, sent emails are logged but not actually delivered
- **Resend API**: For actual email delivery, set `APP_EMAIL_MOCK_MODE=false` and provide `RESEND_API_KEY`
- **Toku payments**: Optional for local dev; use test credentials from Toku dashboard if needed

### Verify Setup
- Frontend should connect to `http://localhost:8080`
- Flyway runs migrations automatically on startup
- Check logs for "Started AdmitiaBffApplication" message

## Key Design Patterns

### Security
- **JWT Authentication**: Stateless token-based auth with access + refresh tokens, configured in SecurityConfig
- **Refresh Token Revocation**: Stored in database (V6 migration), checked on token refresh
- **Firebase Auth Integration**: Email verification via Firebase with public URL rewriting to hide firebaseapp.com domain
- **CORS**: Configured for development (localhost:5173) and production via `APP_CORS_ALLOWED_ORIGINS`
- **CSRF**: Disabled (stateless API design)
- **Webhook Security**: Toku payment webhooks validated using signature verification (see `TokuSignatureVerifierTest`)

### Persistence
- **Soft Deletes**: Some entities use `is_active` or `deleted_at` columns (check domain classes)
- **Timestamps**: Audit fields (`created_at`, `updated_at`) are standard across entities
- **Lazy Loading**: JPA is set to `open-in-view: false`, so use explicit `@Transactional` or `@Fetch`
- **Schema Validation**: `ddl-auto: validate` enforces schema-migration sync; never modify existing migration files

### Error Handling
- **ApiExceptionHandler**: Centralized exception handling with standardized error responses
- HTTP status codes follow REST conventions

### Email System
- **Template-based emails**: Thymeleaf templates in `src/main/resources/template/templateCorreo.html`
- **Resend integration**: Email delivery via Resend API (can be mocked in dev with `APP_EMAIL_MOCK_MODE`)
- **Email verification flow**: Firebase sends verification link; BFF intercepts via `/api/auth/firebase/verify-redirect`, rewrites public URL, redirects to frontend

### Dependencies
- **Lombok**: Used throughout for `@Data`, `@Getter`, `@Setter`, `@AllArgsConstructor`, `@NoArgsConstructor`
- **Spring Data JPA + Hibernate**: ORM with relationships configured in domain entities
- **JJWT**: JWT token generation and validation
- **Firebase Admin SDK**: Authentication and user management
- **Vercel Blob**: Cloud file storage (local fallback if token not configured)

### Common Utilities
Located in `src/main/java/cl/mtn/admitiabff/util/`:
- **ApiResponse**: Standardized API response wrapper
- **TemplateUtils**: Email template rendering and variable substitution (Thymeleaf integration)
- **CsvUtils**: CSV file handling and parsing
- **RutUtils**: Chilean RUT (national ID) validation and formatting
- **JsonSupport**: JSON serialization/deserialization helpers

### Authentication Flow
1. **Firebase → JWT**: `FirebaseAuthenticationFilter` intercepts requests, validates Firebase tokens, extracts user info
2. **JWT Generation**: `JwtService` creates access + refresh tokens with claims (userId, email, role)
3. **Stateless Validation**: `SecurityConfig` enforces JWT validation on protected endpoints
4. **Refresh Token Revocation**: Stored in DB (V6 migration), checked on token refresh to support logout

## Subsystems & Workflows

### Email Verification Flow
The system uses Firebase for email verification but rewrites the public URL to hide the firebaseapp.com domain:

1. **Frontend** calls `/api/auth/request-email-verification` with email
2. **EmailVerificationService** sends Firebase verification link with rewritten URL:
   - Original: `https://[firebaseapp].firebaseapp.com/...?oobCode=XXX`
   - Rewritten: `https://[public-base-url]/v1/auth/firebase/verify-redirect?oobCode=XXX` (via `APP_FIREBASE_VERIFICATION_PUBLIC_BASE_URL`)
3. **BFF endpoint** `/api/auth/firebase/verify-redirect` receives the request, extracts `oobCode`, verifies with Firebase
4. **Frontend** redirected to continue URL (if configured via `APP_FIREBASE_VERIFICATION_CONTINUE_URL`)
5. **Firebase sets** `emailVerified=true` internally

### Payment System (Toku)
Toku integration handles invoice generation and payment tracking:

1. **Application created** → `PaymentService` creates Toku invoice, stores in `toku_invoices` table
2. **Student pays** → Toku webhook sent to `/api/payments/toku/webhook`
3. **BFF validates** webhook signature using `TokuSignatureVerifier` and updates transaction status
4. **Frontend tracks** payment status via `/api/payments/status/{applicationId}`

See `src/test/java/.../TokuSignatureVerifierTest.java` for webhook validation examples.

## Important Notes

### Package Naming
- **Root Package**: `cl.mtn.admitiabff` (Chilean admission system backend)
- **Feature Package Structure**: `domain/user/`, `domain/application/`, etc. contain entities related to that feature
- **Service Package**: `service/` contains business logic services that operate on feature entities

### Exception Handling
- Centralized exception handling via `ApiExceptionHandler`
- Throw appropriate HTTP status exceptions (e.g., `NotFoundException`, `BadRequestException`) from services
- Handlers convert exceptions to standardized `ApiResponse` objects with error messages and HTTP status codes
- Do not return error information in response bodies—rely on `ApiExceptionHandler` for consistency

## Common Workflow

### Adding a New Feature
1. **Define the domain entity** → `src/main/java/cl/mtn/admitiabff/domain/[feature]/`
2. **Create the repository** → `src/main/java/cl/mtn/admitiabff/repository/`
3. **Implement the service** → `src/main/java/cl/mtn/admitiabff/service/`
4. **Create the controller** → `src/main/java/cl/mtn/admitiabff/controller/`
5. **Add database migration** → `src/main/resources/db/migration/V[N]__description.sql`
6. **Add tests** → `src/test/java/cl/mtn/admitiabff/[same-path-as-feature]/`
7. **Restart** → Flyway runs migrations automatically on next startup

### Testing
- **Location**: `src/test/java/cl/mtn/admitiabff/`
- **Current Tests**: Unit tests for critical paths (e.g., `TokuSignatureVerifierTest` for payment webhook validation)
- **Pattern**: Use JUnit 5 + Spring Test framework; consider integration tests for persistence and service logic
- **Running Tests**: `mvn test` or `mvn test -Dtest=ClassName#methodName` for specific tests

### Debugging
- Logs are configured at `INFO` level for the app and `INFO` for Spring
- Enable debug mode: `mvn spring-boot:run -Dspring-boot.run.arguments="--debug"`
- Check database queries: Set `spring.jpa.show-sql=true` in application.yml
- View SQL formatting: Set `spring.jpa.properties.hibernate.format_sql=true` in application.yml (already enabled)
- JWT debugging: Inspect token payload at [jwt.io](https://jwt.io)
- Email debugging: With `APP_EMAIL_MOCK_MODE=true`, emails are logged instead of sent (check console logs)
