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

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.sdk.common.InstrumentationScopeInfo;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.testing.trace.TestSpanData;
import io.opentelemetry.sdk.trace.data.EventData;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.data.StatusData;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class MaskingSpanDataTest {

  private static final SpanContext SPAN_CONTEXT =
      SpanContext.create(
          "01234567890123456789012345678901",
          "0123456789012345",
          TraceFlags.getSampled(),
          TraceState.getDefault());

  @Test
  void masksStringAttributesAndCachesResults() {
    PiiMasker masker = new PiiMasker();
    SpanData base =
        TestSpanData.builder()
            .setSpanContext(SPAN_CONTEXT)
            .setResource(Resource.empty())
            .setInstrumentationScopeInfo(InstrumentationScopeInfo.empty())
            .setName("test")
            .setKind(SpanKind.INTERNAL)
            .setStartEpochNanos(TimeUnit.MILLISECONDS.toNanos(1))
            .setEndEpochNanos(TimeUnit.MILLISECONDS.toNanos(2))
            .setAttributes(
                Attributes.builder()
                    .put("plain", "user@example.com")
                    .put(AttributeKey.longKey("n"), 1L)
                    .build())
            .setStatus(StatusData.ok())
            .setHasEnded(true)
            .setTotalAttributeCount(2)
            .build();

    MaskingSpanData masked = new MaskingSpanData(base, masker);
    assertThat(masked.getAttributes().get(AttributeKey.stringKey("plain"))).contains("***");
    assertThat(masked.getAttributes().get(AttributeKey.longKey("n"))).isEqualTo(1L);
    assertThat(masked.getAttributes()).isSameAs(masked.getAttributes());
  }

  @Test
  void masksGenAiEventsAndGenericContentEvents() {
    PiiMasker masker = new PiiMasker();
    EventData promptEvent =
        EventData.create(
            TimeUnit.MILLISECONDS.toNanos(1),
            GenAiTelemetrySupport.EVENT_GEN_AI_CONTENT_PROMPT,
            Attributes.of(GenAiTelemetrySupport.GEN_AI_PROMPT, "secret@x.com"));
    EventData completionEvent =
        EventData.create(
            TimeUnit.MILLISECONDS.toNanos(2),
            GenAiTelemetrySupport.EVENT_GEN_AI_CONTENT_COMPLETION,
            Attributes.of(GenAiTelemetrySupport.GEN_AI_COMPLETION, "out@y.com"));
    EventData genericContent =
        EventData.create(
            TimeUnit.MILLISECONDS.toNanos(3),
            "gen_ai.content.custom",
            Attributes.of(AttributeKey.stringKey("payload"), "z@z.com"));
    EventData passwordKey =
        EventData.create(
            TimeUnit.MILLISECONDS.toNanos(4),
            "other",
            Attributes.of(AttributeKey.stringKey("user_password"), "secret@evil.com"));
    EventData passthrough =
        EventData.create(
            TimeUnit.MILLISECONDS.toNanos(5),
            "other",
            Attributes.of(AttributeKey.stringKey("safe"), "visible"));

    SpanData base =
        TestSpanData.builder()
            .setSpanContext(SPAN_CONTEXT)
            .setResource(Resource.empty())
            .setInstrumentationScopeInfo(InstrumentationScopeInfo.empty())
            .setName("e")
            .setKind(SpanKind.INTERNAL)
            .setStartEpochNanos(1)
            .setEndEpochNanos(2)
            .setEvents(List.of(promptEvent, completionEvent, genericContent, passwordKey, passthrough))
            .setStatus(StatusData.ok())
            .setHasEnded(true)
            .setTotalRecordedEvents(5)
            .build();

    MaskingSpanData masked = new MaskingSpanData(base, masker);
    assertThat(masked.getEvents()).hasSize(5);
    assertThat(masked.getEvents().get(0).getAttributes().get(GenAiTelemetrySupport.GEN_AI_PROMPT))
        .doesNotContain("@x.com");
    assertThat(masked.getEvents().get(1).getAttributes().get(GenAiTelemetrySupport.GEN_AI_COMPLETION))
        .doesNotContain("@y.com");
    assertThat(masked.getEvents().get(2).getAttributes().get(AttributeKey.stringKey("payload")))
        .doesNotContain("@z.com");
    assertThat(masked.getEvents().get(3).getAttributes().get(AttributeKey.stringKey("user_password")))
        .doesNotContain("secret@evil.com");
    assertThat(masked.getEvents().get(4).getAttributes().get(AttributeKey.stringKey("safe")))
        .isEqualTo("visible");
    assertThat(masked.getEvents()).isSameAs(masked.getEvents());
  }

  @Test
  void masksEventAttributesForPromptAndCompletionSemantics() {
    PiiMasker masker = new PiiMasker();
    EventData promptWrongKey =
        EventData.create(
            1L,
            GenAiTelemetrySupport.EVENT_GEN_AI_CONTENT_PROMPT,
            Attributes.of(AttributeKey.stringKey("other_key"), "a@b.com"));
    EventData completionWrongKey =
        EventData.create(
            2L,
            GenAiTelemetrySupport.EVENT_GEN_AI_CONTENT_COMPLETION,
            Attributes.of(AttributeKey.stringKey("other_key"), "c@d.com"));
    EventData mixedTypes =
        EventData.create(
            3L,
            "evt",
            Attributes.builder()
                .put(AttributeKey.longKey("n"), 1L)
                .put(AttributeKey.stringKey("note"), "visible")
                .build());

    SpanData base =
        TestSpanData.builder()
            .setSpanContext(SPAN_CONTEXT)
            .setResource(Resource.empty())
            .setInstrumentationScopeInfo(InstrumentationScopeInfo.empty())
            .setName("m")
            .setKind(SpanKind.INTERNAL)
            .setStartEpochNanos(1)
            .setEndEpochNanos(2)
            .setEvents(List.of(promptWrongKey, completionWrongKey, mixedTypes))
            .setStatus(StatusData.ok())
            .setHasEnded(true)
            .setTotalRecordedEvents(3)
            .build();

    MaskingSpanData masked = new MaskingSpanData(base, masker);
    assertThat(masked.getEvents().get(0).getAttributes().get(AttributeKey.stringKey("other_key")))
        .doesNotContain("a@b.com");
    assertThat(masked.getEvents().get(1).getAttributes().get(AttributeKey.stringKey("other_key")))
        .doesNotContain("c@d.com");
    assertThat(masked.getEvents().get(2).getAttributes().get(AttributeKey.longKey("n"))).isEqualTo(1L);
    assertThat(masked.getEvents().get(2).getAttributes().get(AttributeKey.stringKey("note")))
        .isEqualTo("visible");
  }

  @Test
  void masksByKeyNameHeuristics() {
    PiiMasker masker = new PiiMasker();
    EventData byPrompt =
        EventData.create(
            1L,
            "custom",
            Attributes.of(AttributeKey.stringKey("meta_prompt_id"), "a@b.com"));
    EventData byCompletion =
        EventData.create(
            2L,
            "custom",
            Attributes.of(AttributeKey.stringKey("completion_meta"), "c@d.com"));
    EventData byPassword =
        EventData.create(
            3L,
            "custom",
            Attributes.of(AttributeKey.stringKey("x_password_hint"), "e@f.com"));

    SpanData base =
        TestSpanData.builder()
            .setSpanContext(SPAN_CONTEXT)
            .setResource(Resource.empty())
            .setInstrumentationScopeInfo(InstrumentationScopeInfo.empty())
            .setName("m")
            .setKind(SpanKind.INTERNAL)
            .setStartEpochNanos(1)
            .setEndEpochNanos(2)
            .setEvents(List.of(byPrompt, byCompletion, byPassword))
            .setStatus(StatusData.ok())
            .setHasEnded(true)
            .setTotalRecordedEvents(3)
            .build();

    MaskingSpanData masked = new MaskingSpanData(base, masker);
    assertThat(masked.getEvents().get(0).getAttributes().get(AttributeKey.stringKey("meta_prompt_id")))
        .doesNotContain("a@b.com");
    assertThat(masked.getEvents().get(1).getAttributes().get(AttributeKey.stringKey("completion_meta")))
        .doesNotContain("c@d.com");
    assertThat(masked.getEvents().get(2).getAttributes().get(AttributeKey.stringKey("x_password_hint")))
        .doesNotContain("e@f.com");
  }

  @Test
  void leavesPlainAttributesWhenEventNameDoesNotMatchGenAiConventions() {
    PiiMasker masker = new PiiMasker();
    EventData emptyName =
        EventData.create(
            1L, "", Attributes.of(AttributeKey.stringKey("plain_body"), "visible@example.com"));

    SpanData base =
        TestSpanData.builder()
            .setSpanContext(SPAN_CONTEXT)
            .setResource(Resource.empty())
            .setInstrumentationScopeInfo(InstrumentationScopeInfo.empty())
            .setName("m")
            .setKind(SpanKind.INTERNAL)
            .setStartEpochNanos(1)
            .setEndEpochNanos(2)
            .setEvents(List.of(emptyName))
            .setStatus(StatusData.ok())
            .setHasEnded(true)
            .setTotalRecordedEvents(1)
            .build();

    MaskingSpanData masked = new MaskingSpanData(base, masker);
    assertThat(masked.getEvents().get(0).getAttributes().get(AttributeKey.stringKey("plain_body")))
        .isEqualTo("visible@example.com");
  }
}
