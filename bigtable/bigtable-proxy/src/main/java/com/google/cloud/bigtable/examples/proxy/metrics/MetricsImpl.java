/*
 * Copyright 2024 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.cloud.bigtable.examples.proxy.metrics;

import com.google.auth.Credentials;
import com.google.cloud.opentelemetry.metric.GoogleCloudMetricExporter;
import com.google.cloud.opentelemetry.metric.MetricConfiguration;
import io.grpc.Status;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.LongHistogram;
import io.opentelemetry.api.metrics.LongUpDownCounter;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.contrib.gcp.resource.GCPResourceProvider;
import io.opentelemetry.sdk.common.InstrumentationScopeInfo;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.export.MetricExporter;
import io.opentelemetry.sdk.metrics.export.PeriodicMetricReader;
import io.opentelemetry.sdk.resources.Resource;
import java.io.Closeable;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

public class MetricsImpl implements Closeable, Metrics {
  public static final InstrumentationScopeInfo INSTRUMENTATION_SCOPE_INFO =
      InstrumentationScopeInfo.create("bigtable-proxy");

  public static final String METRIC_PREFIX = "bigtableproxy.";

  static final AttributeKey<String> API_CLIENT_KEY = AttributeKey.stringKey("api_client");
  static final AttributeKey<String> RESOURCE_KEY = AttributeKey.stringKey("resource");
  static final AttributeKey<String> APP_PROFILE_KEY = AttributeKey.stringKey("app_profile");
  static final AttributeKey<String> METHOD_KEY = AttributeKey.stringKey("method");
  static final AttributeKey<String> STATUS_KEY = AttributeKey.stringKey("status");

  public static final String METRIC_PRESENCE_NAME = METRIC_PREFIX + "presence";
  public static final String METRIC_PRESENCE_DESC = "Number of proxy processes";
  public static final String METRIC_PRESENCE_UNIT = "{process}";

  private final SdkMeterProvider meterProvider;

  private final DoubleHistogram gfeLatency;
  private final LongCounter gfeResponseHeadersMissing;
  private final DoubleHistogram clientCredLatencies;
  private final DoubleHistogram clientQueueLatencies;
  private final DoubleHistogram clientCallLatencies;
  private final LongCounter serverCallsStarted;
  private final LongHistogram requestSizes;
  private final LongHistogram responseSizes;

  private final LongUpDownCounter channelCounter;
  private final AtomicInteger numOutstandingRpcs = new AtomicInteger();
  private final AtomicInteger maxSeen = new AtomicInteger();

  static Supplier<Resource> gcpResourceSupplier =
      () -> Resource.create(new GCPResourceProvider().getAttributes());

  public MetricsImpl(Credentials credentials, String projectId) throws IOException {
    meterProvider = createMeterProvider(credentials, projectId);
    Meter meter =
        meterProvider
            .meterBuilder(INSTRUMENTATION_SCOPE_INFO.getName())
            .setInstrumentationVersion(INSTRUMENTATION_SCOPE_INFO.getVersion())
            .build();

    serverCallsStarted =
        meter
            .counterBuilder(METRIC_PREFIX + "server.call.started")
            .setDescription(
                "The total number of RPCs started, including those that have not completed.")
            .setUnit("{call}")
            .build();

    clientCredLatencies =
        meter
            .histogramBuilder(METRIC_PREFIX + "client.call.credential.duration")
            .setDescription("Latency of getting credentials")
            .setUnit("ms")
            .build();

    clientQueueLatencies =
        meter
            .histogramBuilder(METRIC_PREFIX + "client.call.queue.duration")
            .setDescription(
                "Duration of how long the outbound side of the proxy had the RPC queued")
            .setUnit("ms")
            .build();

    requestSizes =
        meter
            .histogramBuilder(METRIC_PREFIX + "client.call.sent_total_message_size")
            .setDescription(
                "Total bytes sent per call to Bigtable service (excluding metadata, grpc and"
                    + " transport framing bytes)")
            .setUnit("by")
            .ofLongs()
            .build();

    responseSizes =
        meter
            .histogramBuilder(METRIC_PREFIX + "client.call.rcvd_total_message_size")
            .setDescription(
                "Total bytes received per call from Bigtable service (excluding metadata, grpc and"
                    + " transport framing bytes)")
            .setUnit("by")
            .ofLongs()
            .build();

    gfeLatency =
        meter
            .histogramBuilder(METRIC_PREFIX + "client.gfe.duration")
            .setDescription(
                "Latency as measured by Google load balancer from the time it "
                    + "received the first byte of the request until it received the first byte of"
                    + " the response from the Cloud Bigtable service.")
            .setUnit("ms")
            .build();

    gfeResponseHeadersMissing =
        meter
            .counterBuilder(METRIC_PREFIX + "client.gfe.duration_missing.count")
            .setDescription("Count of calls missing gfe response headers")
            .setUnit("{call}")
            .build();

    clientCallLatencies =
        meter
            .histogramBuilder(METRIC_PREFIX + "client.call.duration")
            .setDescription("Total duration of how long the outbound call took")
            .setUnit("ms")
            .build();

    channelCounter =
        meter
            .upDownCounterBuilder(METRIC_PREFIX + "client.channel.count")
            .setDescription("Number of open channels")
            .setUnit("{channel}")
            .build();

    meter
        .gaugeBuilder(METRIC_PREFIX + "client.call.max_outstanding_count")
        .setDescription("Maximum number of concurrent RPCs in a single minute window")
        .setUnit("{call}")
        .ofLongs()
        .buildWithCallback(o -> o.record(maxSeen.getAndSet(0)));

    meter
        .gaugeBuilder(METRIC_PRESENCE_NAME)
        .setDescription(METRIC_PRESENCE_DESC)
        .setUnit(METRIC_PRESENCE_UNIT)
        .ofLongs()
        .buildWithCallback(o -> o.record(1));
  }

  @Override
  public void close() {
    meterProvider.close();
  }

  private static SdkMeterProvider createMeterProvider(Credentials credentials, String projectId) {
    MetricConfiguration config =
        MetricConfiguration.builder()
            .setProjectId(projectId)
            .setCredentials(credentials)
            .setInstrumentationLibraryLabelsEnabled(false)
            .build();

    MetricExporter exporter = GoogleCloudMetricExporter.createWithConfiguration(config);

    return SdkMeterProvider.builder()
        .setResource(gcpResourceSupplier.get())
        .registerMetricReader(
            PeriodicMetricReader.builder(exporter).setInterval(Duration.ofMinutes(1)).build())
        .build();
  }

  @Override
  public void recordCallStarted(CallLabels labels) {
    serverCallsStarted.add(1, labels.getOtelAttributes());

    int outstanding = numOutstandingRpcs.incrementAndGet();
    maxSeen.updateAndGet(n -> Math.max(outstanding, n));
  }

  @Override
  public void recordCredLatency(CallLabels labels, Status status, Duration duration) {
    Attributes attributes =
        labels.getOtelAttributes().toBuilder().put(STATUS_KEY, status.getCode().name()).build();
    clientCredLatencies.record(duration.toMillis(), attributes);
  }

  @Override
  public void recordQueueLatency(CallLabels labels, Duration duration) {
    clientQueueLatencies.record(duration.toMillis(), labels.getOtelAttributes());
  }

  @Override
  public void recordRequestSize(CallLabels labels, long size) {
    requestSizes.record(size, labels.getOtelAttributes());
  }

  @Override
  public void recordResponseSize(CallLabels labels, long size) {
    responseSizes.record(size, labels.getOtelAttributes());
  }

  @Override
  public void recordGfeLatency(CallLabels labels, Duration duration) {
    gfeLatency.record(duration.toMillis(), labels.getOtelAttributes());
  }

  @Override
  public void recordGfeHeaderMissing(CallLabels labels) {
    gfeResponseHeadersMissing.add(1, labels.getOtelAttributes());
  }

  @Override
  public void recordCallLatency(CallLabels labels, Status status, Duration duration) {
    Attributes attributes =
        labels.getOtelAttributes().toBuilder().put(STATUS_KEY, status.getCode().name()).build();

    clientCallLatencies.record(duration.toMillis(), attributes);
    numOutstandingRpcs.decrementAndGet();
  }

  @Override
  public void updateChannelCount(int delta) {
    channelCounter.add(delta);
  }
}
