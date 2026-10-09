package com.sinosoft.amp.openapi;

import com.sinosoft.amp.openapi.agent.AgentProperties;
import com.sinosoft.amp.openapi.agent.ToolPolicyProperties;
import com.sinosoft.amp.openapi.knowledge.DifyProperties;
import com.sinosoft.amp.openapi.knowledge.EmbeddingProperties;
import com.sinosoft.amp.openapi.memory.Mem0Properties;
import com.sinosoft.amp.openapi.skill.SkillCanaryProperties;
import com.sinosoft.amp.openapi.ops.NacosAiProperties;
import com.sinosoft.amp.openapi.ops.NacosProperties;
import com.sinosoft.amp.openapi.ops.OpsProperties;
import com.sinosoft.amp.openapi.ops.OtelProperties;
import com.sinosoft.amp.openapi.ops.LokiProperties;
import com.sinosoft.amp.openapi.sandbox.SandboxProperties;
import com.sinosoft.amp.openapi.fs.FsProperties;
import com.sinosoft.amp.openapi.security.AuthProperties;
import com.sinosoft.amp.openapi.security.IamProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableConfigurationProperties({
        AuthProperties.class, IamProperties.class, AgentProperties.class, ToolPolicyProperties.class,
        EmbeddingProperties.class, DifyProperties.class, SkillCanaryProperties.class,
        Mem0Properties.class,
        OpsProperties.class, NacosProperties.class, NacosAiProperties.class, OtelProperties.class,
        LokiProperties.class, SandboxProperties.class, FsProperties.class})
@EnableScheduling
public class OpenApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(OpenApiApplication.class, args);
    }
}
