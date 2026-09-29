package com.sinosoft.amp.openapi.web;

import com.sinosoft.amp.openapi.iam.IamService;
import com.sinosoft.amp.openapi.iam.RbacGuard;
import com.sinosoft.amp.openapi.security.AuthContext;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/admin/iam")
public class IamAdminController {

    private final IamService iam;
    private final RbacGuard rbac;

    public IamAdminController(IamService iam, RbacGuard rbac) {
        this.iam = iam;
        this.rbac = rbac;
    }

    @GetMapping("/roles")
    public Map<String, Object> roles() {
        rbac.require("admin:read");
        return iam.describeDefaults();
    }

    @GetMapping("/users")
    public List<Map<String, Object>> users() {
        rbac.require("iam:admin");
        return iam.listUsers(AuthContext.requireTenantId());
    }

    @PostMapping("/users/{userId}/roles/{roleCode}")
    public Map<String, Object> assign(@PathVariable String userId, @PathVariable String roleCode) {
        rbac.require("iam:admin");
        String tenant = AuthContext.requireTenantId();
        iam.assignRole(tenant, userId, roleCode);
        return Map.of("userId", userId, "roles", iam.rolesOf(tenant, userId));
    }

    @DeleteMapping("/users/{userId}/roles/{roleCode}")
    public Map<String, Object> revoke(@PathVariable String userId, @PathVariable String roleCode) {
        rbac.require("iam:admin");
        String tenant = AuthContext.requireTenantId();
        iam.revokeRole(tenant, userId, roleCode);
        return Map.of("userId", userId, "roles", iam.rolesOf(tenant, userId));
    }
}
