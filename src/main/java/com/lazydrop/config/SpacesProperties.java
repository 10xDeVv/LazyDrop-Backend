package com.lazydrop.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "spaces")
public class SpacesProperties {

    private String endpoint;

    private String region;

    private String bucketName;

    private String accessKey;

    private String secretKey;

    private String cdnEndpoint;
}