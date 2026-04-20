# Spring AI AgentCore Observability

Spring Boot starter library providing **OpenTelemetry** integration for [Spring AI Community AgentCore](https://github.com/spring-ai-community/spring-ai-agentcore): GenAI semantic attributes on invocation spans, optional prompt/completion capture (opt-in), **request correlation** (AgentCore session and AWS request IDs), **reactive** (`Mono` / `Flux`) handling for streaming-style responses, and **PII masking** on span export so sensitive strings never leave the process unredacted.

## Features

- **GenAI telemetry** — Spans and metrics aligned with OpenTelemetry GenAI conventions (`gen_ai.*` attributes, token usage histograms).
- **AgentCore / AWS correlation** — When inbound HTTP headers are present (`x-amzn-bedrock-agentcore-session-id`, `x-amzn-request-id` and legacy aliases), values are copied onto spans as `aws.bedrock.agentcore.session_id` and `aws.request_id`. Resolution uses servlet `HttpServletRequest`, WebFlux `ServerWebExchange`, or `RequestContextHolder` when those APIs are on the classpath (`spring-web` / `spring-webflux` are **optional** Maven dependencies so pure synchronous stacks are not forced to pull both).
- **Structured configuration** — `spring.ai.agentcore.observability.*` via `AgentCoreObservabilityProperties`: enable/disable masking globally, toggle categories (email, SSN, PAN, phone), and optional **custom regex** patterns for extra redaction. Prompt/completion capture respects `spring.ai.agentcore.observability.capture-content` and the legacy **`OTEL_GENAI_CAPTURE_CONTENT`** environment variable when the Spring property is unset.
- **PII-safe export** — `PiiMaskingSpanExporter` wraps the configured OTLP span exporter. PAN-like runs are validated with **Luhn** and **issuer-style prefix** checks before masking; phone patterns cover common US formats (hyphen, dot, parentheses, `+1`).
- **Error classification** — Span `error.type` is mapped toward OTel-friendly categories where possible (e.g. `rate_limit`, `invalid_request`, `timeout`, `authentication_failure`, `server_error`).
- **Spring Boot auto-configuration** — Registers the exporter wrapper and an aspect around AgentCore HTTP entry points via `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`. The default `PiiMasker` bean is registered only if you do not define your own (`@ConditionalOnMissingBean`).

## Requirements

- **Java 17+**
- **Maven 3.9+** (wrapper optional)

## Quick start

```bash
mvn clean verify
```

This compiles the library, runs all tests (unit + integration), runs **Spring Java Format** validation, and enforces JaCoCo coverage gates (see below).

### Git hooks (optional)

To enforce a `commit-msg` hook that removes stray toolchain footer lines from commit messages, point Git at the tracked hooks directory once per clone:

```bash
git config core.hooksPath .githooks
```

## Testing and coverage

- **Unit and integration tests** cover auto-configuration, the observability aspect, span masking, PII masking, and HTTP invocation flow (including masked export via an in-memory span exporter).
- **Optional live AWS Bedrock tests** (`RealBedrockIntegrationTest`) call the real Converse API via `ChatModel`. They are **disabled by default**; enable with `RUN_REAL_BEDROCK_TESTS=true` plus valid AWS credentials (see `doc/bedrock-testing-tutorial.md`). Assertions use **positive token counts** (not fixed literals) because usage varies by model and prompt.
- **`mvn verify`** runs **JaCoCo** with bundle minimums of **96.5% line** and **83% branch** coverage on instrumented production code (see `pom.xml`). Raise these toward 100% as tests expand.

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

**Author:** Vaquar Khan

**Overview of Features:**

## 1. Core GenAI Telemetry (15 features)

| # | Feature | Implementation |
|---|---|---|
| 1 | OTel GenAI span attribute: `gen_ai.operation.name` | `AgentCoreInvocationObservabilityAspect.applyGenAiAttributes` |
| 2 | OTel GenAI span attribute: `gen_ai.provider.name` (= `aws.bedrock`) | Same |
| 3 | OTel GenAI span attribute: `gen_ai.system` (= `aws.bedrock`) | Same |
| 4 | OTel GenAI span attribute: `gen_ai.request.model` | From `ChatResponseMetadata.getModel()` |
| 5 | OTel GenAI span attribute: `gen_ai.response.model` | From `ChatResponseMetadata.getModel()` |
| 6 | OTel GenAI span attribute: `gen_ai.usage.input_tokens` | From `Usage.getPromptTokens()` |
| 7 | OTel GenAI span attribute: `gen_ai.usage.output_tokens` | From `Usage.getCompletionTokens()` |
| 8 | OTel GenAI span attribute: `gen_ai.response.finish_reasons` | Deduplicated across `Generation.getMetadata().getFinishReason()` |
| 9 | Token usage histogram metric: `gen_ai.client.token.usage` | With `gen_ai.token.type` (input/output), model, provider attributes |
| 10 | `chat` vs `execute_tool` span name discrimination | Based on `ChatResponse.hasToolCalls()` |
| 11 | Cache-read token handling | `cacheReadInputTokens` from metadata added to input count |
| 12 | AgentCore session ID correlation | `aws.bedrock.agentcore.session_id` from `x-amzn-bedrock-agentcore-session-id` |
| 13 | AWS request ID correlation | `aws.request_id` from `x-amzn-request-id` (with `x-amzn-requestid` fallback for proxies that strip the dash) |
| 14 | Multi-source header extraction | Servlet `HttpServletRequest`, WebFlux `ServerWebExchange`, Spring `RequestContextHolder` fallback |
| 15 | OTel-aligned error classification | 5 categories: `rate_limit`, `invalid_request`, `timeout`, `authentication_failure`, `server_error` |

## 2. Reactive / Streaming Support (5 features)

| # | Feature | Implementation |
|---|---|---|
| 16 | `Mono<ChatResponse>` instrumentation | `instrumentMono` with `doOnSuccess`, `doOnError`, `doFinally` |
| 17 | `Flux<ChatResponse>` instrumentation | `instrumentFlux` with `doOnNext`, `doOnComplete`, `doOnError`, `doFinally` |
| 18 | Deferred span end for async returns | `AtomicBoolean deferredEnd` guard - span ends on publisher terminal signal |
| 19 | No span leaks on cancellation | `doFinally(sig -> span.end())` catches all terminal signals including cancel |
| 20 | Sync `ChatResponse` path also supported | Fast-path in `aroundAgentCoreController` for non-reactive responses |

## 3. Content Capture (4 features)

| # | Feature | Implementation |
|---|---|---|
| 21 | Opt-in prompt capture | Emits `gen_ai.content.prompt` span event with `gen_ai.prompt` attribute |
| 22 | Opt-in completion capture | Emits `gen_ai.content.completion` span event with `gen_ai.completion` attribute |
| 23 | Three-source capture-content resolution | Spring property → `OTEL_GENAI_CAPTURE_CONTENT` legacy env var → default |
| 24 | Safety for tool-only generations | Try/catch on `getResult()` avoids failing aspect when text content missing |

## 4. PII Masking - Patterns (9 features)

| # | Feature | Implementation |
|---|---|---|
| 25 | SSN masking | `\b\d{3}-\d{2}-\d{4}\b` → `###-##-####` |
| 26 | Credit card masking with Luhn check | `passesLuhn(String digits)` validates modulus-10 before masking |
| 27 | Credit card issuer-prefix validation | Visa (4), MasterCard (5[1-5] + 2221-2720), Amex (3[47]), Discover (6011, 65, 6440-6499), UnionPay (622126-622925), JCB |
| 28 | Credit card format preservation | `first4-****-****-last4` format for diagnostic value without exposing PAN |
| 29 | Email masking | Preserves first char + `@***.` + TLD - `john.doe@example.com` → `j***@***.com` |
| 30 | Phone masking - `+1` format | `+1 (555) 555-5555`, `+1-555-555-5555`, `+1.555.555.5555` |
| 31 | Phone masking - parentheses format | `(555) 555-5555`, `(555)555-5555` |
| 32 | Phone masking - dot format | `555.555.5555` |
| 33 | Phone masking - dash format | `555-555-5555` |

## 5. PII Masking -Configuration (7 features)

| # | Feature | Implementation |
|---|---|---|
| 34 | Global masking enable/disable | `spring.ai.agentcore.observability.masking.enabled` |
| 35 | Per-category toggle - email | `spring.ai.agentcore.observability.masking.mask-email` |
| 36 | Per-category toggle - SSN | `spring.ai.agentcore.observability.masking.mask-ssn` |
| 37 | Per-category toggle - credit card | `spring.ai.agentcore.observability.masking.mask-credit-card` |
| 38 | Per-category toggle - phone | `spring.ai.agentcore.observability.masking.mask-phone` |
| 39 | Custom regex patterns | `spring.ai.agentcore.observability.masking.custom-regex[]` - user-supplied redaction rules |
| 40 | Compile-once pattern strategy | All patterns compiled at `PiiMaskingSettings` construction, reused for every span |

## 6. Span Export Pipeline (6 features)

| # | Feature | Implementation |
|---|---|---|
| 41 | Span exporter wrapper | `PiiMaskingSpanExporter` decorates the configured OTLP exporter |
| 42 | Non-intrusive injection | Via `AutoConfigurationCustomizerProvider.addSpanExporterCustomizer` - OTel SDK's official extension point |
| 43 | Lazy masking | `MaskingSpanData` masks only when `getAttributes()`/`getEvents()` is called |
| 44 | All string span attributes masked | Auto-applied to every non-null string attribute |
| 45 | Span event attribute masking | Targeted rules for `gen_ai.content.*` events + heuristic matching on `prompt`/`completion`/`password` keys |
| 46 | Proper delegation of exporter lifecycle | `flush()` and `shutdown()` forwarded to the wrapped exporter |

## 7. Configuration Surface (5 features)

| # | Feature | Implementation |
|---|---|---|
| 47 | `@ConfigurationProperties(prefix = "spring.ai.agentcore.observability")` | `AgentCoreObservabilityProperties` |
| 48 | `@NestedConfigurationProperty` for masking block | Clean property paths (`.masking.enabled` vs flat naming) |
| 49 | Legacy env-var compatibility | `OTEL_GENAI_CAPTURE_CONTENT` still honored for existing deployments |
| 50 | Defensive null handling in setters | `setCustomRegex(null)` safely becomes empty list |
| 51 | Spring Boot configuration-processor integration | IDE autocomplete and metadata generation enabled |

## 8. Auto-Configuration (4 features)

| # | Feature | Implementation |
|---|---|---|
| 52 | `@AutoConfiguration` class | `AgentCoreObservabilityAutoConfiguration` |
| 53 | `@ConditionalOnClass(AgentCoreInvocation.class)` | Only activates when AgentCore runtime-starter is on classpath |
| 54 | Separate replaceable Tracer + Meter beans | `agentCoreObservabilityTracer`, `agentCoreObservabilityMeter` - override via standard Spring bean replacement |
| 55 | `AutoConfiguration.imports` registration | `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` |

## 9. Testing (13 features)

| # | Feature | Implementation |
|---|---|---|
| 56 | Full Spring context integration test | `AgentCoreObservabilityIntegrationTest` with in-memory OTel exporter |
| 57 | Configuration binding test | `AgentCoreObservabilityPropertiesTest` - all property paths validated |
| 58 | PII masker pattern coverage | `PiiMaskerTest` - covers every pattern + Luhn edge cases |
| 59 | Settings resolution test | `PiiMaskingSettingsTest` - null/defaults/custom paths |
| 60 | Exporter delegation test | `PiiMaskingSpanExporterTest` - verifies flush/shutdown pass-through |
| 61 | Lazy masking test | `MaskingSpanDataTest` - masking happens only on access |
| 62 | Aspect behavior test | `AgentCoreInvocationObservabilityAspectTest` - sync + async (Mono/Flux) paths |
| 63 | GenAI constants stability test | `GenAiTelemetrySupportTest` - guards against accidental key renames |
| 64 | Live Bedrock integration test | `RealBedrockIntegrationTest` - gated behind `RUN_REAL_BEDROCK_TESTS=true` |
| 65 | Environment-gated test condition | `RealBedrockConditions` - custom JUnit 5 `ExecutionCondition` |
| 66 | In-memory span exporter test config | `OtelInMemorySpanExporterTestConfig` - reusable across tests |
| 67 | Sample agent service | `SampleBedrockAgentService` - reference implementation |
| 68 | End-to-end sample app | `AgentCoreObservabilitySampleApplication` + test |

## 10. Build & Quality (4 features)

| # | Feature | Implementation |
|---|---|---|
| 69 | Spring Java Format plugin | Enforced at validate phase - all files tab-indented per Spring conventions |
| 70 | JaCoCo coverage gate | 97% line, 84% branch minimums -build fails below threshold |
| 71 | BOM-aligned dependency management | Spring AI BOM (1.1.2), AWS SDK BOM (2.40.3), OTel Instrumentation BOM (2.14.0) |
| 72 | Monorepo migration-ready POM | Parent POM comment documents exactly what to change when merging into `spring-ai-agentcore` |

## 11. Documentation (5 features)

| # | Feature | Location |
|---|---|---|
| 73 | GenAI OTel semconv mapping reference | `doc/genai-observability.md` |
| 74 | AgentCore contract notes | `doc/agentcore-runtime.md` |
| 75 | Live Bedrock test guide | `doc/bedrock-testing-tutorial.md` |
| 76 | Spring AI observability cross-reference | `doc/spring-ai-reference.md` |
| 77 | Spring Boot + OTel integration notes | `doc/spring-boot-and-otel.md` |

## 12. Extension Points (3 features)

| # | Feature | How to Use |
|---|---|---|
| 78 | Replaceable `PiiMasker` bean | Override with `@Primary` bean of same type; custom masking logic auto-wired into the exporter |
| 79 | Replaceable `PiiMaskingSettings` | Construct `PiiMasker(PiiMaskingSettings)` with custom settings bean |
| 80 | Replaceable Tracer/Meter beans | Standard Spring bean replacement picks up your custom OTel instruments |

---

## Summary

**80 features across 12 categories already implemented in Phase 1.**

The implementation is complete and production-ready.

Planning to add  Phase 2 features are:

- Per-tool instrumentation (Browser, CodeInterpreter, Memory repository)
- OTel Logs bridge for prompt/completion
- Pre-built dashboards (Grafana, Datadog, New Relic)
- Cost metric + cache hit ratio derived metrics
- SPI for custom span enrichers
- Sampling hints for GenAI spans
