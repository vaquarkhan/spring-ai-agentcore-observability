# Spring Boot and OpenTelemetry

## Official documentation

- [Spring Boot reference - Observability](https://docs.spring.io/spring-boot/reference/actuator/observability.html) - Actuator, Micrometer, tracing overview.
- [Spring blog - OpenTelemetry with Spring Boot](https://spring.io/blog/2025/11/18/opentelemetry-with-spring-boot) - When to use the Java agent vs starters (Boot 3.x / 4.x context).
- [OpenTelemetry - Spring Boot starter](https://opentelemetry.io/docs/zero-code/java/spring-boot-starter/getting-started) - BOM import, `opentelemetry-spring-boot-starter`, GraalVM-friendly path.
- [OpenTelemetry - SDK configuration (programmatic)](https://opentelemetry.io/docs/zero-code/java/spring-boot-starter/sdk-configuration/) - `AutoConfigurationCustomizerProvider`, `addSpanExporterCustomizer`, samplers.

## Used in this project

This MVP uses **`io.opentelemetry.instrumentation:opentelemetry-spring-boot-starter`** with the **OpenTelemetry instrumentation BOM** so SDK versions stay aligned with the starter.

Custom behavior:

- **`AutoConfigurationCustomizerProvider`** - registers a **span exporter wrapper** (`PiiMaskingSpanExporter`) via `addSpanExporterCustomizer` so spans are redacted before export.
- **Tracer / Meter** - obtained from the auto-configured `OpenTelemetry` bean for GenAI spans and `gen_ai.client.token.usage` histograms.

## Environment variables (common)

| Variable | Role |
|----------|------|
| `OTEL_SERVICE_NAME` / `otel.service.name` | Logical service name in the resource. |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | OTLP endpoint for traces/metrics (when not using test overrides). |
| `OTEL_TRACES_EXPORTER` | e.g. `otlp`, `logging`, `none` - tests may set `logging` so an exporter exists for customizers. |

See also: [Configure the SDK - Java](https://opentelemetry.io/docs/languages/java/configuration/).

---

**Author:** Vaqur Khan
