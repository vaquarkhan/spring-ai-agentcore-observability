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

package org.springaicommunity.agentcore.observability.masking;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springaicommunity.agentcore.observability.autoconfigure.AgentCoreObservabilityProperties;

class PiiMaskingSettingsTest {

	@Test
	void fromNullMaskingUsesDefaults() {
		assertThat(PiiMaskingSettings.from(null).enabled()).isTrue();
	}

	@Test
	void fromSkipsBlankCustomRegex() {
		AgentCoreObservabilityProperties.Masking masking = new AgentCoreObservabilityProperties.Masking();
		masking.setCustomRegex(java.util.List.of("  ", "A\\d+"));
		assertThat(PiiMaskingSettings.from(masking).customPatterns()).hasSize(1);
	}

}
