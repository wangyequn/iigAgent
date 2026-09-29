package com.sinosoft.amp.openapi.sandbox;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DockerSandboxServiceTest {

    private DockerSandboxService service(SandboxProperties p) {
        return new DockerSandboxService(p, new com.sinosoft.amp.openapi.ops.PlatformMetrics(
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry(),
                new com.sinosoft.amp.openapi.ops.RuntimeConfigService(
                        new com.sinosoft.amp.openapi.ops.OpsProperties(true, 100, 3, 2))));
    }

    @Test
    void buildRunArgsEnforcesSecurityBoundary() {
        SandboxProperties p = new SandboxProperties(true, "alpine:3.19", "docker", "USER", 15, 256, 0.5, true);
        List<String> args = service(p).buildRunArgs("amp-sbx-1", "echo hi");
        assertTrue(args.contains("--network=none"));
        assertTrue(args.contains("--read-only"));
        assertTrue(args.contains("--cap-drop"));
        assertTrue(args.contains("ALL"));
        assertTrue(args.contains("no-new-privileges"));
        assertTrue(args.contains("65534:65534"));
        assertTrue(args.contains("alpine:3.19"));
        assertTrue(args.get(args.size() - 1).equals("echo hi")
                || args.contains("echo hi"));
    }

    @Test
    void globalScopeDowngradedToUser() {
        SandboxProperties p = new SandboxProperties(true, "alpine", "docker", "GLOBAL", 10, 128, 0.2, true);
        assertTrue("USER".equals(service(p).normalizeScope("GLOBAL")));
    }

    @Test
    void disabledThrows() {
        SandboxProperties p = new SandboxProperties(false, "alpine", "docker", "USER", 10, 128, 0.2, true);
        DockerSandboxService s = service(p);
        assertThrows(com.sinosoft.amp.openapi.catalog.CatalogException.class,
                () -> s.exec("acme", "demo", "u", "s", "echo"));
    }

    @Test
    void networkNoneFlagCanDisable() {
        SandboxProperties p = new SandboxProperties(true, "alpine", "docker", "SESSION", 10, 128, 0.2, false);
        List<String> args = service(p).buildRunArgs("n", "true");
        assertFalse(args.contains("--network=none"));
    }
}
