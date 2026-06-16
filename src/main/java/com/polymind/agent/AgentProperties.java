package com.polymind.agent;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "polymind.agent")
public class AgentProperties {

    /** Maximum reason/act iterations before forcing a final answer. */
    private int maxSteps = 6;

    public int getMaxSteps() {
        return maxSteps;
    }

    public void setMaxSteps(int maxSteps) {
        this.maxSteps = maxSteps;
    }
}
