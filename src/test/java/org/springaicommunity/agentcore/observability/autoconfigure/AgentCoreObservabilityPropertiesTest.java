/*
 * Copyright 2025-2026 the original author or authors.
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

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class AgentCoreObservabilityPropertiesTest {

	@Test
	void resolveCaptureContent_usesFieldWhenEnvironmentNull() {
		AgentCoreObservabilityProperties p = new AgentCoreObservabilityProperties();
		p.setCaptureContent(true);
		assertThat(p.resolveCaptureContent(null)).isTrue();
	}

	@Test
	void resolveCaptureContent_prefersSpringProperty() {
		AgentCoreObservabilityProperties p = new AgentCoreObservabilityProperties();
		p.setCaptureContent(false);
		MockEnvironment env = new MockEnvironment();
		env.setProperty("spring.ai.agentcore.observability.capture-content", "true");
		assertThat(p.resolveCaptureContent(env)).isTrue();
	}

	@Test
	void resolveCaptureContent_honorsLegacyOtelWhenSpringUnset() {
		AgentCoreObservabilityProperties p = new AgentCoreObservabilityProperties();
		p.setCaptureContent(false);
		MockEnvironment env = new MockEnvironment();
		env.setProperty("OTEL_GENAI_CAPTURE_CONTENT", "true");
		assertThat(p.resolveCaptureContent(env)).isTrue();
	}

	@Test
	void maskingCustomRegexNullUsesEmptyList() {
		AgentCoreObservabilityProperties p = new AgentCoreObservabilityProperties();
		p.getMasking().setCustomRegex(null);
		assertThat(p.getMasking().getCustomRegex()).isEmpty();
	}

}
