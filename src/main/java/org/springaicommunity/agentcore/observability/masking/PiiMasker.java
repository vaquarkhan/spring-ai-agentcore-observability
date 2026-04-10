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

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Applies targeted redaction to telemetry strings before export. Patterns are compiled once at
 * construction time to avoid repeated compilation on the export hot path.
 *
 * @author Vaquar Khan
 */
public class PiiMasker {

  private static final Pattern SSN =
      Pattern.compile("\\b\\d{3}-\\d{2}-\\d{4}\\b");
  private static final Pattern CREDIT_CARD =
      Pattern.compile("\\b(?:\\d[ -]*?){13,19}\\b");
  private static final Pattern EMAIL =
      Pattern.compile("[\\w.+-]+@([\\w-]+\\.)+[\\w-]{2,}");
  private static final Pattern PHONE =
      Pattern.compile("\\b[2-9]\\d{2}-\\d{3}-\\d{4}\\b");

  /**
   * Masks common PII patterns. Null-safe; returns null for null input.
   */
  public String mask(String value) {
    if (value == null || value.isEmpty()) {
      return value;
    }
    String s = SSN.matcher(value).replaceAll("###-##-####");
    s = maskCreditCards(s);
    s = maskEmails(s);
    s = PHONE.matcher(s).replaceAll("###-###-####");
    return s;
  }

  private static String maskCreditCards(String s) {
    Matcher m = CREDIT_CARD.matcher(s);
    StringBuilder out = new StringBuilder();
    while (m.find()) {
      String g = m.group();
      String digits = g.replaceAll("[^0-9]", "");
      if (digits.length() < 13 || digits.length() > 19) {
        m.appendReplacement(out, Matcher.quoteReplacement(g));
        continue;
      }
      String masked =
          digits.substring(0, 4) + "-****-****-" + digits.substring(digits.length() - 4);
      m.appendReplacement(out, Matcher.quoteReplacement(masked));
    }
    m.appendTail(out);
    return out.toString();
  }

  private static String maskEmails(String s) {
    Matcher m = EMAIL.matcher(s);
    StringBuilder out = new StringBuilder();
    while (m.find()) {
      String full = m.group();
      int at = full.indexOf('@');
      String local = full.substring(0, at);
      String domain = full.substring(at + 1);
      String maskedLocal =
          local.length() <= 1 ? "*" : local.charAt(0) + "***";
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
