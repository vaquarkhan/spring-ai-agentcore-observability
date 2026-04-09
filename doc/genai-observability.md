# GenAI observability (OpenTelemetry)

## Semantic conventions

- [Generative AI — OpenTelemetry](https://opentelemetry.io/docs/specs/semconv/gen-ai/) — overview and naming.
- [Gen AI spans](https://opentelemetry.io/docs/specs/semconv/gen-ai/gen-ai-spans/) — span names and attributes.
- [Gen AI metrics](https://opentelemetry.io/docs/specs/semconv/gen-ai/gen-ai-metrics/) — includes client token usage patterns.
- [Gen AI events](https://opentelemetry.io/docs/specs/semconv/gen-ai/gen-ai-events/) — optional prompt/completion capture.
- [Handling sensitive data](https://opentelemetry.io/docs/specs/semconv/general/trace/#handling-sensitive-data) — data minimization expectations.

## Stability opt-in

Some distributions honor:

- `OTEL_SEMCONV_STABILITY_OPT_IN=gen_ai_latest_experimental`

for newer GenAI registry attributes. Behavior depends on your OpenTelemetry SDK and exporter version.

## This project’s mapping

Implementation constants live in `GenAiTelemetrySupport` (e.g. `gen_ai.provider.name` = `aws.bedrock`, usage attributes, histogram name `gen_ai.client.token.usage`).

Optional content capture is gated by **`OTEL_GENAI_CAPTURE_CONTENT=true`**. The **`PiiMaskingSpanExporter`** applies regex redaction **before** export; do not rely on the collector alone for compliance.

## Testing

Integration tests use **`InMemorySpanExporter`** (`opentelemetry-sdk-testing`) to assert spans without a live OTLP endpoint. See `AgentCoreObservabilityIntegrationTest`.
