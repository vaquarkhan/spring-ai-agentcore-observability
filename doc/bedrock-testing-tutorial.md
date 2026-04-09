# Testing with Amazon Bedrock and proving observability

This guide has two parts:

1. **Validate the MVP today** - The bundled `SampleBedrockAgentService` returns a **synthetic** `ChatResponse` (no AWS API calls). You can still prove AgentCore endpoints, GenAI span attributes, and PII masking end-to-end **without** Bedrock charges.
2. **Optional: call real Bedrock** - Add Spring AI's Bedrock client, AWS credentials, and swap the sample agent to use `ChatModel` so `/invocations` drives a live model.

---

## Prerequisites

| Item | Local / synthetic test | Real Bedrock test |
|------|------------------------|-------------------|
| Java 17+, Maven | Required | Required |
| AWS account | No | Yes |
| Bedrock model access in console | No | Yes (e.g. Claude Haiku) |
| IAM credentials on the machine | No | Yes (`aws configure`, env vars, or IAM role) |

---

## Part 1 - Prove it works without calling Bedrock (recommended first)

The sample service **does not** invoke `BedrockRuntime`; it builds a fake `ChatResponse` with realistic metadata so the observability aspect and PII masking behave like a real flow.

### 1.1 Automated checks

From the repository root:

```bash
mvn clean verify
```

Expect **`BUILD SUCCESS`**, all tests green, and JaCoCo checks passing. This already exercises `POST /invocations` in `AgentCoreObservabilityIntegrationTest` and asserts GenAI attributes plus masked PII in exported spans.

### 1.2 Manual HTTP check

Start the app:

```bash
mvn spring-boot:run
```

In another terminal (PowerShell example; use `curl.exe` on Windows if `curl` is an alias):

```bash
curl.exe -s -o NUL -w "HTTP %{http_code}\n" -X POST "http://localhost:8080/invocations" -H "Content-Type: text/plain" --data "Hello from manual test"
```

Expect **`HTTP 200`**. Optional health check (AgentCore contract):

```bash
curl.exe -s -o NUL -w "HTTP %{http_code}\n" "http://localhost:8080/ping"
```

**What this proves:** The AgentCore starter wires `/invocations` and your `@AgentCoreInvocation` method; the response body reflects the synthetic echo from `SampleBedrockAgentService`.

### 1.3 See traces in logs (optional)

With the **logging** trace exporter you can watch spans in the console. Example `application.properties` override (or JVM system properties):

```properties
otel.traces.exporter=logging
otel.metrics.exporter=none
```

Restart the app and call `/invocations` again. Look for span output that includes `gen_ai.*` attributes when content capture is enabled (see below).

### 1.4 Optional: GenAI content events (prompt/completion in spans)

Only when safe for your environment (may contain sensitive text until masked at export):

```properties
OTEL_GENAI_CAPTURE_CONTENT=true
```

The `PiiMaskingSpanExporter` still redacts patterns (email, phone, etc.) before export. Do **not** enable this in production without policy review.

---

## Part 2 - Call real Amazon Bedrock (optional)

To **prove** a **live** Bedrock response flows through the same `ChatResponse` path (and thus the same observability aspect), you need a Spring-managed `ChatModel` that calls Bedrock.

### 2.1 AWS setup

1. In **Amazon Bedrock**  ->  **Model access**, request access to a model you will use (for example **Anthropic Claude 3 Haiku**).
2. Ensure an IAM principal can call Bedrock in your chosen **Region**, e.g. `bedrock:InvokeModel` / Converse API actions per [AWS least-privilege guidance](https://docs.aws.amazon.com/bedrock/latest/userguide/security_iam_id-based-policy-examples.html).
3. Configure credentials on the machine running the app (one of):
   - `aws configure` (writes `~/.aws/credentials`)
   - Environment variables: `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`, `AWS_REGION` (or `AWS_DEFAULT_REGION`)
   - Instance profile / IRSA when deployed on AWS

### 2.2 Add Spring AI Bedrock (Converse API)

Use the Spring AI BOM already in this project and add the Bedrock Converse starter (version managed by the BOM):

```xml
<dependency>
  <groupId>org.springframework.ai</groupId>
  <artifactId>spring-ai-starter-model-bedrock-converse</artifactId>
</dependency>
```

See also: [Spring AI - Amazon Bedrock](https://docs.spring.io/spring-ai/reference/1.0/api/bedrock-chat.html).

### 2.3 Minimal configuration

Example `application.properties` (adjust region and model id to match your account):

```properties
spring.ai.bedrock.aws.region=us-east-1
# Example: set default model id for Converse - exact property names may vary by Spring AI version; check current docs.
spring.ai.bedrock.converse.chat.options.model=anthropic.claude-3-haiku-20240307-v1:0
```

Enable the chat model if your Spring AI version requires an explicit flag (check the reference for your BOM version).

### 2.4 Replace the synthetic sample with a Bedrock-backed agent

Conceptually, inject `ChatModel` and return its `ChatResponse` from your `@AgentCoreInvocation` method, for example:

```java
@Service
public class LiveBedrockAgentService {

  private final ChatModel chatModel;

  public LiveBedrockAgentService(ChatModel chatModel) {
    this.chatModel = chatModel;
  }

  @AgentCoreInvocation
  public ChatResponse invoke(String prompt) {
    return chatModel.call(new Prompt(new UserMessage(prompt)));
  }
}
```

Remove or `@Profile`-gate `SampleBedrockAgentService` so only **one** `@AgentCoreInvocation` method handles the contract (exact registration rules follow the AgentCore starter).

After this change:

1. Run `mvn spring-boot:run` with valid AWS credentials.
2. `POST /invocations` with a text body should return **real model output** (latency and token usage will reflect Bedrock).
3. Observability: the same aspect still wraps the controller; spans should show GenAI attributes derived from the **real** `ChatResponse` metadata.

### 2.5 Cost and safety

- Every successful invocation **incurs Bedrock usage**; monitor in **AWS Billing** and **CloudWatch**.
- Do not commit access keys. Use IAM roles, short-lived credentials, or Secrets Manager in real deployments.

---

## Part 3 - What "working" means for this MVP

| Check | Without Bedrock | With Bedrock |
|-------|-----------------|--------------|
| `mvn verify` passes | Yes | Yes (add Bedrock dep + tests that mock or skip live AWS) |
| `POST /invocations` returns 200 | Yes (echo + synthetic metadata) | Yes (real model text) |
| Spans include `gen_ai.provider.name` = `aws.bedrock` | Yes (aspect sets convention) | Yes |
| PII masked in exported spans | Yes (integration test + `PiiMaskingSpanExporter`) | Yes (same exporter) |

---

## Troubleshooting

| Symptom | What to check |
|---------|----------------|
| `403` / `AccessDeniedException` from AWS | IAM permissions, correct region, model access enabled in Bedrock console |
| `/invocations` 404 or not mapped | AgentCore starter on classpath; app class scans `org.springaicommunity.agentcore` packages |
| No GenAI attributes on spans | Aspect targets `AgentCoreInvocationsController`; response must be a `ChatResponse` from the controller path |
| Tests fail after adding Bedrock | Keep Bedrock out of unit tests: use `@MockBean ChatModel` or a `test` profile without AWS |

---

## Related docs

- [AgentCore runtime contract](agentcore-runtime.md)
- [GenAI observability](genai-observability.md)
- [Spring Boot + OpenTelemetry](spring-boot-and-otel.md)
- [Project README](../README.md)
