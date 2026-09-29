package com.sinosoft.amp.openapi.ops;

import com.sinosoft.amp.openapi.security.AuthContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * S7/T2：查看 Nacos 配置源状态（local / nacos:...）。
 */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminNacosController {

    private final NacosConfigBridge bridge;

    public AdminNacosController(NacosConfigBridge bridge) {
        this.bridge = bridge;
    }

    @GetMapping("/nacos")
    public Map<String, Object> status() {
        AuthContext.requireTenantId();
        return bridge.status();
    }
}
