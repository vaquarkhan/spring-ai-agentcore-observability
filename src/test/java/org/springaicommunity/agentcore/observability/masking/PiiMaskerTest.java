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

import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springaicommunity.agentcore.observability.autoconfigure.AgentCoreObservabilityProperties;

class PiiMaskerTest {

	private final PiiMasker masker = new PiiMasker();

	@Test
	void masksEmailSsnCardAndPhone() {
		String in = "Contact jane@example.com ssn 123-45-6789 card 4111111111111111 tel 212-555-0199";
		String out = masker.mask(in);
		assertThat(out).doesNotContain("jane@example.com");
		assertThat(out).contains("j***@***.com");
		assertThat(out).contains("###-##-####");
		assertThat(out).contains("4111-****-****-1111");
		assertThat(out).contains("###-###-####");
	}

	@Test
	void masksParenthesizedAndDottedUsPhones() {
		String in = "Call (415) 555-2671 or 415.555.2671";
		String out = masker.mask(in);
		assertThat(out).doesNotContain("415");
		assertThat(out).contains("###-###-####");
	}

	@Test
	void masksE164StyleUsNumber() {
		String in = "Dial +1-415-555-2671 today";
		String out = masker.mask(in);
		assertThat(out).doesNotContain("415");
	}

	@Test
	void doesNotMaskDigitRunsThatFailLuhn() {
		String in = "order id 1234567890123456";
		String out = masker.mask(in);
		assertThat(out).isEqualTo(in);
	}

	@Test
	void customRegexRedacts() {
		AgentCoreObservabilityProperties.Masking masking = new AgentCoreObservabilityProperties.Masking();
		masking.setCustomRegex(List.of("SECRET-\\d+"));
		PiiMasker m = new PiiMasker(PiiMaskingSettings.from(masking));
		assertThat(m.mask("token SECRET-99 end")).isEqualTo("token [REDACTED] end");
	}

	@Test
	void maskingDisabledPassesThrough() {
		AgentCoreObservabilityProperties.Masking masking = new AgentCoreObservabilityProperties.Masking();
		masking.setEnabled(false);
		PiiMasker m = new PiiMasker(PiiMaskingSettings.from(masking));
		assertThat(m.mask("jane@example.com")).isEqualTo("jane@example.com");
	}

	@Test
	void returnsNullOrEmptyUnchanged() {
		assertThat(masker.mask(null)).isNull();
		assertThat(masker.mask("")).isEmpty();
	}

	@Test
	void masksSingleCharacterEmailLocalPart() {
		String out = masker.mask("j@example.com");
		assertThat(out).isEqualTo("*@***.com");
	}

	@Test
	void domainSegmentFallsBackForMalformedDomains() throws Exception {
		Method domainSegment = PiiMasker.class.getDeclaredMethod("domainSegment", String.class);
		domainSegment.setAccessible(true);
		assertThat(domainSegment.invoke(null, "single")).isEqualTo("com");
		assertThat(domainSegment.invoke(null, "a.")).isEqualTo("com");
	}

	@Test
	void luhnValidatesKnownTestPan() {
		assertThat(PiiMasker.passesLuhn("4111111111111111")).isTrue();
		assertThat(PiiMasker.passesLuhn("1234567890123456")).isFalse();
	}

	@Test
	void passesLuhnRejectsNonDigits() {
		assertThat(PiiMasker.passesLuhn("411111111111111x")).isFalse();
	}

	@Test
	void canDisableIndividualCategories() {
		AgentCoreObservabilityProperties.Masking m = new AgentCoreObservabilityProperties.Masking();
		m.setMaskSsn(false);
		PiiMasker pm = new PiiMasker(PiiMaskingSettings.from(m));
		assertThat(pm.mask("123-45-6789")).contains("123-45-6789");
		m.setMaskSsn(true);
		m.setMaskEmail(false);
		pm = new PiiMasker(PiiMaskingSettings.from(m));
		assertThat(pm.mask("a@b.co")).contains("a@b.co");
		m.setMaskEmail(true);
		m.setMaskCreditCard(false);
		pm = new PiiMasker(PiiMaskingSettings.from(m));
		assertThat(pm.mask("4111111111111111")).contains("4111111111111111");
		m.setMaskCreditCard(true);
		m.setMaskPhone(false);
		pm = new PiiMasker(PiiMaskingSettings.from(m));
		assertThat(pm.mask("212-555-0199")).contains("212-555-0199");
	}

	@Test
	void masksKnownIssuerTestNumbers() {
		String in = "Visa 4111111111111111 MC 5555555555554444 Amex 378282246310005 Discover 6011111111111117";
		String out = masker.mask(in);
		assertThat(out).contains("4111-****-****-1111");
		assertThat(out).contains("5555-****-****-4444");
		assertThat(out).contains("3782-****-****-0005");
		assertThat(out).contains("6011-****-****-1117");
	}

	@Test
	void hasPlausibleIssuerPrefixViaReflection() throws Exception {
		java.lang.reflect.Method hm = PiiMasker.class.getDeclaredMethod("hasPlausibleIssuerPrefix", String.class);
		hm.setAccessible(true);
		assertThat(hm.invoke(null, "")).isEqualTo(false);
		assertThat(hm.invoke(null, "6445000000000000")).isEqualTo(true);
		assertThat(hm.invoke(null, "6221260000000000")).isEqualTo(true);
		assertThat(hm.invoke(null, "2223000000000000")).isEqualTo(true);
	}

}
