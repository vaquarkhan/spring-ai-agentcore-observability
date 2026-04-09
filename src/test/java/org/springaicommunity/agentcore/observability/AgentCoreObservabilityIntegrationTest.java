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
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizerProvider;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;

@SpringBootTest(classes = AgentCoreObservabilitySampleApplication.class)
@AutoConfigureMockMvc
@Import(AgentCoreObservabilityIntegrationTest.OtelInMemoryExporterConfig.class)
@TestPropertySource(
    properties = {
      "OTEL_GENAI_CAPTURE_CONTENT=true",
      "otel.traces.exporter=logging",
      "otel.metrics.exporter=none",
      "otel.logs.exporter=none"
    })
class AgentCoreObservabilityIntegrationTest {

  @Autowired private MockMvc mockMvc;

  @AfterEach
  void resetExporter() {
    OtelInMemoryExporterConfig.SPAN_EXPORTER.reset();
  }

  @Test
  void invocationsEmitsGenAiAttributesAndMasksPiiInExportedSpans() throws Exception {
    String body =
        "Hello jane@example.com SSN 123-45-6789 card 4111111111111111 phone 212-555-0199";

    mockMvc
        .perform(
            MockMvcRequestBuilders.post("/invocations")
                .contentType(MediaType.TEXT_PLAIN)
                .content(body))
        .andExpect(MockMvcResultMatchers.status().isOk());

    List<SpanData> spans = OtelInMemoryExporterConfig.SPAN_EXPORTER.getFinishedSpanItems();
    Optional<SpanData> genAi =
        spans.stream()
            .filter(s -> s.getAttributes().get(GenAiTelemetrySupport.GEN_AI_PROVIDER_NAME) != null)
            .findFirst();

    assertThat(genAi).isPresent();
    SpanData span = genAi.orElseThrow();
    assertThat(span.getAttributes().get(GenAiTelemetrySupport.GEN_AI_PROVIDER_NAME))
        .isEqualTo(GenAiTelemetrySupport.PROVIDER_AWS_BEDROCK);
    assertThat(span.getAttributes().get(GenAiTelemetrySupport.GEN_AI_USAGE_INPUT_TOKENS))
        .isEqualTo(45L);
    assertThat(span.getAttributes().get(GenAiTelemetrySupport.GEN_AI_USAGE_OUTPUT_TOKENS))
        .isEqualTo(7L);

    String promptEventPayload = findEventAttribute(span, GenAiTelemetrySupport.EVENT_GEN_AI_CONTENT_PROMPT, "gen_ai.prompt");
    assertThat(promptEventPayload).isNotNull();
    assertThat(promptEventPayload).doesNotContain("jane@example.com");
    assertThat(promptEventPayload).contains("j***@***.com");
    assertThat(promptEventPayload).contains("###-##-####");
    assertThat(promptEventPayload).contains("4111-****-****-1111");
  }

  private static String findEventAttribute(
      SpanData span, String eventName, String attributeKey) {
    return span.getEvents().stream()
        .filter(e -> eventName.equals(e.getName()))
        .map(e -> e.getAttributes().get(AttributeKey.stringKey(attributeKey)))
        .findFirst()
        .orElse(null);
  }

  @TestConfiguration
  static class OtelInMemoryExporterConfig {

    static final InMemorySpanExporter SPAN_EXPORTER = InMemorySpanExporter.create();

    @Bean
    AutoConfigurationCustomizerProvider routeTracesToInMemory(PiiMasker piiMasker) {
      return customizer ->
          customizer.addSpanExporterCustomizer(
              (delegate, unused) -> new PiiMaskingSpanExporter(SPAN_EXPORTER, piiMasker));
    }
  }
}
