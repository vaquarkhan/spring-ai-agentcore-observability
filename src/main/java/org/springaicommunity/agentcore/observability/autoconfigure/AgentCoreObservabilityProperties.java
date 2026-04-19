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

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;
import org.springframework.core.env.Environment;

/**
 * Configuration for AgentCore OpenTelemetry enrichment and PII masking.
 *
 * <p>
 * Capture of prompt/completion content is also read from the legacy environment variable
 * {@code OTEL_GENAI_CAPTURE_CONTENT} when
 * {@code spring.ai.agentcore.observability.capture-content} is unset.
 */
@ConfigurationProperties(prefix = "spring.ai.agentcore.observability")
public class AgentCoreObservabilityProperties {

	private boolean captureContent = false;

	@NestedConfigurationProperty
	private final Masking masking = new Masking();

	public boolean isCaptureContent() {
		return this.captureContent;
	}

	public void setCaptureContent(boolean captureContent) {
		this.captureContent = captureContent;
	}

	public Masking getMasking() {
		return this.masking;
	}

	/**
	 * Resolves whether to record prompt/completion span events. Explicit Spring property
	 * wins; otherwise the legacy {@code OTEL_GENAI_CAPTURE_CONTENT} variable is honored;
	 * otherwise the {@link #captureContent} field (bound from configuration) is used.
	 */
	public boolean resolveCaptureContent(Environment environment) {
		if (environment == null) {
			return this.captureContent;
		}
		if (environment.containsProperty("spring.ai.agentcore.observability.capture-content")) {
			return environment.getProperty("spring.ai.agentcore.observability.capture-content", Boolean.class, false);
		}
		if (environment.containsProperty("OTEL_GENAI_CAPTURE_CONTENT")) {
			return environment.getProperty("OTEL_GENAI_CAPTURE_CONTENT", Boolean.class, false);
		}
		return this.captureContent;
	}

	public static final class Masking {

		private boolean enabled = true;

		private boolean maskEmail = true;

		private boolean maskSsn = true;

		private boolean maskCreditCard = true;

		private boolean maskPhone = true;

		private List<String> customRegex = new ArrayList<>();

		public boolean isEnabled() {
			return this.enabled;
		}

		public void setEnabled(boolean enabled) {
			this.enabled = enabled;
		}

		public boolean isMaskEmail() {
			return this.maskEmail;
		}

		public void setMaskEmail(boolean maskEmail) {
			this.maskEmail = maskEmail;
		}

		public boolean isMaskSsn() {
			return this.maskSsn;
		}

		public void setMaskSsn(boolean maskSsn) {
			this.maskSsn = maskSsn;
		}

		public boolean isMaskCreditCard() {
			return this.maskCreditCard;
		}

		public void setMaskCreditCard(boolean maskCreditCard) {
			this.maskCreditCard = maskCreditCard;
		}

		public boolean isMaskPhone() {
			return this.maskPhone;
		}

		public void setMaskPhone(boolean maskPhone) {
			this.maskPhone = maskPhone;
		}

		public List<String> getCustomRegex() {
			return this.customRegex;
		}

		public void setCustomRegex(List<String> customRegex) {
			this.customRegex = customRegex != null ? customRegex : new ArrayList<>();
		}

	}

}
