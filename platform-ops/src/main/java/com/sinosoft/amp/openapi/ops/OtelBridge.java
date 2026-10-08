package com.sinosoft.amp.openapi.ops;

import io.agentscope.core.tracing.TracerRegistry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Scope;
import io.opentelemetry.exporter.logging.LoggingSpanExporter;
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * S7 OTel：初始化 SDK → OTLP Collector 或 logging；注册 AgentScope Tracer；
 * 并提供 HTTP 级 span（sessionId 等业务属性）。
 */
@Component
public class OtelBridge implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(OtelBridge.class);

    private final OtelProperties properties;
    private volatile OpenTelemetrySdk sdk;
    private volatile io.opentelemetry.api.trace.Tracer tracer;
    private volatile String mode = "disabled";

    public OtelBridge(OtelProperties properties) {
        this.properties = properties;
    }

    public synchronized void start() {
        if (!properties.enabled()) {
            mode = "disabled";
            log.info("OTel disabled (amp.otel.enabled=false)");
            return;
        }
        try {
            String endpoint = properties.endpoint();
            if (endpoint == null || endpoint.isBlank()) {
                endpoint = System.getenv("OTEL_EXPORTER_OTLP_ENDPOINT");
            }
            SpanExporter exporter;
            if (endpoint != null && !endpoint.isBlank()) {
                exporter = OtlpGrpcSpanExporter.builder()
                        .setEndpoint(endpoint)
                        .setTimeout(5, TimeUnit.SECONDS)
                        .build();
                mode = "otlp:" + endpoint;
            } else {
                exporter = LoggingSpanExporter.create();
                mode = "logging";
            }
            String svc = properties.serviceName() == null || properties.serviceName().isBlank()
                    ? "platform-open-api" : properties.serviceName();
            SdkTracerProvider provider = SdkTracerProvider.builder()
                    .addSpanProcessor(BatchSpanProcessor.builder(exporter).build())
                    .build();
            sdk = OpenTelemetrySdk.builder()
                    .setTracerProvider(provider)
                    .build();
            tracer = sdk.getTracer(svc);
            // AgentScope 内部 Agent/Model/Tool 链路
            TracerRegistry.enableTracingHook();
            log.info("OTel started mode={} service={}", mode, svc);
        } catch (Exception e) {
            mode = "failed";
            log.warn("OTel start failed: {}", e.toString());
        }
    }

    public String mode() {
        return mode;
    }

    /**
     * 执行一段业务 span（HTTP chat 等）。
     */
    public <T> T inSpan(String name, Map<String, String> attrs, java.util.function.Supplier<T> body) {
        if (tracer == null) {
            return body.get();
        }
        Span span = tracer.spanBuilder(name).setSpanKind(SpanKind.SERVER).startSpan();
        if (attrs != null) {
            attrs.forEach(span::setAttribute);
        }
        try (Scope ignored = span.makeCurrent()) {
            T result = body.get();
            span.setStatus(StatusCode.OK);
            return result;
        } catch (RuntimeException e) {
            span.setStatus(StatusCode.ERROR, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            span.recordException(e);
            throw e;
        } finally {
            span.end();
        }
    }

    public Map<String, Object> status() {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("enabled", properties.enabled());
        m.put("mode", mode);
        m.put("endpoint", properties.endpoint());
        m.put("serviceName", properties.serviceName());
        m.put("hasTracer", tracer != null);
        return m;
    }

    @Override
    public synchronized void close() {
        if (sdk != null) {
            try {
                sdk.getSdkTracerProvider().shutdown().join(3, TimeUnit.SECONDS);
            } catch (Exception e) {
                log.debug("otel shutdown {}", e.toString());
            }
        }
        TracerRegistry.resetToNoop();
    }
}
