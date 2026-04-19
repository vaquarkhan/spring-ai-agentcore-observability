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

package org.springaicommunity.agentcore.observability;

import org.springaicommunity.agentcore.observability.sample.SampleBedrockAgentService;
import org.springaicommunity.agentcore.observability.testsupport.OtelInMemorySpanExporterTestConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;

/**
 * Same wide component scan as other observability tests but excludes the synthetic
 * {@link SampleBedrockAgentService} and the shared
 * {@link OtelInMemorySpanExporterTestConfig} (the latter is {@code @Import}ed only by
 * tests to avoid duplicate beans).
 */
@SpringBootApplication(scanBasePackages = "org.springaicommunity.agentcore.observability")
@ComponentScan(excludeFilters = {
		@ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = SampleBedrockAgentService.class),
		@ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = OtelInMemorySpanExporterTestConfig.class) })
public class RealBedrockTestApplication {

	public static void main(String[] args) {
		SpringApplication.run(RealBedrockTestApplication.class, args);
	}

}
