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

package org.springaicommunity.agentcore.observability.autoconfigure;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizerProvider;
import org.springaicommunity.agentcore.annotation.AgentCoreInvocation;
import org.springaicommunity.agentcore.observability.masking.PiiMasker;
import org.springaicommunity.agentcore.observability.masking.PiiMaskingSpanExporter;
import org.springaicommunity.agentcore.observability.telemetry.AgentCoreInvocationObservabilityAspect;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

/**
 * Registers the GenAI enrichment aspect and wraps the configured OTLP span exporter with {@link
 * PiiMaskingSpanExporter}.
 *
 * @author Vaquar Khan
 */
@AutoConfiguration
@ConditionalOnClass(AgentCoreInvocation.class)
public class AgentCoreObservabilityAutoConfiguration {

  private static final String INSTRUMENTATION_SCOPE = "org.springaicommunity.agentcore.observability";

  @Bean
  public PiiMasker agentCorePiiMasker() {
    return new PiiMasker();
  }

  @Bean
  public AutoConfigurationCustomizerProvider agentCorePiiMaskingSpanExporterCustomizer(
      PiiMasker piiMasker) {
    return customizer ->
        customizer.addSpanExporterCustomizer(
            (spanExporter, config) -> new PiiMaskingSpanExporter(spanExporter, piiMasker));
  }

  @Bean
  public Tracer agentCoreObservabilityTracer(OpenTelemetry openTelemetry) {
    return openTelemetry.getTracer(INSTRUMENTATION_SCOPE);
  }

  @Bean
  public Meter agentCoreObservabilityMeter(OpenTelemetry openTelemetry) {
    return openTelemetry.getMeter(INSTRUMENTATION_SCOPE);
  }

  @Bean
  public AgentCoreInvocationObservabilityAspect agentCoreInvocationObservabilityAspect(
      Tracer agentCoreObservabilityTracer,
      Meter agentCoreObservabilityMeter,
      Environment environment) {
    return new AgentCoreInvocationObservabilityAspect(
        agentCoreObservabilityTracer, agentCoreObservabilityMeter, environment);
  }
}
