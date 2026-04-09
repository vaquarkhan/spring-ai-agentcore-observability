# AWS Bedrock AgentCore and Spring

## Upstream project

- [spring-ai-community / spring-ai-bedrock-agentcore](https://github.com/spring-ai-community/spring-ai-bedrock-agentcore) - Spring Boot starter for the **AgentCore Runtime** contract (`POST /invocations`, `GET /ping`, etc.).
- [Mintlify - Spring AI Bedrock AgentCore](https://springaicommunity.mintlify.app/projects/incubating/spring-ai-bedrock-agentcore) - quick start and modules.

## Runtime contract (summary)

- **`POST /invocations`** - Invokes the user method annotated with `@AgentCoreInvocation`.
- **`GET /ping`** - Health for the hosted runtime.

Artifact used in this MVP: **`org.springaicommunity:spring-ai-bedrock-agentcore-starter`**.

## Why this MVP advises the controller

AgentCore registers `@AgentCoreInvocation` methods by scanning beans. Spring AOP proxies on the **agent bean** can interfere with that registration. This project's aspect targets **`AgentCoreInvocationsController`** instead so the agent implementation stays a normal `@Service` bean. See the main [README](../README.md) and Javadoc on `AgentCoreInvocationObservabilityAspect`.

## AWS documentation

- [Observe agent applications on Amazon Bedrock AgentCore](https://docs.aws.amazon.com/bedrock/latest/userguide/agentcore-observability.html) - platform-side observability; this MVP adds **application-side** OpenTelemetry GenAI attributes and masking.
