package com.polymind.routing;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "polymind.registry")
public class RegistryProperties {

    /** Location of models.yaml (Spring resource URL). */
    private String location = "classpath:models.yaml";

    public String getLocation() {
        return location;
    }

    public void setLocation(String location) {
        this.location = location;
    }
}
