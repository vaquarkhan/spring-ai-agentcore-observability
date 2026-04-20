# GenAI observability (OpenTelemetry)

## Semantic conventions

- [Generative AI - OpenTelemetry](https://opentelemetry.io/docs/specs/semconv/gen-ai/) — overview and naming.
- [Gen AI spans](https://opentelemetry.io/docs/specs/semconv/gen-ai/gen-ai-spans/) — span names and attributes.
- [Gen AI metrics](https://opentelemetry.io/docs/specs/semconv/gen-ai/gen-ai-metrics/) — includes client token usage patterns.
- [Gen AI events](https://opentelemetry.io/docs/specs/semconv/gen-ai/gen-ai-events/) — optional prompt/completion capture.
- [Handling sensitive data](https://opentelemetry.io/docs/specs/semconv/general/trace/#handling-sensitive-data) — data minimization expectations.

## Stability opt-in

Some distributions honor:

- `OTEL_SEMCONV_STABILITY_OPT_IN=gen_ai_latest_experimental`

for newer GenAI registry attributes. Behavior depends on your OpenTelemetry SDK and exporter version.

## This project's mapping

Implementation constants live in `GenAiTelemetrySupport` (e.g. `gen_ai.provider.name` = `aws.bedrock`, usage attributes, histogram name `gen_ai.client.token.usage`).

**Correlation attributes** (when HTTP headers are available on the AgentCore request):

- `aws.bedrock.agentcore.session_id` — from `x-amzn-bedrock-agentcore-session-id`
- `aws.request_id` — from `x-amzn-request-id` (standard AWS spelling; also matches `x-amzn-requestid` / `X-Amzn-RequestId` when present)

Header resolution does not require a hard compile dependency on both Spring MVC and Spring WebFlux: the library keeps `spring-web` and `spring-webflux` **optional** and resolves headers reflectively when those types are present at runtime.

**Model attributes** (`gen_ai.request.model` / `gen_ai.response.model`): taken from `ChatResponse` metadata when set. If the provider omits the model (some Bedrock Converse paths), the aspect falls back to `spring.ai.bedrock.converse.chat.options.model`, then `spring.ai.openai.chat.options.model`, so spans still carry a stable model id when configured.

**Optional content capture** is resolved once at aspect construction from, in order:

1. `spring.ai.agentcore.observability.capture-content` (if set in the environment), else  
2. `OTEL_GENAI_CAPTURE_CONTENT`, else  
3. the default in `AgentCoreObservabilityProperties`.

The **`PiiMaskingSpanExporter`** applies redaction **before** export (including on GenAI content events). Do not rely on the collector alone for compliance.

**Reactive:** If the controller returns `Mono<ChatResponse>` or `Flux<ChatResponse>`, the aspect records GenAI attributes when each `ChatResponse` is emitted and ends the span when the publisher terminates (success or error).

**Errors:** `error.type` on spans uses normalized categories (e.g. `rate_limit`, `invalid_request`, `timeout`, `server_error`) where the throwable maps cleanly to AWS-style or common failure modes.

## Testing

Integration tests use **`InMemorySpanExporter`** (`opentelemetry-sdk-testing`) to assert spans without a live OTLP endpoint. See `AgentCoreObservabilityIntegrationTest`.

---

**Author:** Vaqur Khan
