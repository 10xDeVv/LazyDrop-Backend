package com.lazydrop.config;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.net.URI;

@Configuration
@RequiredArgsConstructor
public class S3Config {

    private final SpacesProperties spaces;

    @Bean
    public S3Client s3Client() {
        return S3Client.builder()
                .endpointOverride(URI.create(spaces.getEndpoint()))
                .region(Region.of(spaces.getRegion()))
                .credentialsProvider(credentials())
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(true)
                        .build())
                .build();
    }

    @Bean
    @Qualifier("originPresigner")
    public S3Presigner s3Presigner() {
        return S3Presigner.builder()
                .endpointOverride(URI.create(spaces.getEndpoint()))
                .region(Region.of(spaces.getRegion()))
                .credentialsProvider(credentials())
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(true)
                        .build())
                .build();
    }

    @Bean
    @Qualifier("cdnPresigner")
    public S3Presigner cdnPresigner() {
        String cdnEndpoint = spaces.getCdnEndpoint();
        if (cdnEndpoint == null || cdnEndpoint.isBlank()) {
            // No CDN configured — fall back to origin
            return s3Presigner();
        }

        return S3Presigner.builder()
                .endpointOverride(URI.create(cdnEndpoint))
                .region(Region.of(spaces.getRegion()))
                .credentialsProvider(credentials())
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(false)
                        .build())
                .build();
    }

    private StaticCredentialsProvider credentials() {
        return StaticCredentialsProvider.create(
                AwsBasicCredentials.create(spaces.getAccessKey(), spaces.getSecretKey())
        );
    }
}
