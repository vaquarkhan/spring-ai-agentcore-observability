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

import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.util.ArrayList;
import java.util.Collection;

/**
 * Wraps a delegate {@link SpanExporter} and applies {@link PiiMasker} to each {@link SpanData}
 * prior to export so sensitive content never reaches the network layer.
 */
public class PiiMaskingSpanExporter implements SpanExporter {

  private final SpanExporter delegate;
  private final PiiMasker masker;

  public PiiMaskingSpanExporter(SpanExporter delegate, PiiMasker masker) {
    this.delegate = delegate;
    this.masker = masker;
  }

  @Override
  public CompletableResultCode export(Collection<SpanData> spans) {
    Collection<SpanData> masked = new ArrayList<>(spans.size());
    for (SpanData s : spans) {
      masked.add(new MaskingSpanData(s, masker));
    }
    return delegate.export(masked);
  }

  @Override
  public CompletableResultCode flush() {
    return delegate.flush();
  }

  @Override
  public CompletableResultCode shutdown() {
    return delegate.shutdown();
  }
}
