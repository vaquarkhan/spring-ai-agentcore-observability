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

package org.springaicommunity.agentcore.observability.masking;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.sdk.trace.data.DelegatingSpanData;
import io.opentelemetry.sdk.trace.data.EventData;
import io.opentelemetry.sdk.trace.data.SpanData;
import java.util.ArrayList;
import java.util.List;
import org.springaicommunity.agentcore.observability.telemetry.GenAiTelemetrySupport;

/**
 * Delegates to an underlying {@link SpanData} while masking string attribute values on the span and
 * on span events (including GenAI prompt/completion payloads).
 *
 * @author Vaquar Khan
 */
final class MaskingSpanData extends DelegatingSpanData {

  private final PiiMasker masker;
  private Attributes maskedAttributes;
  private List<EventData> maskedEvents;

  MaskingSpanData(SpanData delegate, PiiMasker masker) {
    super(delegate);
    this.masker = masker;
  }

  @Override
  public Attributes getAttributes() {
    if (maskedAttributes == null) {
      maskedAttributes = maskAttributes(super.getAttributes());
    }
    return maskedAttributes;
  }

  @Override
  public List<EventData> getEvents() {
    if (maskedEvents == null) {
      maskedEvents = maskEvents(super.getEvents());
    }
    return maskedEvents;
  }

  private Attributes maskAttributes(Attributes attributes) {
    AttributesBuilder b = attributes.toBuilder();
    attributes.forEach(
        (key, value) -> {
          if (value instanceof String) {
            @SuppressWarnings("unchecked")
            AttributeKey<String> sk = (AttributeKey<String>) (AttributeKey<?>) key;
            b.put(sk, masker.mask((String) value));
          }
        });
    return b.build();
  }

  private List<EventData> maskEvents(List<EventData> events) {
    List<EventData> out = new ArrayList<>(events.size());
    for (EventData e : events) {
      Attributes masked = maskEventAttributes(e.getName(), e.getAttributes());
      out.add(EventData.create(e.getEpochNanos(), e.getName(), masked, e.getTotalAttributeCount()));
    }
    return out;
  }

  private Attributes maskEventAttributes(String eventName, Attributes attributes) {
    AttributesBuilder b = attributes.toBuilder();
    attributes.forEach(
        (key, value) -> {
          if (value instanceof String) {
            @SuppressWarnings("unchecked")
            AttributeKey<String> sk = (AttributeKey<String>) (AttributeKey<?>) key;
            String s = (String) value;
            if (shouldMaskEventAttribute(eventName, key)) {
              b.put(sk, masker.mask(s));
            } else {
              b.put(sk, s);
            }
          }
        });
    return b.build();
  }

  private boolean shouldMaskEventAttribute(String eventName, AttributeKey<?> key) {
    String kn = key.getKey();
    if (GenAiTelemetrySupport.EVENT_GEN_AI_CONTENT_PROMPT.equals(eventName)
        && GenAiTelemetrySupport.GEN_AI_PROMPT.getKey().equals(kn)) {
      return true;
    }
    if (GenAiTelemetrySupport.EVENT_GEN_AI_CONTENT_COMPLETION.equals(eventName)
        && GenAiTelemetrySupport.GEN_AI_COMPLETION.getKey().equals(kn)) {
      return true;
    }
    // Defensive: mask any string attribute on GenAI content events
    if (eventName.startsWith("gen_ai.content.")) {
      return true;
    }
    return kn.contains("prompt") || kn.contains("completion") || kn.contains("password");
  }
}
