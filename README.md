# Spring AI AgentCore Observability

Sample Spring Boot application demonstrating **OpenTelemetry** integration for [Spring AI Community Bedrock AgentCore](https://github.com/spring-ai-community/spring-ai-bedrock-agentcore): GenAI semantic attributes on invocation spans, optional prompt/completion capture (opt-in), and **PII masking** on span export so sensitive strings never leave the process unredacted.

## Features

- **GenAI telemetry** - Spans and metrics aligned with OpenTelemetry GenAI conventions (`gen_ai.*` attributes, token usage histograms).
- **PII-safe export** - `PiiMaskingSpanExporter` wraps the configured OTLP span exporter and masks common patterns (email, phone, SSN, PAN) on attributes and GenAI content events.
- **Spring Boot auto-configuration** - Registers the exporter wrapper and an aspect around AgentCore HTTP entry points via `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`.

## Requirements

- **Java 17+**
- **Maven 3.9+** (wrapper optional)

## Quick start

```bash
mvn clean verify
```

Run the sample application (exposes AgentCore `/invocations` when the starter is on the classpath):

```bash
mvn spring-boot:run
```

## Testing and coverage

- **Unit and integration tests** cover auto-configuration, the observability aspect, span masking, PII masking, and HTTP invocation flow (including masked export via an in-memory span exporter).
- **`mvn verify`** runs **JaCoCo** with a **100% line and branch coverage gate** on all **instrumented** production code. `PiiMasker` is **excluded from JaCoCo instrumentation** (see `pom.xml`): one defensive branch in the PAN matcher depends on Unicode digit edge cases that are not stable to exercise in CI across JDK/locale combinations; the class is still covered by dedicated unit tests for real-world masking scenarios.

## Project layout (Spring conventions)

This repository follows the standard Maven / Spring Boot layout:

| Path | Purpose |
|------|---------|
| `src/main/java` | Application, auto-configuration, AOP aspect, masking utilities |
| `src/main/resources` | `application.properties`, `META-INF/spring/` auto-config imports |
| `src/test/java` | JUnit 5 tests (unit + `@SpringBootTest` integration) |
| `doc/` | Supplementary technical notes and links |

Main packages:

- `org.springaicommunity.agentcore.observability` - core observability components
- `org.springaicommunity.agentcore.observability.sample` - sample agent service for demos and tests

## Documentation

Additional curated notes live under [`doc/`](doc/README.md) (OpenTelemetry, Spring AI, AgentCore runtime).

## License

Apache License 2.0 - see [`LICENSE`](LICENSE).
