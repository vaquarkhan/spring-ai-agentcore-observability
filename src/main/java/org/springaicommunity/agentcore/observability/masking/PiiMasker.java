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

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Applies targeted redaction to telemetry strings before export. Patterns are compiled
 * once at construction time to avoid repeated compilation on the export hot path.
 */
public class PiiMasker {

	private static final Pattern SSN = Pattern.compile("\\b\\d{3}-\\d{2}-\\d{4}\\b");

	/**
	 * Candidate digit runs (with optional separators) that may represent a PAN; each
	 * match is validated with Luhn and length before masking.
	 */
	private static final Pattern PAN_CANDIDATE = Pattern.compile("\\b(?:\\d[\\s\\-]*){12,18}\\d\\b");

	private static final Pattern EMAIL = Pattern.compile("[\\w.+-]+@([\\w-]+\\.)+[\\w-]{2,}");

	private static final Pattern[] PHONE = {
			Pattern.compile("\\+1[\\s.-]?(?:\\([2-9]\\d{2}\\)|[2-9]\\d{2})[\\s.-]?\\d{3}[\\s.-]?\\d{4}\\b"),
			Pattern.compile("\\([2-9]\\d{2}\\)\\s*\\d{3}[\\s.-]\\d{4}\\b"),
			Pattern.compile("\\b[2-9]\\d{2}[.]\\d{3}[.]\\d{4}\\b"),
			Pattern.compile("\\b[2-9]\\d{2}-\\d{3}-\\d{4}\\b") };

	private final PiiMaskingSettings settings;

	public PiiMasker() {
		this(PiiMaskingSettings.defaults());
	}

	public PiiMasker(PiiMaskingSettings settings) {
		this.settings = settings != null ? settings : PiiMaskingSettings.defaults();
	}

	/**
	 * Masks common PII patterns. Null-safe; returns null for null input.
	 */
	public String mask(String value) {
		if (!this.settings.enabled() || value == null || value.isEmpty()) {
			return value;
		}
		String s = value;
		if (this.settings.maskSsn()) {
			s = SSN.matcher(s).replaceAll("###-##-####");
		}
		if (this.settings.maskCreditCard()) {
			s = maskCreditCards(s);
		}
		if (this.settings.maskEmail()) {
			s = maskEmails(s);
		}
		if (this.settings.maskPhone()) {
			for (Pattern p : PHONE) {
				s = p.matcher(s).replaceAll("###-###-####");
			}
		}
		for (Pattern custom : this.settings.customPatterns()) {
			s = custom.matcher(s).replaceAll("[REDACTED]");
		}
		return s;
	}

	private String maskCreditCards(String s) {
		Matcher m = PAN_CANDIDATE.matcher(s);
		StringBuilder out = new StringBuilder();
		while (m.find()) {
			String g = m.group();
			String digits = g.replaceAll("[^0-9]", "");
			if (digits.length() < 13 || digits.length() > 19 || !passesLuhn(digits)
					|| !hasPlausibleIssuerPrefix(digits)) {
				m.appendReplacement(out, Matcher.quoteReplacement(g));
				continue;
			}
			String masked = digits.substring(0, 4) + "-****-****-" + digits.substring(digits.length() - 4);
			m.appendReplacement(out, Matcher.quoteReplacement(masked));
		}
		m.appendTail(out);
		return out.toString();
	}

	private static boolean hasPlausibleIssuerPrefix(String digits) {
		if (digits.isEmpty()) {
			return false;
		}
		char c0 = digits.charAt(0);
		if (c0 == '4') {
			return true;
		}
		if (c0 == '5') {
			char c1 = digits.length() > 1 ? digits.charAt(1) : '0';
			return c1 >= '1' && c1 <= '5';
		}
		if (c0 == '3') {
			char c1 = digits.length() > 1 ? digits.charAt(1) : '0';
			return c1 == '4' || c1 == '7';
		}
		if (digits.startsWith("6011")) {
			return true;
		}
		if (digits.startsWith("65")) {
			return true;
		}
		if (digits.length() >= 4) {
			int prefix4 = Integer.parseInt(digits.substring(0, 4));
			if (prefix4 >= 6440 && prefix4 <= 6499) {
				return true;
			}
		}
		if (digits.length() >= 6) {
			int prefix6 = Integer.parseInt(digits.substring(0, 6));
			if (prefix6 >= 622126 && prefix6 <= 622925) {
				return true;
			}
		}
		return c0 == '2' && digits.length() >= 4;
	}

	static boolean passesLuhn(String digits) {
		if (digits == null || digits.length() < 2) {
			return false;
		}
		int sum = 0;
		boolean alternate = false;
		for (int i = digits.length() - 1; i >= 0; i--) {
			char ch = digits.charAt(i);
			if (ch < '0' || ch > '9') {
				return false;
			}
			int n = ch - '0';
			if (alternate) {
				n *= 2;
				if (n > 9) {
					n -= 9;
				}
			}
			sum += n;
			alternate = !alternate;
		}
		return sum % 10 == 0;
	}

	private static String maskEmails(String s) {
		Matcher m = EMAIL.matcher(s);
		StringBuilder out = new StringBuilder();
		while (m.find()) {
			String full = m.group();
			int at = full.indexOf('@');
			String local = full.substring(0, at);
			String domain = full.substring(at + 1);
			String maskedLocal = local.length() <= 1 ? "*" : local.charAt(0) + "***";
			String masked = maskedLocal + "@***." + domainSegment(domain);
			m.appendReplacement(out, Matcher.quoteReplacement(masked));
		}
		m.appendTail(out);
		return out.toString();
	}

	private static String domainSegment(String domain) {
		int dot = domain.lastIndexOf('.');
		if (dot <= 0 || dot >= domain.length() - 1) {
			return "com";
		}
		return domain.substring(dot + 1);
	}

}
