package com.example.agent.config;

import com.example.agent.runtime.AgentSessions;
import com.example.agent.runtime.HarnessFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AgentConfiguration {

    @Bean(destroyMethod = "close")
    public AgentSessions agentSessions(AgentProperties props) {
        return new AgentSessions(new HarnessFactory(props));
    }
}
