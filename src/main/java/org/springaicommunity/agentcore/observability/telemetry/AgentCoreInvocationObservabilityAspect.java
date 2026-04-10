/*
 * Copyright 2026 Vaquar Khan
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springaicommunity.agentcore.observability.telemetry;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.core.env.Environment;

/**
 * Wraps the AgentCore HTTP controller entry points (not the user {@code @AgentCoreInvocation} bean)
 * so Spring AOP does not proxy agent implementations. AgentCore's method scanner reflects on each
 * bean type; CGLIB proxies for advised services may not retain annotations on overridden methods,
 * which would prevent registration of invocation handlers.
 *
 * @author Vaquar Khan
 */
@Aspect
public class AgentCoreInvocationObservabilityAspect {

  private final Tracer tracer;
  private final Environment environment;
  private final DoubleHistogram tokenUsageHistogram;

  public AgentCoreInvocationObservabilityAspect(
      Tracer tracer, Meter meter, Environment environment) {
    this.tracer = tracer;
    this.environment = environment;
    this.tokenUsageHistogram =
        meter
            .histogramBuilder(GenAiTelemetrySupport.METRIC_GEN_AI_CLIENT_TOKEN_USAGE)
            .setUnit("{token}")
            .build();
  }

  @Around(
      "execution(* org.springaicommunity.agentcore.controller.AgentCoreInvocationsController.handleJsonInvocation(..))"
          + " || execution(* org.springaicommunity.agentcore.controller.AgentCoreInvocationsController.handleTextInvocation(..))")
  public Object aroundAgentCoreController(ProceedingJoinPoint joinPoint) throws Throwable {
    String spanName = GenAiTelemetrySupport.OP_CHAT;
    Span span =
        tracer
            .spanBuilder(spanName)
            .setSpanKind(SpanKind.INTERNAL)
            .startSpan();
    try (Scope scope = span.makeCurrent()) {
      Object result = joinPoint.proceed();
      if (result instanceof ChatResponse response) {
        applyGenAiAttributes(span, joinPoint, response);
      }
      span.setStatus(StatusCode.OK);
      return result;
    } catch (Throwable t) {
      span.setStatus(StatusCode.ERROR);
      span.recordException(t);
      span.setAttribute(
          GenAiTelemetrySupport.ERROR_TYPE, errorType(t));
      throw t;
    } finally {
      span.end();
    }
  }

  private void applyGenAiAttributes(Span span, ProceedingJoinPoint joinPoint, ChatResponse response) {
    String operation =
        response.hasToolCalls()
            ? GenAiTelemetrySupport.OP_EXECUTE_TOOL
            : GenAiTelemetrySupport.OP_CHAT;
    span.updateName(operation);

    span.setAttribute(GenAiTelemetrySupport.GEN_AI_OPERATION_NAME, operation);
    span.setAttribute(GenAiTelemetrySupport.GEN_AI_PROVIDER_NAME, GenAiTelemetrySupport.PROVIDER_AWS_BEDROCK);
    span.setAttribute(GenAiTelemetrySupport.GEN_AI_SYSTEM, GenAiTelemetrySupport.PROVIDER_AWS_BEDROCK);

    ChatResponseMetadata metadata = response.getMetadata();
    if (metadata != null) {
      Optional.ofNullable(metadata.getModel())
          .ifPresent(m -> span.setAttribute(GenAiTelemetrySupport.GEN_AI_REQUEST_MODEL, m));
      Optional.ofNullable(metadata.getModel())
          .ifPresent(m -> span.setAttribute(GenAiTelemetrySupport.GEN_AI_RESPONSE_MODEL, m));
    }

    Usage usage = metadata != null ? metadata.getUsage() : null;
    long inputTokens = baseInputTokens(usage) + cacheReadTokens(metadata);
    long outputTokens = usage != null && usage.getCompletionTokens() != null
        ? usage.getCompletionTokens().longValue()
        : 0L;

    span.setAttribute(GenAiTelemetrySupport.GEN_AI_USAGE_INPUT_TOKENS, inputTokens);
    span.setAttribute(GenAiTelemetrySupport.GEN_AI_USAGE_OUTPUT_TOKENS, outputTokens);

    List<String> finish = new ArrayList<>();
    for (Generation g : response.getResults()) {
      if (g.getMetadata() != null && g.getMetadata().getFinishReason() != null) {
        finish.add(g.getMetadata().getFinishReason());
      }
    }
    if (!finish.isEmpty()) {
      span.setAttribute(
          GenAiTelemetrySupport.GEN_AI_RESPONSE_FINISH_REASONS,
          finish.stream().distinct().collect(Collectors.joining(",")));
    }

    String modelAttr =
        metadata != null && metadata.getModel() != null ? metadata.getModel() : "unknown";
    Attributes inputMetricAttrs =
        Attributes.builder()
            .put(GenAiTelemetrySupport.GEN_AI_TOKEN_TYPE, "input")
            .put(GenAiTelemetrySupport.GEN_AI_REQUEST_MODEL, modelAttr)
            .put(GenAiTelemetrySupport.GEN_AI_PROVIDER_NAME, GenAiTelemetrySupport.PROVIDER_AWS_BEDROCK)
            .build();
    Attributes outputMetricAttrs =
        Attributes.builder()
            .put(GenAiTelemetrySupport.GEN_AI_TOKEN_TYPE, "output")
            .put(GenAiTelemetrySupport.GEN_AI_REQUEST_MODEL, modelAttr)
            .put(GenAiTelemetrySupport.GEN_AI_PROVIDER_NAME, GenAiTelemetrySupport.PROVIDER_AWS_BEDROCK)
            .build();
    tokenUsageHistogram.record((double) inputTokens, inputMetricAttrs);
    tokenUsageHistogram.record((double) outputTokens, outputMetricAttrs);

    if (captureContent()) {
      String prompt = extractPrompt(joinPoint.getArgs());
      if (prompt != null) {
        span.addEvent(
            GenAiTelemetrySupport.EVENT_GEN_AI_CONTENT_PROMPT,
            Attributes.of(GenAiTelemetrySupport.GEN_AI_PROMPT, prompt));
      }
      String completion = extractCompletion(response);
      if (completion != null) {
        span.addEvent(
            GenAiTelemetrySupport.EVENT_GEN_AI_CONTENT_COMPLETION,
            Attributes.of(GenAiTelemetrySupport.GEN_AI_COMPLETION, completion));
      }
    }
  }

  private static long baseInputTokens(Usage usage) {
    if (usage == null || usage.getPromptTokens() == null) {
      return 0L;
    }
    return usage.getPromptTokens().longValue();
  }

  private static long cacheReadTokens(ChatResponseMetadata metadata) {
    if (metadata == null) {
      return 0L;
    }
    Object v = metadata.get("cacheReadInputTokens");
    if (v instanceof Number n) {
      return n.longValue();
    }
    return 0L;
  }

  private boolean captureContent() {
    String envFlag = environment.getProperty("OTEL_GENAI_CAPTURE_CONTENT");
    if (envFlag == null) {
      envFlag = System.getenv("OTEL_GENAI_CAPTURE_CONTENT");
    }
    return Boolean.parseBoolean(envFlag);
  }

  private static String extractPrompt(Object[] args) {
    if (args == null) {
      return null;
    }
    for (Object a : args) {
      if (a instanceof String s) {
        return s;
      }
    }
    return null;
  }

  private static String extractCompletion(ChatResponse response) {
    try {
      Generation gen = response.getResult();
      if (gen != null && gen.getOutput() != null) {
        return gen.getOutput().getText();
      }
    } catch (RuntimeException ignored) {
      // Tool-only generations may omit textual content; avoid failing the aspect.
    }
    return null;
  }

  private static String errorType(Throwable t) {
    String name = t.getClass().getSimpleName().toLowerCase(Locale.ROOT);
    if (name.contains("timeout")) {
      return "timeout";
    }
    if (name.contains("auth") || name.contains("security")) {
      return "authentication_failure";
    }
    return name;
  }
}
