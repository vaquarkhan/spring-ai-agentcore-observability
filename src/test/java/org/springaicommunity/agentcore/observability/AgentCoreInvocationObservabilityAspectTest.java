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

package org.springaicommunity.agentcore.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.DoubleHistogramBuilder;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeoutException;
import javax.naming.AuthenticationException;
import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.core.env.Environment;
import org.springframework.mock.env.MockEnvironment;

class AgentCoreInvocationObservabilityAspectTest {

  private Tracer tracer;
  private SpanBuilder spanBuilder;
  private Span span;
  private Scope scope;
  private Meter meter;
  private DoubleHistogramBuilder histogramBuilder;
  private DoubleHistogram histogram;
  private AgentCoreInvocationObservabilityAspect aspect;

  @BeforeEach
  void setUp() {
    tracer = mock(Tracer.class);
    spanBuilder = mock(SpanBuilder.class);
    span = mock(Span.class);
    scope = mock(Scope.class);
    meter = mock(Meter.class);
    histogramBuilder = mock(DoubleHistogramBuilder.class);
    histogram = mock(DoubleHistogram.class);

    when(tracer.spanBuilder(anyString())).thenReturn(spanBuilder);
    when(spanBuilder.setSpanKind(any())).thenReturn(spanBuilder);
    when(spanBuilder.startSpan()).thenReturn(span);
    when(span.makeCurrent()).thenReturn(scope);
    when(meter.histogramBuilder(anyString())).thenReturn(histogramBuilder);
    when(histogramBuilder.setUnit(anyString())).thenReturn(histogramBuilder);
    when(histogramBuilder.build()).thenReturn(histogram);

    Environment env = new MockEnvironment();
    aspect = new AgentCoreInvocationObservabilityAspect(tracer, meter, env);
  }

  @Test
  void recordsOkWhenResultIsNotChatResponse() throws Throwable {
    ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
    when(pjp.proceed()).thenReturn("plain");

    Object out = aspect.aroundAgentCoreController(pjp);

    assertThat(out).isEqualTo("plain");
    verify(span).setStatus(StatusCode.OK);
    verify(histogram, never()).record(anyDouble(), any(Attributes.class));
  }

  @Test
  void recordsErrorAndMapsTimeout() throws Throwable {
    ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
    when(pjp.proceed()).thenThrow(new TimeoutException("t"));

    org.assertj.core.api.Assertions.assertThatThrownBy(() -> aspect.aroundAgentCoreController(pjp))
        .isInstanceOf(TimeoutException.class);

    ArgumentCaptor<String> err = ArgumentCaptor.forClass(String.class);
    verify(span).setAttribute(eq(GenAiTelemetrySupport.ERROR_TYPE), err.capture());
    assertThat(err.getValue()).isEqualTo("timeout");
  }

  @Test
  void mapsSecurityKeywordInExceptionName() throws Throwable {
    ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
    class SslSecurityException extends RuntimeException {}
    when(pjp.proceed()).thenThrow(new SslSecurityException());

    org.assertj.core.api.Assertions.assertThatThrownBy(() -> aspect.aroundAgentCoreController(pjp))
        .isInstanceOf(SslSecurityException.class);

    ArgumentCaptor<String> err = ArgumentCaptor.forClass(String.class);
    verify(span).setAttribute(eq(GenAiTelemetrySupport.ERROR_TYPE), err.capture());
    assertThat(err.getValue()).isEqualTo("authentication_failure");
  }

  @Test
  void mapsAuthenticationFailure() throws Throwable {
    ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
    when(pjp.proceed()).thenThrow(new AuthenticationException());

    org.assertj.core.api.Assertions.assertThatThrownBy(() -> aspect.aroundAgentCoreController(pjp))
        .isInstanceOf(AuthenticationException.class);

    ArgumentCaptor<String> err = ArgumentCaptor.forClass(String.class);
    verify(span).setAttribute(eq(GenAiTelemetrySupport.ERROR_TYPE), err.capture());
    assertThat(err.getValue()).isEqualTo("authentication_failure");
  }

  @Test
  void mapsGenericErrorType() throws Throwable {
    ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
    when(pjp.proceed()).thenThrow(new IllegalStateException("x"));

    org.assertj.core.api.Assertions.assertThatThrownBy(() -> aspect.aroundAgentCoreController(pjp))
        .isInstanceOf(IllegalStateException.class);

    ArgumentCaptor<String> err = ArgumentCaptor.forClass(String.class);
    verify(span).setAttribute(eq(GenAiTelemetrySupport.ERROR_TYPE), err.capture());
    assertThat(err.getValue()).isEqualTo("illegalstateexception");
  }

  @Test
  void applyGenAiUsesToolOperationWhenToolCallsPresent() throws Throwable {
    ChatResponse response = mock(ChatResponse.class);
    when(response.hasToolCalls()).thenReturn(true);
    when(response.getMetadata()).thenReturn(null);
    when(response.getResults()).thenReturn(Collections.emptyList());

    ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
    when(pjp.proceed()).thenReturn(response);

    aspect.aroundAgentCoreController(pjp);

    verify(span).updateName(GenAiTelemetrySupport.OP_EXECUTE_TOOL);
  }

  @Test
  void treatsNullCompletionTokensAsZero() throws Throwable {
    Usage usage = mock(Usage.class);
    when(usage.getPromptTokens()).thenReturn(3);
    when(usage.getCompletionTokens()).thenReturn(null);

    ChatResponse response =
        ChatResponse.builder()
            .metadata(ChatResponseMetadata.builder().model("m").usage(usage).build())
            .generations(
                List.of(
                    new Generation(
                        new AssistantMessage("x"),
                        org.springframework.ai.chat.metadata.ChatGenerationMetadata.builder()
                            .finishReason("stop")
                            .build())))
            .build();

    ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
    when(pjp.proceed()).thenReturn(response);

    aspect.aroundAgentCoreController(pjp);

    verify(span).setAttribute(GenAiTelemetrySupport.GEN_AI_USAGE_OUTPUT_TOKENS, 0L);
  }

  @Test
  void skipsFinishReasonWhenGenerationMetadataMissing() throws Throwable {
    ChatResponse response =
        ChatResponse.builder()
            .metadata(ChatResponseMetadata.builder().model("m").usage(new DefaultUsage(1, 1)).build())
            .generations(
                List.of(
                    new Generation(new AssistantMessage("x"), null),
                    new Generation(
                        new AssistantMessage("y"),
                        org.springframework.ai.chat.metadata.ChatGenerationMetadata.builder()
                            .finishReason("stop")
                            .build())))
            .build();

    ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
    when(pjp.proceed()).thenReturn(response);

    aspect.aroundAgentCoreController(pjp);

    verify(span).setAttribute(GenAiTelemetrySupport.GEN_AI_RESPONSE_FINISH_REASONS, "stop");
  }

  @Test
  void modelMetricsUseUnknownWhenModelGetterReturnsNull() throws Throwable {
    ChatResponseMetadata meta = mock(ChatResponseMetadata.class);
    when(meta.getUsage()).thenReturn(new DefaultUsage(1, 1));
    when(meta.getModel()).thenReturn(null);

    ChatResponse response =
        ChatResponse.builder()
            .metadata(meta)
            .generations(
                List.of(
                    new Generation(
                        new AssistantMessage("x"),
                        org.springframework.ai.chat.metadata.ChatGenerationMetadata.builder()
                            .finishReason("stop")
                            .build())))
            .build();

    ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
    when(pjp.proceed()).thenReturn(response);

    aspect.aroundAgentCoreController(pjp);

    verify(histogram, times(2)).record(anyDouble(), any(Attributes.class));
  }

  @Test
  void usesUnknownModelWhenMetadataMissingModel() throws Throwable {
    ChatResponse response =
        ChatResponse.builder()
            .metadata(ChatResponseMetadata.builder().usage(new DefaultUsage(1, 1)).build())
            .generations(Collections.emptyList())
            .build();

    ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
    when(pjp.proceed()).thenReturn(response);

    aspect.aroundAgentCoreController(pjp);

    verify(histogram, times(2)).record(anyDouble(), any(Attributes.class));
  }

  @Test
  void baseInputTokensNullPromptTokens() throws Throwable {
    Usage usage = mock(Usage.class);
    when(usage.getPromptTokens()).thenReturn(null);
    when(usage.getCompletionTokens()).thenReturn(2);

    ChatResponse response =
        ChatResponse.builder()
            .metadata(ChatResponseMetadata.builder().model("m").usage(usage).build())
            .generations(Collections.emptyList())
            .build();

    ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
    when(pjp.proceed()).thenReturn(response);

    aspect.aroundAgentCoreController(pjp);

    verify(span).setAttribute(GenAiTelemetrySupport.GEN_AI_USAGE_INPUT_TOKENS, 0L);
  }

  @Test
  void extractCompletionReturnsNullWhenOutputMissing() throws Throwable {
    MockEnvironment env = new MockEnvironment();
    env.setProperty("OTEL_GENAI_CAPTURE_CONTENT", "true");
    aspect = new AgentCoreInvocationObservabilityAspect(tracer, meter, env);

    Generation gen = mock(Generation.class);
    AssistantMessage msg = mock(AssistantMessage.class);
    when(gen.getOutput()).thenReturn(msg);
    when(msg.getText()).thenReturn(null);

    ChatResponse response = mock(ChatResponse.class);
    when(response.hasToolCalls()).thenReturn(false);
    when(response.getMetadata())
        .thenReturn(ChatResponseMetadata.builder().model("m").usage(new DefaultUsage(1, 1)).build());
    when(response.getResults()).thenReturn(List.of(gen));
    when(response.getResult()).thenReturn(gen);

    ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
    when(pjp.getArgs()).thenReturn(new Object[] {"p"});
    when(pjp.proceed()).thenReturn(response);

    aspect.aroundAgentCoreController(pjp);

    verify(span, never())
        .addEvent(eq(GenAiTelemetrySupport.EVENT_GEN_AI_CONTENT_COMPLETION), any(Attributes.class));
  }

  @Test
  void appliesTokenHistogramsAndHandlesNullUsage() throws Throwable {
    ChatResponse response = mock(ChatResponse.class);
    when(response.hasToolCalls()).thenReturn(false);
    when(response.getMetadata()).thenReturn(null);
    when(response.getResults()).thenReturn(Collections.emptyList());

    ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
    when(pjp.proceed()).thenReturn(response);

    aspect.aroundAgentCoreController(pjp);

    verify(histogram, times(2)).record(anyDouble(), any(Attributes.class));
  }

  @Test
  void handlesNullPromptArgsAndSkipsPromptExtraction() throws Throwable {
    MockEnvironment env = new MockEnvironment();
    env.setProperty("OTEL_GENAI_CAPTURE_CONTENT", "true");
    aspect = new AgentCoreInvocationObservabilityAspect(tracer, meter, env);

    ChatResponse response =
        ChatResponse.builder()
            .metadata(ChatResponseMetadata.builder().model("m").usage(new DefaultUsage(1, 2)).build())
            .generations(
                List.of(
                    new Generation(
                        new AssistantMessage("hi"),
                        org.springframework.ai.chat.metadata.ChatGenerationMetadata.builder()
                            .finishReason("stop")
                            .build())))
            .build();

    ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
    when(pjp.getArgs()).thenReturn(null);
    when(pjp.proceed()).thenReturn(response);

    aspect.aroundAgentCoreController(pjp);

    verify(span).addEvent(anyString(), any(Attributes.class));
  }

  @Test
  void extractCompletionSwallowsRuntimeExceptionFromGeneration() throws Throwable {
    MockEnvironment env = new MockEnvironment();
    env.setProperty("OTEL_GENAI_CAPTURE_CONTENT", "true");
    aspect = new AgentCoreInvocationObservabilityAspect(tracer, meter, env);

    ChatResponse response = mock(ChatResponse.class);
    when(response.hasToolCalls()).thenReturn(false);
    when(response.getMetadata())
        .thenReturn(ChatResponseMetadata.builder().model("m").usage(new DefaultUsage(1, 1)).build());
    when(response.getResults())
        .thenReturn(
            List.of(
                new Generation(
                    new AssistantMessage("x"),
                    org.springframework.ai.chat.metadata.ChatGenerationMetadata.builder()
                        .finishReason("stop")
                        .build())));
    when(response.getResult()).thenThrow(new RuntimeException("no text"));

    ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
    when(pjp.getArgs()).thenReturn(new Object[] {"p"});
    when(pjp.proceed()).thenReturn(response);

    aspect.aroundAgentCoreController(pjp);

    verify(span, atLeastOnce()).addEvent(anyString(), any(Attributes.class));
  }

  @Test
  void addsCacheReadTokensFromMetadata() throws Throwable {
    ChatResponse response =
        ChatResponse.builder()
            .metadata(
                ChatResponseMetadata.builder()
                    .model("m")
                    .usage(new DefaultUsage(10, 2))
                    .keyValue("cacheReadInputTokens", 5L)
                    .build())
            .generations(
                List.of(
                    new Generation(
                        new AssistantMessage("out"),
                        org.springframework.ai.chat.metadata.ChatGenerationMetadata.builder()
                            .finishReason("stop")
                            .build())))
            .build();

    ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
    when(pjp.proceed()).thenReturn(response);

    aspect.aroundAgentCoreController(pjp);

    verify(span).setAttribute(GenAiTelemetrySupport.GEN_AI_USAGE_INPUT_TOKENS, 15L);
  }

  @Test
  void extractPromptReturnsNullWhenNoStringArgument() throws Throwable {
    MockEnvironment env = new MockEnvironment();
    env.setProperty("OTEL_GENAI_CAPTURE_CONTENT", "true");
    aspect = new AgentCoreInvocationObservabilityAspect(tracer, meter, env);

    ChatResponse response =
        ChatResponse.builder()
            .metadata(ChatResponseMetadata.builder().model("m").usage(new DefaultUsage(1, 1)).build())
            .generations(
                List.of(
                    new Generation(
                        new AssistantMessage("only-completion"),
                        org.springframework.ai.chat.metadata.ChatGenerationMetadata.builder()
                            .finishReason("stop")
                            .build())))
            .build();

    ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
    when(pjp.getArgs()).thenReturn(new Object[] {Integer.valueOf(42)});
    when(pjp.proceed()).thenReturn(response);

    aspect.aroundAgentCoreController(pjp);

    verify(span, never())
        .addEvent(eq(GenAiTelemetrySupport.EVENT_GEN_AI_CONTENT_PROMPT), any(Attributes.class));
  }

  @Test
  void extractCompletionReturnsNullWhenAssistantMessageMissing() throws Throwable {
    MockEnvironment env = new MockEnvironment();
    env.setProperty("OTEL_GENAI_CAPTURE_CONTENT", "true");
    aspect = new AgentCoreInvocationObservabilityAspect(tracer, meter, env);

    Generation gen = mock(Generation.class);
    when(gen.getOutput()).thenReturn(null);
    ChatResponse response = mock(ChatResponse.class);
    when(response.hasToolCalls()).thenReturn(false);
    when(response.getMetadata())
        .thenReturn(ChatResponseMetadata.builder().model("m").usage(new DefaultUsage(1, 1)).build());
    when(response.getResults()).thenReturn(List.of(gen));
    when(response.getResult()).thenReturn(gen);

    ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
    when(pjp.getArgs()).thenReturn(new Object[] {"p"});
    when(pjp.proceed()).thenReturn(response);

    aspect.aroundAgentCoreController(pjp);

    verify(span, never())
        .addEvent(eq(GenAiTelemetrySupport.EVENT_GEN_AI_CONTENT_COMPLETION), any(Attributes.class));
  }

  @Test
  void extractCompletionReturnsNullWhenGenerationMissing() throws Throwable {
    MockEnvironment env = new MockEnvironment();
    env.setProperty("OTEL_GENAI_CAPTURE_CONTENT", "true");
    aspect = new AgentCoreInvocationObservabilityAspect(tracer, meter, env);

    ChatResponse response = mock(ChatResponse.class);
    when(response.hasToolCalls()).thenReturn(false);
    when(response.getMetadata())
        .thenReturn(ChatResponseMetadata.builder().model("m").usage(new DefaultUsage(1, 1)).build());
    when(response.getResults()).thenReturn(Collections.emptyList());
    when(response.getResult()).thenReturn(null);

    ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
    when(pjp.getArgs()).thenReturn(new Object[] {"p"});
    when(pjp.proceed()).thenReturn(response);

    aspect.aroundAgentCoreController(pjp);

    verify(span, never())
        .addEvent(eq(GenAiTelemetrySupport.EVENT_GEN_AI_CONTENT_COMPLETION), any(Attributes.class));
  }

  @Test
  void ignoresNonNumericCacheReadTokens() throws Throwable {
    ChatResponse response =
        ChatResponse.builder()
            .metadata(
                ChatResponseMetadata.builder()
                    .model("m")
                    .usage(new DefaultUsage(10, 2))
                    .keyValue("cacheReadInputTokens", "nope")
                    .build())
            .generations(Collections.emptyList())
            .build();

    ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
    when(pjp.proceed()).thenReturn(response);

    aspect.aroundAgentCoreController(pjp);

    verify(span).setAttribute(GenAiTelemetrySupport.GEN_AI_USAGE_INPUT_TOKENS, 10L);
  }
}
