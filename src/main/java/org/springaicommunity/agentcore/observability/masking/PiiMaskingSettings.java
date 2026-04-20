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

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.springaicommunity.agentcore.observability.autoconfigure.AgentCoreObservabilityProperties;

/** Immutable masking toggles and compiled custom patterns. */
public final class PiiMaskingSettings {

	private final boolean enabled;

	private final boolean maskEmail;

	private final boolean maskSsn;

	private final boolean maskCreditCard;

	private final boolean maskPhone;

	private final List<Pattern> customPatterns;

	public PiiMaskingSettings(boolean enabled, boolean maskEmail, boolean maskSsn, boolean maskCreditCard,
			boolean maskPhone, List<Pattern> customPatterns) {
		this.enabled = enabled;
		this.maskEmail = maskEmail;
		this.maskSsn = maskSsn;
		this.maskCreditCard = maskCreditCard;
		this.maskPhone = maskPhone;
		this.customPatterns = List.copyOf(customPatterns);
	}

	public static PiiMaskingSettings defaults() {
		return new PiiMaskingSettings(true, true, true, true, true, List.of());
	}

	public static PiiMaskingSettings from(AgentCoreObservabilityProperties.Masking masking) {
		if (masking == null) {
			return defaults();
		}
		List<Pattern> compiled = new ArrayList<>();
		for (String regex : masking.getCustomRegex()) {
			if (regex != null && !regex.isBlank()) {
				compiled.add(Pattern.compile(regex));
			}
		}
		return new PiiMaskingSettings(masking.isEnabled(), masking.isMaskEmail(), masking.isMaskSsn(),
				masking.isMaskCreditCard(), masking.isMaskPhone(), compiled);
	}

	public boolean enabled() {
		return this.enabled;
	}

	public boolean maskEmail() {
		return this.maskEmail;
	}

	public boolean maskSsn() {
		return this.maskSsn;
	}

	public boolean maskCreditCard() {
		return this.maskCreditCard;
	}

	public boolean maskPhone() {
		return this.maskPhone;
	}

	public List<Pattern> customPatterns() {
		return this.customPatterns;
	}

}
