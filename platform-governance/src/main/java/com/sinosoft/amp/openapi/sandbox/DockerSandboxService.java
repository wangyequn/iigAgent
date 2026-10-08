package com.sinosoft.amp.openapi.sandbox;

import com.sinosoft.amp.openapi.catalog.CatalogException;
import com.sinosoft.amp.openapi.ops.PlatformMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Docker 沙箱：一次性容器执行不可信命令。
 * 默认 --network=none、只读、无提权、降权用户、超时强杀（设计 §8.2/§8.3）。
 */
@Service
public class DockerSandboxService {

    private static final Logger log = LoggerFactory.getLogger(DockerSandboxService.class);

    private final SandboxProperties properties;
    private final PlatformMetrics metrics;

    public DockerSandboxService(SandboxProperties properties, PlatformMetrics metrics) {
        this.properties = properties;
        this.metrics = metrics;
    }

    public boolean enabled() {
        return properties.enabled();
    }

    /**
     * 构建 docker run 参数（便于单测安全策略）。
     */
    public List<String> buildRunArgs(String containerName, String command) {
        String scope = normalizeScope(properties.isolationScope());
        List<String> args = new ArrayList<>();
        args.add(dockerBin());
        args.add("run");
        args.add("--rm");
        args.add("--name");
        args.add(containerName);
        if (properties.networkNone()) {
            args.add("--network=none");
        }
        args.add("--read-only");
        args.add("--tmpfs");
        args.add("/tmp:rw,noexec,nosuid,size=32m");
        args.add("--security-opt");
        args.add("no-new-privileges");
        args.add("--cap-drop");
        args.add("ALL");
        args.add("--user");
        args.add("65534:65534");
        args.add("--memory");
        args.add(Math.max(64, properties.memoryMb()) + "m");
        args.add("--cpus");
        args.add(String.valueOf(Math.max(0.1, properties.cpus())));
        args.add("--pids-limit");
        args.add("64");
        args.add("--label");
        args.add("amp.sandbox=1");
        args.add("--label");
        args.add("amp.scope=" + scope);
        args.add(image());
        args.add("sh");
        args.add("-c");
        args.add(command == null ? "true" : command);
        return args;
    }

    public ExecResult exec(String tenantId, String agentId, String userId, String sessionId, String command) {
        if (!properties.enabled()) {
            throw new CatalogException("SANDBOX_DISABLED", "sandbox disabled (amp.sandbox.enabled=false)");
        }
        if (command == null || command.isBlank()) {
            throw new CatalogException("SANDBOX_COMMAND_REQUIRED", "command is required");
        }
        String scope = normalizeScope(properties.isolationScope());
        String name = "amp-sbx-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        List<String> args = buildRunArgs(name, command);
        long t0 = System.currentTimeMillis();
        ProcessBuilder pb = new ProcessBuilder(args);
        pb.redirectErrorStream(false);
        try {
            Process proc = pb.start();
            StringBuilder out = new StringBuilder();
            StringBuilder err = new StringBuilder();
            Thread tOut = drain(proc.getInputStream(), out);
            Thread tErr = drain(proc.getErrorStream(), err);
            int timeout = Math.max(3, properties.timeoutSec());
            boolean finished = proc.waitFor(timeout, TimeUnit.SECONDS);
            if (!finished) {
                proc.destroyForcibly();
                try {
                    new ProcessBuilder(dockerBin(), "rm", "-f", name).start().waitFor(3, TimeUnit.SECONDS);
                } catch (Exception ignored) {
                }
                metrics.recordSandbox(System.currentTimeMillis() - t0, true);
                return new ExecResult(false, out.toString(), "timeout after " + timeout + "s", 124,
                        name, scope, System.currentTimeMillis() - t0);
            }
            tOut.join(1000);
            tErr.join(1000);
            int code = proc.exitValue();
            long ms = System.currentTimeMillis() - t0;
            metrics.recordSandbox(ms, code != 0);
            log.info("sandbox.exec name={} scope={} exit={} ms={} tenant={} agent={}",
                    name, scope, code, ms, tenantId, agentId);
            return new ExecResult(code == 0, out.toString(), err.toString(), code, name, scope, ms);
        } catch (CatalogException e) {
            throw e;
        } catch (Exception e) {
            metrics.recordSandbox(System.currentTimeMillis() - t0, true);
            log.warn("sandbox.exec failed: {}", e.toString());
            throw new CatalogException("SANDBOX_EXEC_FAILED", "sandbox exec failed: " + e.getMessage());
        }
    }

    public Map<String, Object> status() {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("enabled", properties.enabled());
        m.put("image", properties.image());
        m.put("dockerBin", dockerBin());
        m.put("isolationScope", normalizeScope(properties.isolationScope()));
        m.put("networkNone", properties.networkNone());
        m.put("timeoutSec", properties.timeoutSec());
        m.put("memoryMb", properties.memoryMb());
        m.put("cpus", properties.cpus());
        return m;
    }

    String normalizeScope(String scope) {
        String s = scope == null ? "USER" : scope.trim().toUpperCase(Locale.ROOT);
        if (s.equals("GLOBAL")) {
            log.warn("IsolationScope GLOBAL rejected, fallback USER");
            return "USER";
        }
        return s.equals("SESSION") ? "SESSION" : "USER";
    }

    String dockerBin() {
        if (properties.dockerBin() != null && !properties.dockerBin().isBlank()) {
            return properties.dockerBin();
        }
        String env = System.getenv("DOCKER_BIN");
        return env == null || env.isBlank() ? "docker" : env;
    }

    String image() {
        return properties.image() == null || properties.image().isBlank()
                ? "alpine:3.19" : properties.image();
    }

    private static Thread drain(java.io.InputStream in, StringBuilder sb) {
        Thread t = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (sb.length() < 8000) {
                        sb.append(line).append('\n');
                    }
                }
            } catch (Exception ignored) {
            }
        }, "sbx-drain");
        t.setDaemon(true);
        t.start();
        return t;
    }

    public record ExecResult(
            boolean success,
            String stdout,
            String stderr,
            int exitCode,
            String containerName,
            String isolationScope,
            long durationMs
    ) {
    }
}
