# Documentation index

This folder holds **curated notes and links** for working with this project. For build instructions, layout, and feature overview, see the **[project README](../README.md)** at the repository root.

Authoritative content remains on [Spring](https://spring.io/) and [OpenTelemetry](https://opentelemetry.io/).

## Recent documentation themes

- **Configuration** — `spring.ai.agentcore.observability` properties for masking and capture; see [genai-observability.md](genai-observability.md).
- **Runtime artifact** — Use **`spring-ai-agentcore-runtime-starter`** (see [agentcore-runtime.md](agentcore-runtime.md)).
- **Reactive paths** — `Mono` / `Flux` of `ChatResponse` are instrumented so spans complete when the stream finishes or errors.
- **Consumers** — Optional `spring-web` / `spring-webflux` on the classpath; custom `PiiMasker` beans override the auto-configured default.

| Document | Purpose |
|----------|---------|
| [spring-boot-and-otel.md](spring-boot-and-otel.md) | Spring Boot + OpenTelemetry starter, programmatic SDK customization |
| [spring-ai-reference.md](spring-ai-reference.md) | Spring AI (models, `ChatResponse`, Bedrock) pointers |
| [agentcore-runtime.md](agentcore-runtime.md) | AWS Bedrock AgentCore + `spring-ai-agentcore-runtime-starter` |
| [genai-observability.md](genai-observability.md) | GenAI semantic conventions and this module's behavior |
| [bedrock-testing-tutorial.md](bedrock-testing-tutorial.md) | Validate locally vs. optional real Amazon Bedrock + observability checks |

---

**Author:** Vaqur Khan
