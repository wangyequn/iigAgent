package com.sinosoft.amp.openapi.agent;

import com.sinosoft.amp.openapi.governance.GovernanceService;
import com.sinosoft.amp.openapi.skill.SkillCatalogRepository;
import io.agentscope.harness.agent.bus.MessageBus;
import io.agentscope.harness.agent.bus.WorkspaceMessageBus;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

/**
 * 瀹炰緥宸ュ巶瑁呴厤锛圫4 娌荤悊 + S5 鎶€鑳藉競鍦猴級銆? */
@Configuration
public class AgentRuntimeConfiguration {

    private static final Logger log = LoggerFactory.getLogger(AgentRuntimeConfiguration.class);

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(AgentInstanceCreator.class)
    public AgentInstanceFactory agentInstanceFactory(
            AgentProperties properties,
            GovernanceService governance,
            SkillCatalogRepository skillCatalog,
            com.sinosoft.amp.openapi.knowledge.KnowledgeService knowledge,
            com.sinosoft.amp.openapi.governance.McpConnectService mcpConnect,
            com.sinosoft.amp.openapi.sandbox.DockerSandboxService sandbox,
            com.sinosoft.amp.openapi.fs.PgBaseStore baseStore,
            com.sinosoft.amp.openapi.fs.FsProperties fsProperties,
            ToolPolicyProperties toolPolicy,
            com.sinosoft.amp.openapi.model.ModelCredentialService modelCredentials,
            com.sinosoft.amp.openapi.skill.SkillCanaryProperties skillCanary,
            MessageBus platformMessageBus) {
        AgentInstanceFactory factory = new AgentInstanceFactory(properties, governance, skillCatalog, knowledge,
                mcpConnect, sandbox, baseStore, fsProperties.mode(), fsProperties.isolationScope(),
                toolPolicy, modelCredentials, skillCanary);
        factory.setMessageBus(platformMessageBus);
        return factory;
    }

    /**
     * 骞冲彴鍏变韩 MessageBus锛圓gentScope 2.0锛夛細Inbox/寮傛宸ュ叿鍞ら啋鐨勬寔涔呭寲涓庤闃呴€氶亾銆?     * 鍩轰簬 workspace 鏍圭洰褰曠殑鏈湴鏂囦欢瀹炵幇鈥斺€斿悓鏈哄杩涚▼鍏变韩鍚屼竴璺緞锛涜法涓绘満澶氬壇鏈睘鏈鐩栧満鏅€?     */
    @Bean(destroyMethod = "close")
    public MessageBus platformMessageBus(AgentProperties properties) {
        Path base = Path.of(properties.workspace() == null || properties.workspace().isBlank()
                ? ".agentscope/workspace" : properties.workspace())
                .toAbsolutePath().normalize();
        MessageBus bus = new WorkspaceMessageBus(new LocalFilesystem(base), ".agentscope/bus");
        log.info("platform message bus ready base={} key={}", base, ".agentscope/bus");
        return bus;
    }
}
