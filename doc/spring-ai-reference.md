# Spring AI reference

## Official documentation

- [Spring AI reference — home](https://docs.spring.io/spring-ai/reference/index.html)
- [Chat model API](https://docs.spring.io/spring-ai/reference/api/chatmodel.html) — `ChatClient`, chat models.
- [Prompts](https://docs.spring.io/spring-ai/reference/api/prompt.html) — prompt templates and messages.
- [Observability (Spring AI)](https://docs.spring.io/spring-ai/reference/observability/index.html) — Spring AI’s own observation hooks (complementary to this MVP’s AgentCore-level spans).

## Types used in this MVP

- **`org.springframework.ai.chat.model.ChatResponse`** — Result of a chat completion; carries `ChatResponseMetadata`, `Generation` list, tool-call flags.
- **`org.springframework.ai.chat.metadata.Usage`** — `getPromptTokens()`, `getCompletionTokens()`; Bedrock-specific cache fields often appear in the **metadata map** (e.g. `cacheReadInputTokens`).
- **`org.springframework.ai.chat.metadata.ChatResponseMetadata`** — Model id, usage, and extensible `keyValue` / map entries for provider-specific fields.

## AWS Bedrock in Spring AI

- [Bedrock Converse API](https://docs.spring.io/spring-ai/reference/api/chat/bedrock-converse.html) — high-level mapping for Bedrock chat.
- [AWS Bedrock prompt caching (Spring blog)](https://spring.io/blog/aws-bedrock-prompt-caching-support-in-spring-ai) — relates to cache token fields in responses.

This repository does **not** replace Spring AI’s model clients; it **enriches telemetry** when responses are already `ChatResponse` instances returned through the AgentCore `/invocations` flow.
