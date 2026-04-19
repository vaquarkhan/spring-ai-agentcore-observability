# Spring AI AgentCore Observability

Spring Boot starter library providing **OpenTelemetry** integration for [Spring AI Community AgentCore](https://github.com/spring-ai-community/spring-ai-agentcore): GenAI semantic attributes on invocation spans, optional prompt/completion capture (opt-in), **request correlation** (AgentCore session and AWS request IDs), **reactive** (`Mono` / `Flux`) handling for streaming-style responses, and **PII masking** on span export so sensitive strings never leave the process unredacted.

## Features

- **GenAI telemetry** — Spans and metrics aligned with OpenTelemetry GenAI conventions (`gen_ai.*` attributes, token usage histograms).
- **AgentCore / AWS correlation** — When inbound HTTP headers are present (`x-amzn-bedrock-agentcore-session-id`, `x-amzn-requestid`), values are copied onto spans as `aws.bedrock.agentcore.session_id` and `aws.request_id` (from servlet request, `ServerWebExchange`, or `RequestContextHolder`).
- **Structured configuration** — `spring.ai.agentcore.observability.*` via `AgentCoreObservabilityProperties`: enable/disable masking globally, toggle categories (email, SSN, PAN, phone), and optional **custom regex** patterns for extra redaction. Prompt/completion capture respects `spring.ai.agentcore.observability.capture-content` and the legacy **`OTEL_GENAI_CAPTURE_CONTENT`** environment variable when the Spring property is unset.
- **PII-safe export** — `PiiMaskingSpanExporter` wraps the configured OTLP span exporter. PAN-like runs are validated with **Luhn** and **issuer-style prefix** checks before masking; phone patterns cover common US formats (hyphen, dot, parentheses, `+1`).
- **Error classification** — Span `error.type` is mapped toward OTel-friendly categories where possible (e.g. `rate_limit`, `invalid_request`, `timeout`, `authentication_failure`, `server_error`).
- **Spring Boot auto-configuration** — Registers the exporter wrapper and an aspect around AgentCore HTTP entry points via `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`.

## Requirements

- **Java 17+**
- **Maven 3.9+** (wrapper optional)

## Quick start

```bash
mvn clean verify
```

This compiles the library, runs all tests (unit + integration), runs **Spring Java Format** validation, and enforces JaCoCo coverage gates (see below).

## Testing and coverage

- **Unit and integration tests** cover auto-configuration, the observability aspect, span masking, PII masking, and HTTP invocation flow (including masked export via an in-memory span exporter).
- **Optional live AWS Bedrock tests** (`RealBedrockIntegrationTest`) call the real Converse API via `ChatModel`. They are **disabled by default**; enable with `RUN_REAL_BEDROCK_TESTS=true` plus valid AWS credentials (see `doc/bedrock-testing-tutorial.md`). Assertions use **positive token counts** (not fixed literals) because usage varies by model and prompt.
- **`mvn verify`** runs **JaCoCo** with bundle minimums of **97% line** and **84% branch** coverage on instrumented production code (see `pom.xml`). Raise these toward 100% as tests expand.

## Project layout (Spring conventions)

This repository follows the standard Maven / Spring Boot starter layout:

| Path | Purpose |
|------|---------|
| `src/main/java/.../autoconfigure` | Spring Boot auto-configuration (`@AutoConfiguration`), `AgentCoreObservabilityProperties` |
| `src/main/java/.../telemetry` | GenAI telemetry constants and AOP aspect |
| `src/main/java/.../masking` | PII masking: `PiiMasker`, `PiiMaskingSettings`, `MaskingSpanData`, `PiiMaskingSpanExporter` |
| `src/main/resources` | `META-INF/spring/` auto-config imports |
| `src/test/java/.../sample` | Sample application and agent service (test-only) |
| `src/test/java` | JUnit 5 tests (unit + `@SpringBootTest` integration) |
| `doc/` | Supplementary technical notes and links |

Main packages:

- `org.springaicommunity.agentcore.observability.autoconfigure` — auto-configuration and properties
- `org.springaicommunity.agentcore.observability.telemetry` — GenAI span enrichment and semantic convention constants
- `org.springaicommunity.agentcore.observability.masking` — PII redaction on span export
- `org.springaicommunity.agentcore.observability.sample` (test scope) — sample agent service for demos and integration tests

## Monorepo note

When this module is merged into **spring-ai-agentcore**, switch the Maven **parent** to `spring-ai-agentcore-parent` (typically `relativePath` `../pom.xml`) and drop duplicate BOM/version blocks as described in the root `pom.xml` comment.

## Documentation

Additional curated notes live under [`doc/`](doc/README.md) (OpenTelemetry, Spring AI, AgentCore runtime).

## License

Apache License 2.0 — see [`LICENSE`](LICENSE).

---

**Author:** Vaqur Khan
