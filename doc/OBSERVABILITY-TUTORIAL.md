# Spring AI AgentCore Observability — Complete Tutorial

> A no-assumptions, step-by-step guide to making your Spring AI + Amazon Bedrock agent **observable**, **compliant**, and **debuggable** in production using the `spring-ai-agentcore-observability` Spring Boot starter.

---

## Why do you need this?

If you are running a Spring AI agent on Amazon Bedrock in production, you will run into five hard problems sooner or later:

1. **"How many tokens am I burning per request?"**
   Bedrock bills per token. Without telemetry you can't answer cost questions, can't alert on runaway prompts, can't compare models.

2. **"Which Bedrock call produced this bad answer?"**
   When a customer complains, you need to correlate their session back to a specific Bedrock invocation — and onwards to AWS-side logs (CloudTrail, CloudWatch, Bedrock model invocation logging).

3. **"Did my LLM just leak PII into our APM / OTel collector?"**
   Prompts and completions often contain emails, SSNs, credit cards, phone numbers. If those hit a third-party observability backend unredacted, you have a compliance problem (GDPR, HIPAA, PCI).

4. **"Why is this request slow / failing?"**
   You need spans with error classification (`rate_limit`, `timeout`, `authentication_failure`, `invalid_request`, `server_error`) to tell a throttling incident apart from a code bug in Grafana / Datadog / New Relic.

5. **"I don't want to hand-instrument every agent."**
   Hand-written tracing code drifts, gets skipped in new services, and doesn't cover reactive streaming paths correctly.

`spring-ai-agentcore-observability` solves all five in one drop-in starter. You add a dependency, set two properties, and every AgentCore HTTP invocation comes with:

- OpenTelemetry GenAI spans (provider, model, operation, tokens, finish reasons)
- Token-usage metrics (histogram, attributed by model)
- AWS session ID + request ID on every span for cross-system correlation
- PII redacted before spans leave the process
- Correct behaviour for both synchronous `ChatResponse` and reactive `Mono` / `Flux` streams
- Zero manual instrumentation code

---

## What this tutorial covers

By the end, you will have a working Bedrock-backed Spring Boot agent that produces fully enriched OpenTelemetry traces with PII redacted on export. Specifically, we cover:

| # | Topic | You will learn |
|---|-------|----------------|
| 1 | Architecture overview | How the pieces fit together (diagrams) |
| 2 | Prerequisites | JDK, Maven, AWS setup |
| 3 | Project setup | `pom.xml`, dependencies, `application.properties` |
| 4 | Write the agent | The one class you write: `@AgentCoreInvocation` |
| 5 | Run against live Bedrock | `curl` the endpoint, watch spans appear |
| 6 | Span attributes reference | Every key, every meaning, every source |
| 7 | Configuration reference | Every `spring.ai.agentcore.observability.*` property |
| 8 | PII masking deep dive | How each pattern works, Luhn/issuer rules, custom patterns |
| 9 | AWS request correlation | Session IDs, request IDs, cross-stack header lookup |
| 10 | Exporting to OTLP | Send to a real collector (Jaeger, Datadog, Grafana, X-Ray) |
| 11 | Testing | Assert on spans with an in-memory exporter |
| 12 | Troubleshooting | The exact problems we hit during validation and how to fix them |
| 13 | FAQ | Common questions |

---

## 1. Architecture overview

### High-level flow

**Figure 1 — End-to-end request flow: from HTTP client to observability backend.**

```mermaid
flowchart LR
    Client[HTTP Client<br/>curl / UI / agent] -->|POST /invocations<br/>+ headers + prompt| Controller[AgentCore<br/>InvocationsController]
    Controller -.intercepted by.-> Aspect[AgentCoreInvocation<br/>ObservabilityAspect]
    Controller --> Service["@AgentCoreInvocation<br/>handle(String prompt)"]
    Service --> ChatModel[Spring AI<br/>ChatModel]
    ChatModel -->|Converse API| Bedrock[(Amazon<br/>Bedrock)]
    Bedrock -->|tokens + text| ChatModel
    ChatModel --> Service
    Service --> Aspect
    Aspect --> OtelSdk[OpenTelemetry<br/>SDK]
    OtelSdk --> Masker[PiiMasking<br/>SpanExporter]
    Masker --> Backend[Logging / OTLP<br/>collector / Jaeger /<br/>CloudWatch / Datadog]
```

**Reading the diagram in plain English.** A client (curl, a web UI, another agent) sends `POST /invocations` with a prompt and a couple of AWS correlation headers. The AgentCore HTTP controller picks it up, and the observability aspect (the dotted line) transparently wraps the call — you don't write code for this, it's AOP. The controller dispatches to your `@AgentCoreInvocation` method, which calls Spring AI's `ChatModel`, which calls Amazon Bedrock over the Converse API. Bedrock returns the generated text and token counts, they flow back up the same path, and the aspect enriches the span with everything it just saw. Finally the OpenTelemetry SDK hands the span to `PiiMaskingSpanExporter`, which scrubs sensitive strings before the span reaches your real backend (stdout in dev, OTLP collector in prod). Every box in the middle is code the starter owns — you only own the two ends.

### What the aspect actually does

**Figure 2 — Sequence diagram of a single invocation from HTTP in to masked span out.**

```mermaid
sequenceDiagram
    autonumber
    participant HTTP as HTTP Request
    participant Ctrl as AgentCoreInvocationsController
    participant Asp as Observability Aspect
    participant Svc as Your @AgentCoreInvocation bean
    participant Bed as Bedrock (via Spring AI)
    participant Exp as PiiMaskingSpanExporter
    participant Out as OTLP / Logging exporter

    HTTP->>Ctrl: POST /invocations<br/>x-amzn-bedrock-agentcore-session-id<br/>x-amzn-request-id<br/>prompt body
    Ctrl->>Asp: AOP proxy before invoke
    Asp->>Asp: start span "chat"<br/>copy headers onto span<br/>(aws.bedrock.agentcore.session_id,<br/>aws.request_id)
    Asp->>Svc: proceed(prompt)
    Svc->>Bed: chatModel.call(...)
    Bed-->>Svc: ChatResponse<br/>+ usage + finish reason
    Svc-->>Asp: ChatResponse
    Asp->>Asp: enrich span:<br/>gen_ai.operation.name<br/>gen_ai.provider.name<br/>gen_ai.system<br/>gen_ai.request/response.model<br/>gen_ai.usage.input_tokens<br/>gen_ai.usage.output_tokens<br/>gen_ai.response.finish_reasons
    Asp->>Asp: if capture-content=true<br/>add events:<br/>gen_ai.content.prompt<br/>gen_ai.content.completion
    Asp->>Asp: record metric<br/>gen_ai.client.token.usage
    Asp->>Asp: span.end()
    Asp->>Exp: hand span to exporter
    Exp->>Exp: mask every string<br/>(email, SSN, PAN, phone,<br/>custom regex)
    Exp->>Out: export masked span
    Asp-->>Ctrl: return ChatResponse
    Ctrl-->>HTTP: 200 OK
```

**Reading the diagram in plain English.** Time flows top to bottom. When the HTTP request lands on the controller, AOP quietly inserts the aspect in front of it. The aspect's first job is to create a new `chat` span and copy the two AWS headers onto it — that's how cross-system correlation starts. Only then does it call your actual handler, which talks to Bedrock via Spring AI. When the response comes back, the aspect reads token counts, model name, and finish reason from `ChatResponseMetadata` and writes them onto the span as `gen_ai.*` attributes. If you've opted in to content capture, it also attaches the raw prompt and completion as span events. It records the token-usage histogram metric and closes the span. The span then flows through `PiiMaskingSpanExporter`, which walks every string attribute and event attribute through the regex masker before anything hits the wire. Finally the response returns to the HTTP client. Your code sees none of this — the aspect is the one running the show.

### Key classes at a glance

**Figure 3 — Class relationships inside the observability module.**

```mermaid
classDiagram
    class AgentCoreObservabilityAutoConfiguration {
        +PiiMasker agentCorePiiMasker()
        +AutoConfigurationCustomizerProvider customizer()
        +Tracer tracer()
        +Meter meter()
        +AgentCoreInvocationObservabilityAspect aspect()
    }
    class AgentCoreObservabilityProperties {
        -boolean captureContent
        -Masking masking
        +resolveCaptureContent(Environment) boolean
    }
    class AgentCoreInvocationObservabilityAspect {
        -Tracer tracer
        -DoubleHistogram tokenUsageHistogram
        -boolean captureContent
        +aroundAgentCoreController(JoinPoint) Object
    }
    class PiiMasker {
        -PiiMaskingSettings settings
        +mask(String) String
    }
    class PiiMaskingSpanExporter {
        -SpanExporter delegate
        -PiiMasker masker
        +export(Collection~SpanData~)
    }
    class GenAiTelemetrySupport {
        <<constants>>
        +AttributeKey GEN_AI_OPERATION_NAME
        +AttributeKey GEN_AI_USAGE_INPUT_TOKENS
        +AttributeKey AWS_REQUEST_ID
        +... etc.
    }

    AgentCoreObservabilityAutoConfiguration --> AgentCoreObservabilityProperties
    AgentCoreObservabilityAutoConfiguration --> PiiMasker
    AgentCoreObservabilityAutoConfiguration --> AgentCoreInvocationObservabilityAspect
    AgentCoreObservabilityAutoConfiguration --> PiiMaskingSpanExporter
    AgentCoreInvocationObservabilityAspect --> GenAiTelemetrySupport : uses constants
    PiiMaskingSpanExporter --> PiiMasker : delegates masking
    PiiMasker --> PiiMaskingSettings
```

**Reading the diagram in plain English.** `AgentCoreObservabilityAutoConfiguration` is the Spring Boot auto-config class — it's the factory that builds every other bean when the module is on your classpath. It reads settings from `AgentCoreObservabilityProperties` (the `@ConfigurationProperties` bean that binds to `spring.ai.agentcore.observability.*`). It creates the `AgentCoreInvocationObservabilityAspect` (the AOP aspect that intercepts AgentCore controller calls), a `PiiMasker` (the regex-based redaction engine), and a `PiiMaskingSpanExporter` (the exporter wrapper that scrubs spans before export). `GenAiTelemetrySupport` is a constants-only class holding every `AttributeKey` the aspect writes — no logic there, just names. The only classes you might interact with as a user are the properties class (through `application.properties`) and, if you need custom redaction, `PiiMasker` (by providing your own `@Bean`). Everything else runs unattended.


---

## 2. Prerequisites

Before you write a single line of code, make sure these are installed and working.

### 2.1 Java 17+

```bash
java -version
```

You should see `17` or higher. If not, install Eclipse Temurin / Microsoft JDK / Amazon Corretto.

### 2.2 Maven 3.9+

```bash
mvn -version
```

Anything 3.9+ works.

### 2.3 AWS credentials for Bedrock

The Spring AI Bedrock starter uses the **default AWS credential provider chain** — env vars, `~/.aws/credentials`, EC2 instance profile, etc.

#### 2.3.1 Install the AWS CLI

**macOS:**
```bash
brew install awscli
```

**Linux:**
```bash
curl "https://awscli.amazonaws.com/awscli-exe-linux-x86_64.zip" -o "awscliv2.zip"
unzip awscliv2.zip
sudo ./aws/install
```

**Windows (PowerShell):**
```powershell
msiexec.exe /i https://awscli.amazonaws.com/AWSCLIV2.msi
```

Verify:
```bash
aws --version
```

You should see something like `aws-cli/2.x.x`.

#### 2.3.2 Configure credentials

Pick one of the three options below. For most developers, **Option A** is the simplest.

**Option A — `aws configure` (interactive):**

```bash
aws configure
```

The CLI prompts you for four values:

```
AWS Access Key ID [None]: AKIA...
AWS Secret Access Key [None]: wJalrXUt...
Default region name [None]: us-east-1
Default output format [None]: json
```

This writes `~/.aws/credentials` and `~/.aws/config`. Spring AI's Bedrock starter reads them automatically.

**Option B — Environment variables:**

```bash
export AWS_ACCESS_KEY_ID=AKIA...
export AWS_SECRET_ACCESS_KEY=wJalrXUt...
export AWS_SESSION_TOKEN=IQo...   # only if you're using temporary credentials
export AWS_REGION=us-east-1
```

On Windows PowerShell:

```powershell
$env:AWS_ACCESS_KEY_ID = "AKIA..."
$env:AWS_SECRET_ACCESS_KEY = "wJalrXUt..."
$env:AWS_REGION = "us-east-1"
```

**Option C — AWS SSO (enterprise accounts):**

```bash
aws configure sso
aws sso login --profile my-bedrock-profile
export AWS_PROFILE=my-bedrock-profile
export AWS_REGION=us-east-1
```

#### 2.3.3 Sanity-check your setup

```bash
aws sts get-caller-identity
```

Expected output:

```json
{
    "UserId": "AIDA...",
    "Account": "123456789012",
    "Arn": "arn:aws:iam::123456789012:user/you"
}
```

If you get `Unable to locate credentials`, your credentials aren't configured — revisit 2.3.2.

#### 2.3.4 Confirm Bedrock access

```bash
aws bedrock list-foundation-models --region us-east-1 \
    --query "modelSummaries[?modelId=='amazon.nova-lite-v1:0'].modelId"
```

Expected:

```json
[
    "amazon.nova-lite-v1:0"
]
```

Empty list means the model exists in that region but your account can't see it yet — enable model access in the next step.

#### 2.3.5 IAM permissions required

The principal running the app needs at minimum:

```json
{
    "Version": "2012-10-17",
    "Statement": [
        {
            "Effect": "Allow",
            "Action": [
                "bedrock:InvokeModel",
                "bedrock:Converse",
                "bedrock:ConverseStream"
            ],
            "Resource": "arn:aws:bedrock:us-east-1::foundation-model/amazon.nova-lite-v1:0"
        }
    ]
}
```

Attach this inline policy to the IAM user or role you're using. For quick experimentation the AWS-managed `AmazonBedrockFullAccess` works too, but don't use it in production.

### 2.4 Pick a model

For this tutorial we use **`amazon.nova-lite-v1:0`** because:

- It's cheap enough to experiment with
- It does **not** require an "inference profile" (some Anthropic models do)
- It is available in `us-east-1`, which is the default Bedrock region in most accounts

If you prefer a different model, substitute its ID wherever you see `amazon.nova-lite-v1:0` below.

### 2.5 Enable the model in Bedrock

In the AWS console → Bedrock → **Model access**, make sure the model is enabled for your account. If you skip this, your first call will fail with `AccessDeniedException`.

---

## 3. Project setup

### 3.1 Create a minimal Spring Boot project

You can generate a skeleton with `start.spring.io` (Web, Spring Boot 3.5+), or just create these files by hand.

Directory layout:

```
my-agent/
├── pom.xml
└── src/
    └── main/
        ├── java/
        │   └── com/example/demo/
        │       ├── MyAgentApplication.java
        │       └── BedrockAgentService.java
        └── resources/
            └── application.properties
```

### 3.2 `pom.xml`

Copy this verbatim. The important bits are:

- **Parent:** `spring-boot-starter-parent 3.5.9` (or newer 3.5.x)
- **Two BOMs:** Spring AI BOM and OpenTelemetry instrumentation BOM
- **Three dependencies:** the observability starter, the Bedrock Converse starter, and (optionally) OTel SDK testing

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>

  <parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>3.5.9</version>
    <relativePath/>
  </parent>

  <groupId>com.example</groupId>
  <artifactId>my-agent</artifactId>
  <version>1.0.0-SNAPSHOT</version>

  <properties>
    <java.version>17</java.version>
    <spring-ai.version>1.1.2</spring-ai.version>
    <opentelemetry-instrumentation.version>2.14.0</opentelemetry-instrumentation.version>
  </properties>

  <repositories>
    <!-- Needed while the observability module is still on SNAPSHOT -->
    <repository>
      <id>spring-snapshots</id>
      <url>https://repo.spring.io/snapshot</url>
      <snapshots><enabled>true</enabled></snapshots>
    </repository>
    <repository>
      <id>central-portal-snapshots</id>
      <url>https://central.sonatype.com/repository/maven-snapshots/</url>
      <snapshots><enabled>true</enabled></snapshots>
    </repository>
  </repositories>

  <dependencyManagement>
    <dependencies>
      <dependency>
        <groupId>org.springframework.ai</groupId>
        <artifactId>spring-ai-bom</artifactId>
        <version>${spring-ai.version}</version>
        <type>pom</type>
        <scope>import</scope>
      </dependency>
      <dependency>
        <groupId>io.opentelemetry.instrumentation</groupId>
        <artifactId>opentelemetry-instrumentation-bom</artifactId>
        <version>${opentelemetry-instrumentation.version}</version>
        <type>pom</type>
        <scope>import</scope>
      </dependency>
    </dependencies>
  </dependencyManagement>

  <dependencies>
    <!-- THE observability starter -->
    <dependency>
      <groupId>org.springaicommunity</groupId>
      <artifactId>spring-ai-agentcore-observability</artifactId>
      <version>1.1.0-SNAPSHOT</version>
    </dependency>

    <!-- Spring AI -> Amazon Bedrock -->
    <dependency>
      <groupId>org.springframework.ai</groupId>
      <artifactId>spring-ai-starter-model-bedrock-converse</artifactId>
    </dependency>

    <!-- Optional: only if you want in-memory span assertions in tests -->
    <dependency>
      <groupId>io.opentelemetry</groupId>
      <artifactId>opentelemetry-sdk-testing</artifactId>
      <scope>test</scope>
    </dependency>
  </dependencies>

  <build>
    <plugins>
      <plugin>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-maven-plugin</artifactId>
      </plugin>
    </plugins>
  </build>
</project>
```

**What each dependency brings transitively:**

**Figure 4 — Transitive dependency graph for the two starters.**

```mermaid
flowchart TD
    App[Your app pom.xml]
    App --> Obs[spring-ai-agentcore-observability]
    App --> BedrockStarter[spring-ai-starter-model-bedrock-converse]

    Obs --> AgentCore["spring-ai-agentcore<br/>@AgentCoreInvocation annotation<br/>+ HTTP controller"]
    Obs --> AopStarter[spring-boot-starter-aop]
    Obs --> OtelApi[opentelemetry-api + sdk]
    Obs --> OtelSpring[opentelemetry-spring-boot-starter]

    BedrockStarter --> SpringAiCore[spring-ai-core]
    BedrockStarter --> AwsSdk[AWS SDK v2<br/>bedrock-runtime]

    AgentCore --> SpringWeb[spring-boot-starter-web<br/>registers /invocations]
```


**Reading the diagram in plain English.** You only declare two dependencies in your `pom.xml`, but Maven pulls in everything else automatically. The observability starter brings the AgentCore annotation and HTTP controller (so your `@AgentCoreInvocation` method actually gets routed to), Spring Boot AOP (so the aspect can wrap method calls), and the OpenTelemetry API, SDK, and Spring Boot starter (so traces actually get created and exported). The Bedrock Converse starter brings Spring AI core and the AWS SDK. The AgentCore dependency brings `spring-boot-starter-web` so you get an embedded Tomcat and the `/invocations` endpoint wired up for you. End result: two lines of `<dependency>` give you a production-ready, observable, AWS-correlated, PII-safe Bedrock agent.

### 3.3 `application.properties`

Create `src/main/resources/application.properties`:

```properties
spring.application.name=my-agent

# --- Spring AI Bedrock ---
spring.ai.bedrock.converse.chat.options.model=amazon.nova-lite-v1:0
spring.ai.bedrock.aws.region=${AWS_REGION:us-east-1}

# --- Observability: opt in to prompt/completion capture ---
# Without this, spans have token counts but no prompt/completion text
spring.ai.agentcore.observability.capture-content=true
spring.ai.agentcore.observability.masking.enabled=true

# --- OpenTelemetry SDK ---
# "logging" prints spans to stdout (great for dev).
# Change to "otlp" for production (see section 10).
otel.traces.exporter=logging
otel.metrics.exporter=none
otel.logs.exporter=none

# --- Log noise control ---
logging.level.root=INFO
logging.level.org.springaicommunity.agentcore.observability=INFO
```

> **CRITICAL GOTCHA.** Do **not** set `otel.traces.exporter=none`. Setting it to `none` disables the OTel SDK entirely, which means our `AutoConfigurationCustomizerProvider` never runs and **zero spans are produced**. For local dev use `logging`; for prod use `otlp`.

---

## 4. Write the agent

You only write **one** class. Everything else is auto-configured.

### 4.1 The handler

`src/main/java/com/example/demo/BedrockAgentService.java`:

```java
package com.example.demo;

import org.springaicommunity.agentcore.annotation.AgentCoreInvocation;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;

/**
 * AgentCore invocation handler.
 * Receives the body of POST /invocations as a String
 * and returns a ChatResponse from Spring AI.
 */
@Service
public class BedrockAgentService {

  private final ChatModel chatModel;

  public BedrockAgentService(ChatModel chatModel) {
    this.chatModel = chatModel;
  }

  @AgentCoreInvocation
  public ChatResponse handle(String prompt) {
    return this.chatModel.call(new Prompt(new UserMessage(prompt)));
  }
}
```

**What's going on here:**

- `@AgentCoreInvocation` tells the AgentCore HTTP controller "this is the method to dispatch `POST /invocations` to".
- The observability aspect targets the controller's entry-point methods (`handleJsonInvocation`, `handleTextInvocation`) via AspectJ — **not** your `handle` method directly. That's deliberate: it keeps the AgentCore method scanner happy (CGLIB proxies on your bean would strip annotations).
- `ChatModel` is auto-wired by Spring AI's Bedrock starter.

### 4.2 The main class

`src/main/java/com/example/demo/MyAgentApplication.java`:

```java
package com.example.demo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class MyAgentApplication {
  public static void main(String[] args) {
    SpringApplication.run(MyAgentApplication.class, args);
  }
}
```

That's it. Two files. The starter wires up the `/invocations` endpoint, the AOP aspect, the PII masker, and the exporter wrapper.

### 4.3 Build

```bash
mvn -q -DskipTests package
```

You should end up with `target/my-agent-1.0.0-SNAPSHOT.jar`.

---

## 5. Run against live Bedrock

### 5.1 Start the app

```bash
export AWS_REGION=us-east-1
java -jar target/my-agent-1.0.0-SNAPSHOT.jar
```

Watch for this line:

```
Started MyAgentApplication in 7.8 seconds
```

### 5.2 Send a request

In another terminal:

```bash
curl -X POST http://localhost:8080/invocations \
  -H "Content-Type: text/plain" \
  -H "x-amzn-bedrock-agentcore-session-id: demo-session-abc123" \
  -H "x-amzn-request-id: demo-request-xyz789" \
  --data "I'm Jane (jane.doe@example.com, SSN 123-45-6789). Reply with just: OK"
```

### 5.3 Watch the span appear on stdout

Because we set `otel.traces.exporter=logging`, the OTel SDK prints each exported span to stdout. You should see something like:

```
'chat' : ... SpanData{
  name=chat,
  kind=INTERNAL,
  attributes=AttributesMap{data={
    gen_ai.operation.name=chat,
    gen_ai.provider.name=aws.bedrock,
    gen_ai.system=aws.bedrock,
    gen_ai.request.model=amazon.nova-lite-v1:0,
    gen_ai.response.model=amazon.nova-lite-v1:0,
    gen_ai.usage.input_tokens=34,
    gen_ai.usage.output_tokens=2,
    gen_ai.response.finish_reasons=end_turn,
    aws.bedrock.agentcore.session_id=demo-session-abc123,
    aws.request_id=demo-request-xyz789
  }},
  events=[
    Event{name=gen_ai.content.prompt,
          attributes={gen_ai.prompt=I'm Jane (j***@***.com, SSN ###-##-####). Reply with just: OK}},
    Event{name=gen_ai.content.completion,
          attributes={gen_ai.completion=OK}}
  ]
}
```

Three things to notice:

1. The **email** `jane.doe@example.com` became `j***@***.com`.
2. The **SSN** `123-45-6789` became `###-##-####`.
3. Both request headers landed on the span as `aws.bedrock.agentcore.session_id` and `aws.request_id`.

If you see that, the module is working end-to-end. Congratulations.

---

## 6. Span attributes reference

Every attribute key is a constant on `GenAiTelemetrySupport`. Here's the full set, what each means, and where it comes from.

### 6.1 GenAI attributes

| Attribute | Type | Value(s) | Source |
|-----------|------|----------|--------|
| `gen_ai.operation.name` | string | `chat` or `execute_tool` | `chat` normally; `execute_tool` when `ChatResponse.hasToolCalls()` is true |
| `gen_ai.provider.name` | string | `aws.bedrock` | Constant |
| `gen_ai.system` | string | `aws.bedrock` | Constant |
| `gen_ai.request.model` | string | e.g. `amazon.nova-lite-v1:0` | `ChatResponseMetadata.getModel()`, falls back to `spring.ai.bedrock.converse.chat.options.model` |
| `gen_ai.response.model` | string | same as request.model | Same resolution |
| `gen_ai.usage.input_tokens` | long | prompt tokens + cache-read tokens | `Usage.getPromptTokens()` + `metadata.get("cacheReadInputTokens")` |
| `gen_ai.usage.output_tokens` | long | completion tokens | `Usage.getCompletionTokens()` |
| `gen_ai.response.finish_reasons` | string | comma-separated unique values, e.g. `end_turn,tool_use` | `Generation.getMetadata().getFinishReason()` across all generations |
| `error.type` | string | `rate_limit`, `invalid_request`, `timeout`, `authentication_failure`, `server_error` | Classified from the thrown exception's class name |

### 6.2 AWS correlation attributes

| Attribute | Source HTTP header |
|-----------|--------------------|
| `aws.bedrock.agentcore.session_id` | `x-amzn-bedrock-agentcore-session-id` |
| `aws.request_id` | `x-amzn-request-id` (tried first), then `x-amzn-requestid`, then `X-Amzn-RequestId` |

Both are only set when the header is actually present on the inbound request.

### 6.3 Span events (opt-in, only when `capture-content=true`)

| Event name | Attributes on the event |
|------------|-------------------------|
| `gen_ai.content.prompt` | `gen_ai.prompt = <masked prompt string>` |
| `gen_ai.content.completion` | `gen_ai.completion = <masked completion string>` |

### 6.4 Metrics

The aspect records a histogram called **`gen_ai.client.token.usage`** on every invocation, with two samples per call:

- One with `gen_ai.token.type=input` and the input token count
- One with `gen_ai.token.type=output` and the output token count

Each sample also carries:

- `gen_ai.request.model`
- `gen_ai.provider.name = aws.bedrock`

This gives you per-model token-cost dashboards for free.

### 6.5 Visual map

**Figure 5 — All attributes and events produced on a single invocation.**

```mermaid
graph TD
    Span[chat span]
    Span --> GenAi[gen_ai.*<br/>operation, provider, system,<br/>request/response model,<br/>usage.input/output_tokens,<br/>response.finish_reasons]
    Span --> Aws[aws.*<br/>bedrock.agentcore.session_id,<br/>request_id]
    Span --> Err[error.type<br/>on failure]
    Span --> Ev1[event: gen_ai.content.prompt<br/>if capture-content=true]
    Span --> Ev2[event: gen_ai.content.completion<br/>if capture-content=true]

    Metric[gen_ai.client.token.usage<br/>histogram]
    Metric --> In[sample: token.type=input]
    Metric --> Out[sample: token.type=output]
```

**Reading the diagram in plain English.** Every AgentCore invocation produces two OpenTelemetry signals: one span and one metric. The span is named `chat` (or `execute_tool` for tool-calling responses) and carries three groups of attributes — the standard GenAI conventions (`gen_ai.*`), AWS correlation IDs (`aws.*`), and `error.type` when something blew up. If you turned on `capture-content`, the span also carries two events: one for the prompt and one for the completion. Separately, a histogram metric called `gen_ai.client.token.usage` is recorded with two samples per call — one tagged `input` and one tagged `output`. In Grafana / Datadog / CloudWatch you can slice this metric by model, by provider, or by token type to build cost dashboards.


---

## 7. Configuration reference

All configuration lives under the `spring.ai.agentcore.observability.*` prefix. Every property is backed by `AgentCoreObservabilityProperties`.

### 7.1 Top-level

| Property | Type | Default | What it does |
|----------|------|---------|--------------|
| `spring.ai.agentcore.observability.capture-content` | boolean | `false` | When `true`, adds `gen_ai.content.prompt` and `gen_ai.content.completion` events to spans |

Legacy fallback: if the Spring property is unset, `OTEL_GENAI_CAPTURE_CONTENT` env var is honored.

### 7.2 Masking toggles

All under `spring.ai.agentcore.observability.masking.*`:

| Property | Default | What it does |
|----------|---------|--------------|
| `enabled` | `true` | Master switch. If `false`, no masking happens at all |
| `mask-email` | `true` | Masks emails to `first-char + *** @ *** . tld` |
| `mask-ssn` | `true` | Masks `xxx-xx-xxxx` SSNs to `###-##-####` |
| `mask-credit-card` | `true` | Masks Luhn-valid PANs with plausible issuer prefix |
| `mask-phone` | `true` | Masks US phone formats to `###-###-####` |

### 7.3 Custom regex

Add your own patterns. Each match is replaced with the literal `[REDACTED]`.

```properties
spring.ai.agentcore.observability.masking.custom-regex[0]=\\bAKIA[0-9A-Z]{16}\\b
spring.ai.agentcore.observability.masking.custom-regex[1]=\\bsk-[A-Za-z0-9]{20,}\\b
spring.ai.agentcore.observability.masking.custom-regex[2]=\\bMRN-\\d{6,10}\\b
```

Patterns are compiled once at startup. Invalid regex will fail the app at boot — which is what you want.

### 7.4 Complete example

```properties
spring.ai.agentcore.observability.capture-content=true

spring.ai.agentcore.observability.masking.enabled=true
spring.ai.agentcore.observability.masking.mask-email=true
spring.ai.agentcore.observability.masking.mask-ssn=true
spring.ai.agentcore.observability.masking.mask-credit-card=true
spring.ai.agentcore.observability.masking.mask-phone=true
spring.ai.agentcore.observability.masking.custom-regex[0]=\\bAKIA[0-9A-Z]{16}\\b
```

---

## 8. PII masking deep dive

This is the most nuanced part of the module, so it's worth understanding how it works before you trust it with production data.

### 8.1 Where masking happens

**Figure 6 — Masking sits between the OTel SDK and whatever exporter you configured.**

```mermaid
flowchart LR
    Aspect[Aspect creates span<br/>with raw content] --> Sdk[OTel SDK<br/>BatchSpanProcessor]
    Sdk --> Wrapper[PiiMaskingSpanExporter]
    Wrapper --> Wrapper2[wraps each SpanData<br/>in MaskingSpanData]
    Wrapper2 --> Delegate[Delegate exporter<br/>OTLP / logging / custom]
    Delegate --> Wire[Network / disk]

    style Wrapper fill:#ffe4b5,stroke:#333
    style Wrapper2 fill:#ffe4b5,stroke:#333
```

**Reading the diagram in plain English.** When the aspect finishes enriching a span it still holds the raw prompt and raw completion — that's unavoidable, the string has to be on the span for the exporter to see it. But the span never leaves the process in that form. The SDK's `BatchSpanProcessor` hands it to `PiiMaskingSpanExporter` (the orange box), which wraps each `SpanData` in a lazy proxy called `MaskingSpanData`. The proxy intercepts every string accessor — attribute values, event attribute values — and runs them through `PiiMasker.mask(...)` on the fly. Only then is the span passed to the delegate exporter (OTLP, logging, whatever you configured). By the time bytes leave the process, emails, SSNs, card numbers, phones, and your custom patterns are already redacted. The key takeaway: masking is an export-time transformation, not a create-time one — so if another exporter is ever attached **ahead** of the masking wrapper, it could see raw content. The auto-config uses `AutoConfigurationCustomizerProvider` to ensure the wrap happens, but if you stack additional custom exporters, keep masking outermost.

> **Important caveat:** application logs (`logger.info("prompt: {}", prompt)`) are **not** masked by this module. Masking only applies to OpenTelemetry span export. Use your logging framework's own redaction (Logback converters, Log4j2 filters) for log output.

### 8.2 Patterns and replacements

**Figure 7 — Order of pattern checks inside `PiiMasker.mask(String)`.**

```mermaid
flowchart TD
    Input[Input string] --> Ssn{match<br/>xxx-xx-xxxx?}
    Ssn -->|yes| SsnOut[replace with ###-##-####]
    Ssn -->|no| Pan{match<br/>13-19 digit run?}
    SsnOut --> Pan
    Pan -->|yes| Luhn{Luhn valid?}
    Luhn -->|yes| Prefix{Issuer prefix<br/>plausible?}
    Prefix -->|yes| PanOut[replace: first4-****-****-last4]
    Luhn -->|no| Skip1[leave alone]
    Prefix -->|no| Skip2[leave alone]
    Pan -->|no| Email{match<br/>email?}
    PanOut --> Email
    Skip1 --> Email
    Skip2 --> Email
    Email -->|yes| EmailOut[replace with<br/>first-char + *** @ *** . tld]
    Email -->|no| Phone{match<br/>phone?}
    EmailOut --> Phone
    Phone -->|yes| PhoneOut[replace with ###-###-####]
    Phone -->|no| Custom
    PhoneOut --> Custom[apply custom regex<br/>replace each with REDACTED]
    Custom --> Output[masked string]
```

**Reading the diagram in plain English.** The masker runs patterns in a fixed order, and each pattern is only applied if its corresponding toggle is enabled. First it catches SSNs (nine digits in the `xxx-xx-xxxx` shape). Then it hunts for credit-card-shaped digit runs — but it won't touch them unless they pass **both** the Luhn checksum and an issuer-prefix check (Visa, Mastercard, Amex, Discover, JCB ranges). That double gate is why order numbers and tracking IDs aren't accidentally masked. Next it handles emails, reducing them to first-char + stars + TLD. Then four US phone formats. Finally your custom regex patterns run, each match collapsed to `[REDACTED]`. The reason SSN comes before PAN: an SSN (`123-45-6789`) is 9 digits which the PAN regex would otherwise never match, but SSN also must not be misinterpreted — so the narrow pattern runs first.

### 8.3 Credit card mask — why it's harder than it looks

Naive PAN regex (`\b\d{16}\b`) will trip on:

- Order numbers
- Tracking numbers
- Account IDs
- Random 16-digit hashes

`PiiMasker` applies **two validators** before masking:

1. **Luhn check** — the standard mod-10 checksum every real card passes. Kills 90% of false positives.
2. **Issuer prefix check** — the first 1–6 digits must plausibly be a card network:
   - Visa: starts with `4`
   - Mastercard: `51`–`55` or `2221`–`2720`
   - Amex: `34` or `37` or `3528`–`3589` (JCB range)
   - Discover: `6011`, `65`, `644`–`649`, or `622126`–`622925`

If either check fails, the number is left alone. If both pass, the string becomes `1234-****-****-5678`.

### 8.4 Email mask

`jane.doe@example.com` → `j***@***.com`

- Local part: first character + `***`
- Domain: `***.` + the TLD segment

This preserves the TLD so you can tell `.com` vs `.gov` vs `.mil` for aggregate analytics, while making the individual identity unrecoverable.

### 8.5 Phone mask (US formats)

These formats are all recognized and replaced with `###-###-####`:

- `+1 555-123-4567` (international dash)
- `+1 (555) 123-4567` (international parens)
- `(555) 123-4567` (parens)
- `555.123.4567` (dot)
- `555-123-4567` (dash)

Non-US formats are not matched by default — add a custom regex if you need `+44`, `+91`, etc.

### 8.6 Bring your own masker

If the defaults don't fit (e.g. HIPAA PHI, non-US identifiers), define your own `PiiMasker` bean. The auto-config uses `@ConditionalOnMissingBean` so yours wins:

```java
package com.example.demo;

import java.util.List;
import java.util.regex.Pattern;

import org.springaicommunity.agentcore.observability.masking.PiiMasker;
import org.springaicommunity.agentcore.observability.masking.PiiMaskingSettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class HealthcareMaskerConfig {

  @Bean
  PiiMasker healthcareMasker() {
    PiiMaskingSettings settings = new PiiMaskingSettings(
        true,   // enabled
        true,   // mask email
        true,   // mask SSN
        false,  // don't mask PAN (we don't handle payments)
        true,   // mask phone
        List.of(
            Pattern.compile("\\bMRN-\\d{6,10}\\b"),       // medical record number
            Pattern.compile("\\bNPI-\\d{10}\\b"),         // national provider ID
            Pattern.compile("\\bDEA[A-Z]{2}\\d{7}\\b")    // DEA number
        )
    );
    return new PiiMasker(settings);
  }
}
```

Custom matches collapse to `[REDACTED]` (not a format-preserving mask). If you want format-preserving, subclass `PiiMasker` and override `mask(String)`.

---

## 9. AWS request correlation

One of the hardest parts of debugging LLM apps in AWS is joining your application-side trace to the Bedrock-side logs. The observability module solves this by copying two inbound HTTP headers onto every span.

### 9.1 The headers and attributes

**Figure 8 — How inbound HTTP headers become span attributes.**

```mermaid
flowchart LR
    subgraph Request[Inbound HTTP request]
        H1[x-amzn-bedrock-agentcore-session-id]
        H2[x-amzn-request-id<br/>or x-amzn-requestid<br/>or X-Amzn-RequestId]
    end
    subgraph Span[Span attributes]
        A1[aws.bedrock.agentcore.session_id]
        A2[aws.request_id]
    end
    H1 -->|copied as-is| A1
    H2 -->|first match wins| A2
```

**Reading the diagram in plain English.** Your AgentCore runtime (or whatever's calling your endpoint) sends two headers. The session ID goes straight onto the span as `aws.bedrock.agentcore.session_id`. The request ID is a bit messier because AWS has used three different casings for the same header over the years — the aspect tries each spelling in order and uses whichever one shows up first. Once these two values are on the span, you can pivot on them in your APM: "show me every trace for session X" or "find the span that matches this Bedrock request ID from CloudWatch Logs".

### 9.2 Why three spellings for request-id?

AWS documentation historically uses three different spellings for the same header. The AgentCore runtime might send any of them depending on version and configuration. `GenAiTelemetrySupport.HTTP_HEADER_AMZN_REQUEST_ID_ALIASES` tries them in this order:

1. `x-amzn-request-id` (canonical AWS form)
2. `x-amzn-requestid` (legacy undashed)
3. `X-Amzn-RequestId` (doc-style casing; HTTP headers are case-insensitive but some libraries key on the exact string)

### 9.3 How it finds the headers (cross-stack)

`AgentCoreInvocationHeaderSupport` is deliberately stack-agnostic. It resolves headers via reflection so the observability module doesn't force `spring-web` **or** `spring-webflux` on you:

**Figure 9 — Header lookup strategy across servlet, WebFlux, and thread-local stacks.**

```mermaid
flowchart TD
    Start[firstHeader] --> HasServletArg{Servlet<br/>HttpServletRequest<br/>in method args?}
    HasServletArg -->|yes| GetServlet[request.getHeader]
    HasServletArg -->|no| HasWebFluxArg{ServerWebExchange<br/>in method args?}
    HasWebFluxArg -->|yes| GetWebFlux[exchange.getRequest.getHeaders.getFirst]
    HasWebFluxArg -->|no| HasCtx{RequestContextHolder<br/>has attributes?}
    HasCtx -->|yes| GetCtx[servletRequest.getHeader]
    HasCtx -->|no| ReturnNull[null - no correlation]

    GetServlet --> Return[return header value]
    GetWebFlux --> Return
    GetCtx --> Return
```

**Reading the diagram in plain English.** Finding an inbound header sounds trivial, but Spring supports both servlet (Tomcat / Jetty) and reactive (WebFlux / Netty) stacks, and they expose requests through completely different APIs. Rather than picking one and forcing every consumer to depend on it, the module looks at whatever the aspect has in hand and tries three strategies. First: is any argument of the advised method a `HttpServletRequest`? If so, call `getHeader`. Second: is any argument a WebFlux `ServerWebExchange`? If so, walk it to the headers and pull the first match. Third: fall back to Spring's thread-local `RequestContextHolder`, which works in servlet-only scenarios where the request isn't an argument. If none of those land, the header simply isn't there and correlation attributes are skipped — no crash, no log noise. This reflection-based approach means `spring-web` and `spring-webflux` are **optional** Maven dependencies; you only pay for the stack you use.

### 9.4 Using correlation in practice

In your APM / tracing backend, you can now query:

- "Show me all spans where `aws.bedrock.agentcore.session_id = <customer-session-uuid>`"
- "Show me all spans where `aws.request_id = <bedrock-request-id-from-cloudwatch-log>`"

This lets you answer production questions like "which Bedrock call corresponds to this customer complaint" in seconds.


---

## 9a. End-to-end worked example — everything at once

This section throws every feature at the module in a single request so you can see each transformation happening side by side.

### 9a.1 What we'll exercise

**Figure 10 — Ten observable outcomes exercised by a single request.**

```mermaid
flowchart LR
    Req[Single HTTP request] --> F1[Email masking]
    Req --> F2[SSN masking]
    Req --> F3[Credit card masking<br/>+ Luhn + issuer check]
    Req --> F4[Phone masking<br/>4 formats]
    Req --> F5[Custom regex<br/>AWS key + OpenAI key]
    Req --> F6[Session ID correlation]
    Req --> F7[Request ID correlation]
    Req --> F8[Token usage metric]
    Req --> F9[Finish reason]
    Req --> F10[Prompt + completion capture]
```

**Reading the diagram in plain English.** One `POST /invocations` call will light up every feature of the module simultaneously. We'll craft a single prompt that contains an email, an SSN, a Luhn-valid test Visa number, four different US phone formats, a fake AWS access key, and a fake OpenAI key — then we'll watch the masker redact each one differently. The same request will carry the two AWS correlation headers so we can see session and request IDs on the span. Bedrock will report token counts and a finish reason automatically. And because `capture-content=true`, we'll see the masked prompt and completion attached as span events. This single request is the smoke test you run after wiring the starter into a new service.

### 9a.2 Configuration — turn everything on

`application.properties`:

```properties
spring.application.name=my-agent
server.port=8080

spring.ai.bedrock.converse.chat.options.model=amazon.nova-lite-v1:0
spring.ai.bedrock.aws.region=${AWS_REGION:us-east-1}

# Turn on prompt/completion capture so masking is visible
spring.ai.agentcore.observability.capture-content=true

# Turn on every masking category
spring.ai.agentcore.observability.masking.enabled=true
spring.ai.agentcore.observability.masking.mask-email=true
spring.ai.agentcore.observability.masking.mask-ssn=true
spring.ai.agentcore.observability.masking.mask-credit-card=true
spring.ai.agentcore.observability.masking.mask-phone=true

# Two custom regexes: AWS access keys and OpenAI secret keys
spring.ai.agentcore.observability.masking.custom-regex[0]=\\bAKIA[0-9A-Z]{16}\\b
spring.ai.agentcore.observability.masking.custom-regex[1]=\\bsk-[A-Za-z0-9]{20,}\\b

# Print spans to stdout so we can see what gets exported
otel.traces.exporter=logging
otel.metrics.exporter=logging
otel.logs.exporter=none

logging.level.root=WARN
logging.level.com.example.demo=INFO
```

### 9a.3 The request — cram every PII type into one prompt

Ask the model for a one-word answer so its response is predictable and the interesting content is in the prompt.

```bash
curl -X POST http://localhost:8080/invocations \
  -H "Content-Type: text/plain" \
  -H "x-amzn-bedrock-agentcore-session-id: session-e2e-test-42" \
  -H "x-amzn-request-id: req-e2e-test-99" \
  --data $'Customer profile for the record:\n\
Name: Jane Doe\n\
Email: jane.doe@acme-corp.com\n\
SSN: 123-45-6789\n\
Visa: 4532-0151-1283-0366\n\
Phone (dash): 555-234-5678\n\
Phone (parens): (555) 234-5678\n\
Phone (dot): 555.234.5678\n\
Phone (intl): +1 555-234-5678\n\
AWS key: AKIAIOSFODNN7EXAMPLE\n\
OpenAI key: sk-abc123def456ghi789jkl012mno345\n\
Respond with just the word OK.'
```

> **Note on the Visa number.** `4532-0151-1283-0366` is a well-known Luhn-valid test PAN — it doesn't belong to anyone and won't charge anything. Never put real card numbers in test prompts.

### 9a.4 Expected span attributes (on stdout)

With `otel.traces.exporter=logging`, the SDK prints each exported span. Here's the shape of what you'll see, trimmed to the interesting parts:

```text
'chat' : ... INTERNAL SpanData{
  name=chat,
  kind=INTERNAL,
  status=OK,
  attributes=AttributesMap{data={
    gen_ai.operation.name       = chat,
    gen_ai.provider.name        = aws.bedrock,
    gen_ai.system               = aws.bedrock,
    gen_ai.request.model        = amazon.nova-lite-v1:0,
    gen_ai.response.model       = amazon.nova-lite-v1:0,
    gen_ai.usage.input_tokens   = 87,
    gen_ai.usage.output_tokens  = 2,
    gen_ai.response.finish_reasons = end_turn,

    aws.bedrock.agentcore.session_id = session-e2e-test-42,
    aws.request_id                   = req-e2e-test-99
  }},
  events=[
    Event{name=gen_ai.content.prompt, attributes={
      gen_ai.prompt=Customer profile for the record:
Name: Jane Doe
Email: j***@***.com
SSN: ###-##-####
Visa: 4532-****-****-0366
Phone (dash): ###-###-####
Phone (parens): ###-###-####
Phone (dot): ###-###-####
Phone (intl): ###-###-####
AWS key: [REDACTED]
OpenAI key: [REDACTED]
Respond with just the word OK.
    }},
    Event{name=gen_ai.content.completion, attributes={
      gen_ai.completion=OK
    }}
  ]
}
```

Token counts will vary by model and tokenizer — the important thing is they're positive.

### 9a.5 Transformation table — what came in vs what went out

This is the table you show your security team.

| # | Feature | What you sent | What the collector saw | Controlled by |
|---|---------|---------------|------------------------|---------------|
| 1 | Email | `jane.doe@acme-corp.com` | `j***@***.com` | `masking.mask-email` |
| 2 | SSN | `123-45-6789` | `###-##-####` | `masking.mask-ssn` |
| 3 | Visa (Luhn-valid) | `4532-0151-1283-0366` | `4532-****-****-0366` | `masking.mask-credit-card` |
| 4 | Phone dash | `555-234-5678` | `###-###-####` | `masking.mask-phone` |
| 5 | Phone parens | `(555) 234-5678` | `###-###-####` | `masking.mask-phone` |
| 6 | Phone dot | `555.234.5678` | `###-###-####` | `masking.mask-phone` |
| 7 | Phone intl | `+1 555-234-5678` | `###-###-####` | `masking.mask-phone` |
| 8 | AWS access key | `AKIAIOSFODNN7EXAMPLE` | `[REDACTED]` | `custom-regex[0]` |
| 9 | OpenAI key | `sk-abc123def456ghi789jkl012mno345` | `[REDACTED]` | `custom-regex[1]` |
| 10 | Session header | `session-e2e-test-42` | attr `aws.bedrock.agentcore.session_id` | copied from header |
| 11 | Request header | `req-e2e-test-99` | attr `aws.request_id` | copied from header |
| 12 | Token usage | — | attrs + metric sample | always on |
| 13 | Finish reason | — | `end_turn` | always on |

### 9a.6 The metric you get

`otel.metrics.exporter=logging` also prints the token histogram. You'll see two samples per request:

```text
metric: gen_ai.client.token.usage (histogram, unit={token})
  sample: value=87.0, attributes={
    gen_ai.token.type=input,
    gen_ai.request.model=amazon.nova-lite-v1:0,
    gen_ai.provider.name=aws.bedrock
  }
  sample: value=2.0, attributes={
    gen_ai.token.type=output,
    gen_ai.request.model=amazon.nova-lite-v1:0,
    gen_ai.provider.name=aws.bedrock
  }
```

That's everything you need to build a per-model token-spend dashboard without writing any custom code.

### 9a.7 Negative test — confirm PAN false-positive rejection

Bonus: the PAN masker is **supposed** to leave non-card numbers alone. Send one to prove it:

```bash
curl -X POST http://localhost:8080/invocations \
  -H "Content-Type: text/plain" \
  --data "Tracking number: 1234567890123456. Reply OK."
```

`1234567890123456` passes the length check but fails Luhn. Expected prompt event on the span:

```text
gen_ai.prompt=Tracking number: 1234567890123456. Reply OK.
```

No masking, because it isn't a real card number. If your team was worried about order numbers getting mangled — they don't.

### 9a.8 Negative test — disable everything, see raw content

To prove masking is actually doing work, turn it off for one run:

```properties
spring.ai.agentcore.observability.masking.enabled=false
```

Rebuild, rerun the full request from 9a.3, and you'll see the prompt event contain the raw email, SSN, card, phones and keys. Turn it back on before you forget.

### 9a.9 Turnkey sanity-check script

Drop this alongside your jar to run the whole scenario end-to-end:

```bash
#!/usr/bin/env bash
set -euo pipefail

export AWS_REGION=us-east-1

# Start the app in the background, capture output
java -jar target/my-agent-1.0.0-SNAPSHOT.jar > app.log 2>&1 &
APP_PID=$!
trap "kill $APP_PID 2>/dev/null || true" EXIT

# Wait until the app is accepting requests
until curl -sf -o /dev/null http://localhost:8080/actuator/health 2>/dev/null \
      || curl -sf -o /dev/null -X POST http://localhost:8080/invocations --data "ping" 2>/dev/null; do
  sleep 1
done

# Fire the everything-at-once request
curl -sS -X POST http://localhost:8080/invocations \
  -H "Content-Type: text/plain" \
  -H "x-amzn-bedrock-agentcore-session-id: session-e2e-test-42" \
  -H "x-amzn-request-id: req-e2e-test-99" \
  --data $'Email: jane.doe@acme-corp.com\nSSN: 123-45-6789\nVisa: 4532-0151-1283-0366\nAWS key: AKIAIOSFODNN7EXAMPLE\nReply OK.'

# Give the exporter a moment to flush
sleep 2

# Show the masked span events
echo "--- Exported span content ---"
grep -E "gen_ai\.(prompt|completion)|aws\.(bedrock|request)|usage\.(input|output)_tokens" app.log
```

Run it, eyeball the grep output, confirm nothing unmasked leaks through.

---

## 10. Exporting to a real collector (OTLP)

`otel.traces.exporter=logging` is fine for development, but in production you want to send traces to a real backend. The OpenTelemetry Protocol (OTLP) is the standard way.

### 10.1 Route to an OpenTelemetry Collector

Swap three properties:

```properties
otel.traces.exporter=otlp
otel.metrics.exporter=otlp
otel.exporter.otlp.endpoint=http://otel-collector:4317
otel.exporter.otlp.protocol=grpc
otel.service.name=my-agent
```

The masking wrapper still applies — `PiiMaskingSpanExporter` wraps the OTLP exporter exactly the same way it wraps the logging exporter. No sensitive strings ever leave the process.

### 10.2 Common OTLP targets

| Backend | Endpoint / protocol |
|---------|---------------------|
| Jaeger (native OTLP, v1.35+) | `http://jaeger:4317` (grpc) |
| OpenTelemetry Collector | `http://otel-collector:4317` (grpc) |
| Grafana Tempo | `http://tempo:4317` (grpc) |
| Datadog agent | `http://datadog-agent:4317` (grpc) |
| New Relic | `https://otlp.nr-data.net:4317` (grpc, requires API key) |
| AWS X-Ray via ADOT collector | `http://adot-collector:4317` (grpc) |

### 10.3 Quick start with a local collector

Run the OTel Collector contrib image locally:

```bash
docker run --rm -p 4317:4317 -p 4318:4318 \
  -v "$(pwd)/otel-config.yaml:/etc/otel-collector-config.yaml" \
  otel/opentelemetry-collector-contrib:latest \
  --config=/etc/otel-collector-config.yaml
```

Minimal `otel-config.yaml` that just prints spans to its own stdout:

```yaml
receivers:
  otlp:
    protocols:
      grpc:
        endpoint: 0.0.0.0:4317
      http:
        endpoint: 0.0.0.0:4318

exporters:
  debug:
    verbosity: detailed

service:
  pipelines:
    traces:
      receivers: [otlp]
      exporters: [debug]
    metrics:
      receivers: [otlp]
      exporters: [debug]
```

Point your app at `http://localhost:4317` and you'll see masked spans land in the collector's logs.

---

## 11. Testing — assert on exported spans

You want a test that proves "my agent produces a span with `gen_ai.usage.input_tokens > 0` and `aws.request_id` set". The `InMemorySpanExporter` from `opentelemetry-sdk-testing` lets you capture every exported span and inspect it.

### 11.1 Register an in-memory exporter (test scope)

`src/test/java/com/example/demo/InMemoryExporterConfig.java`:

```java
package com.example.demo;

import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizerProvider;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;

import org.springaicommunity.agentcore.observability.masking.PiiMasker;
import org.springaicommunity.agentcore.observability.masking.PiiMaskingSpanExporter;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Replaces whatever exporter was configured with an in-memory one wrapped
 * in PiiMaskingSpanExporter so tests can assert on masked content.
 */
@AutoConfiguration
public class InMemoryExporterConfig {

  public static final InMemorySpanExporter EXPORTER = InMemorySpanExporter.create();

  @Bean
  AutoConfigurationCustomizerProvider inMemoryExporter(PiiMasker masker) {
    return customizer -> customizer.addSpanExporterCustomizer(
        (delegate, unused) -> new PiiMaskingSpanExporter(EXPORTER, masker));
  }
}
```

Register it via `src/test/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`:

```
com.example.demo.InMemoryExporterConfig
```

### 11.2 Write the test

`src/test/java/com/example/demo/AgentObservabilityTest.java`:

```java
package com.example.demo;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import io.opentelemetry.sdk.trace.data.SpanData;

import org.junit.jupiter.api.Test;
import org.springaicommunity.agentcore.observability.telemetry.GenAiTelemetrySupport;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        "spring.ai.agentcore.observability.capture-content=true",
        "otel.traces.exporter=logging"
    })
class AgentObservabilityTest {

  @LocalServerPort int port;
  @Autowired TestRestTemplate rest;

  @Test
  void spanCarriesGenAiAttributesAndMasksPii() throws Exception {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.TEXT_PLAIN);
    headers.set("x-amzn-bedrock-agentcore-session-id", "test-session");
    headers.set("x-amzn-request-id", "test-request");

    String prompt = "Jane (jane@example.com, SSN 123-45-6789). Reply: OK";
    rest.postForObject("/invocations", new HttpEntity<>(prompt, headers), String.class);

    Thread.sleep(500);  // let the batch span processor flush

    List<SpanData> spans = InMemoryExporterConfig.EXPORTER.getFinishedSpanItems();
    SpanData span = spans.stream()
        .filter(s -> s.getAttributes().get(GenAiTelemetrySupport.GEN_AI_PROVIDER_NAME) != null)
        .findFirst()
        .orElseThrow();

    assertThat(span.getAttributes().get(GenAiTelemetrySupport.GEN_AI_PROVIDER_NAME))
        .isEqualTo("aws.bedrock");
    assertThat(span.getAttributes().get(GenAiTelemetrySupport.GEN_AI_USAGE_INPUT_TOKENS))
        .isGreaterThan(0L);
    assertThat(span.getAttributes().get(GenAiTelemetrySupport.AWS_REQUEST_ID))
        .isEqualTo("test-request");

    String maskedPrompt = span.getEvents().stream()
        .filter(e -> GenAiTelemetrySupport.EVENT_GEN_AI_CONTENT_PROMPT.equals(e.getName()))
        .map(e -> e.getAttributes().get(GenAiTelemetrySupport.GEN_AI_PROMPT))
        .findFirst()
        .orElseThrow();

    assertThat(maskedPrompt).doesNotContain("jane@example.com");
    assertThat(maskedPrompt).contains("j***@***.com");
    assertThat(maskedPrompt).doesNotContain("123-45-6789");
    assertThat(maskedPrompt).contains("###-##-####");
  }
}
```

### 11.3 Run

```bash
mvn test
```

This test calls real Bedrock. If you want a test that doesn't hit AWS, mock `ChatModel` and return a fixed `ChatResponse` in a `@TestConfiguration`.

---

## 12. Troubleshooting

Every problem we hit during end-to-end validation of this module, and how to fix it.

### 12.1 "Captured 0 spans" — nothing shows up

**Cause 1:** `otel.traces.exporter=none`. Setting this to `none` disables the OTel SDK entirely.
**Fix:** use `logging` for dev or `otlp` for prod.

**Cause 2:** Your `application.properties` is empty on disk. This happens surprisingly often when file-writes silently fail.
**Fix:** build the jar, extract and inspect the bundled copy:

```bash
jar -xf target/my-agent-1.0.0-SNAPSHOT.jar BOOT-INF/classes/application.properties
cat BOOT-INF/classes/application.properties
```

If the file is empty, rewrite it and rebuild.

### 12.2 Prompt/completion events are missing from spans

**Cause:** `capture-content` is still at its default of `false`.
**Fix:** add `spring.ai.agentcore.observability.capture-content=true` to `application.properties`.

**Verify the property reached the app** by adding a temporary diagnostic bean:

```java
@Bean
ApplicationRunner captureCheck(Environment env) {
  return args -> System.out.println("capture-content = " +
      env.getProperty("spring.ai.agentcore.observability.capture-content"));
}
```

You should see `capture-content = true` at startup.

### 12.3 Emails/SSNs are still visible in spans

**Cause 1:** `masking.enabled=false` somewhere (property files win over defaults).
**Cause 2:** You're looking at application **logs**, not **spans**. Masking applies only to OpenTelemetry span export. Log output uses a different pipeline.

**Fix:** for log redaction, configure your logging framework (Logback `<converter>` or Log4j2 `<PatternLayout>` with a custom converter).

### 12.4 `aws.request_id` attribute missing

**Cause 1:** The caller didn't send the header (or sent a non-standard spelling the module doesn't recognize).
**Cause 2:** `spring-web` is not on the classpath — the aspect has no way to reach the servlet request.

**Fix:** confirm `spring-boot-starter-web` is in your dependency tree (`mvn dependency:tree | grep spring-web`). The AgentCore starter should pull it transitively, but it's worth checking.

### 12.5 `ClassNotFoundException: org.springaicommunity.agentcore.annotation.AgentCoreInvocation`

**Cause:** the observability auto-config is `@ConditionalOnClass(AgentCoreInvocation.class)`. If the agentcore dependency isn't present, the starter silently does nothing.
**Fix:** add `spring-ai-agentcore` explicitly to your `pom.xml`. The observability starter should bring it transitively, but if you've added exclusions, you may have removed it by accident.

### 12.6 `AccessDeniedException` from Bedrock

**Cause 1:** Model access not enabled in the Bedrock console.
**Fix:** AWS console → Bedrock → Model access → request access for `amazon.nova-lite-v1:0`.

**Cause 2:** IAM principal doesn't have the required actions.
**Fix:** attach the inline policy from section 2.3.5.

**Cause 3:** Wrong region.
**Fix:** confirm `AWS_REGION` or `spring.ai.bedrock.aws.region` matches where the model is enabled.

### 12.7 `ThrottlingException`

**Cause:** Per-account or per-region Bedrock TPS limit hit.
**Fix:** request a service quota increase, switch to a cross-region inference profile, or backoff. The span will carry `error.type=rate_limit` automatically so you can alert on it.

### 12.8 Spans from OTLP exporter but masking isn't applied

**Cause:** You registered a custom `AutoConfigurationCustomizerProvider` that adds a span exporter **after** ours. The customizer chain matters.
**Fix:** wrap your exporter in `PiiMaskingSpanExporter` yourself, or ensure your customizer runs before `agentCorePiiMaskingSpanExporterCustomizer` using `@AutoConfiguration(before = AgentCoreObservabilityAutoConfiguration.class)`.

---

## 13. FAQ

**Q: Does this instrument the Spring AI `ChatModel` call itself?**
No. Spring AI has its own observation hooks for that. This module instruments the AgentCore HTTP boundary. Together they give you a parent `chat` span (AgentCore) and child spans for the model call (Spring AI) when both are enabled.

**Q: What happens on streaming (`Flux<ChatResponse>`)?**
The aspect subscribes to the flux, records token usage from the final chunk only (intermediate chunks usually have empty usage in Bedrock), and ends the span in `doFinally` after the stream completes.

**Q: Can I turn off masking entirely?**
Yes: `spring.ai.agentcore.observability.masking.enabled=false`. But then any PII in prompts/completions flows to your collector unmasked. Think twice.

**Q: Does it work with AWS X-Ray?**
Yes, via the AWS Distro for OpenTelemetry (ADOT) collector. Point your app at the ADOT collector's OTLP endpoint and X-Ray will see your `gen_ai.*` and `aws.*` attributes as regular span attributes.

**Q: Does the masker affect performance?**
Regex compilation happens once at bean construction. Per-span cost is bounded by the number of enabled patterns (4 defaults + your custom ones) and the length of the string. For typical prompt sizes (1–2 KB) the overhead is in microseconds and only applied to strings that are about to be exported anyway.

**Q: Can I use this without Bedrock?**
The aspect is Bedrock-oriented (sets `gen_ai.provider.name=aws.bedrock` statically) but the module will instrument any `@AgentCoreInvocation` method. If you're using a different provider, the `gen_ai.*` attributes will still be populated — just hard-coded to `aws.bedrock` in this version. A provider-agnostic variant is on the roadmap.

**Q: Does it work in Spring WebFlux?**
Yes. The aspect handles both `Mono<ChatResponse>` and `Flux<ChatResponse>` return types. Header resolution also works via `ServerWebExchange`.

**Q: What's the minimum Spring Boot version?**
3.5.x. The module was compiled against `spring-boot-starter-parent 3.5.9`.

**Q: Where's the source?**
`github.com/vaquarkhan/spring-ai-agentcore-observability` (feature/enhancement branch). A merge proposal to `spring-ai-community/spring-ai-agentcore` is tracked in issue #50.

---

## Conclusion

You now have a Spring Boot agent that produces OpenTelemetry traces with GenAI semantic attributes, AWS correlation IDs, token-usage metrics, and PII-safe content capture — all from two lines of `<dependency>` and a handful of properties.

What you gained:

- **Cost visibility.** Every invocation carries token counts broken down by model, so you can slice Bedrock spend in Grafana / Datadog / CloudWatch without custom instrumentation.
- **Cross-system correlation.** The two AWS headers on every span let you join traces from your app to Bedrock's own logs and to the AgentCore session that triggered them.
- **Compliance by default.** Emails, SSNs, credit cards (Luhn + issuer-validated), phone numbers, and any custom patterns you add are redacted before spans leave your JVM. Your observability vendor never sees raw PII.
- **Error classification.** Failures are labeled `rate_limit`, `timeout`, `authentication_failure`, `invalid_request`, or `server_error` automatically — your alerts can distinguish a throttling incident from a code bug in one query.
- **Zero drift.** No hand-written tracing code to rot. Every new agent method added to the app gets the same treatment with no extra work.

What to do next:

1. Swap `otel.traces.exporter=logging` for `otlp` and point at your real collector (section 10).
2. Add your organization-specific custom regex patterns (API keys, internal IDs, non-US PII formats) to `masking.custom-regex` (section 7.3).
3. Write a smoke test that asserts `gen_ai.provider.name` is set and no raw email appears in any span (section 11).
4. Build the dashboards your finance team will thank you for (per-model token-spend, p95 latency by model, error-rate by `error.type`).

The module is production-ready and battle-tested against live Bedrock. Ship it.

---

**Feedback / issues:** [spring-ai-agentcore-observability on GitHub](https://github.com/vaquarkhan/spring-ai-agentcore-observability)

**Related docs:**
- [OpenTelemetry GenAI semantic conventions](https://opentelemetry.io/docs/specs/semconv/gen-ai/gen-ai-spans/)
- [Spring AI reference](https://docs.spring.io/spring-ai/reference/)
- [Amazon Bedrock Converse API](https://docs.aws.amazon.com/bedrock/latest/userguide/conversation-inference.html)
