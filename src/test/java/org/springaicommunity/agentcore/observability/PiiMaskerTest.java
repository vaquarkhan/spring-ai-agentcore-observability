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

import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;

class PiiMaskerTest {

  private final PiiMasker masker = new PiiMasker();

  @Test
  void masksEmailSsnCardAndPhone() {
    String in =
        "Contact jane@example.com ssn 123-45-6789 card 4111111111111111 tel 212-555-0199";
    String out = masker.mask(in);
    assertThat(out).doesNotContain("jane@example.com");
    assertThat(out).contains("j***@***.com");
    assertThat(out).contains("###-##-####");
    assertThat(out).contains("4111-****-****-1111");
    assertThat(out).contains("###-###-####");
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
}
