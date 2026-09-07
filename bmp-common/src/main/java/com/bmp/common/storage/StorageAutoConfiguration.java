package com.bmp.common.storage;

import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import software.amazon.awssdk.services.s3.S3Client;

/**
 * Wires object storage into any service that put the AWS SDK on its classpath. Session 44.
 *
 * <p>{@code @ConditionalOnClass(S3Client.class)} is what keeps this dormant everywhere else.
 * bmp-common declares the SDK as an optional dependency (see its pom), so twelve of the thirteen
 * services never see these beans at all — no wasted memory, no wasted startup, and no accidental
 * dependency on storage in a service that has no business touching files.
 *
 * <p>The {@code initMethod} runs {@link S3ObjectStorage#ensureBucket()} at startup so a fresh
 * {@code docker compose up} yields a working upload rather than a {@code NoSuchBucket} error on
 * the first try. It logs rather than throws — see that method for why a storage outage must not
 * prevent the salon desk from starting.
 */
@Configuration
@ConditionalOnClass(S3Client.class)
@EnableConfigurationProperties(StorageProperties.class)
public class StorageAutoConfiguration {

    @Bean(initMethod = "ensureBucket")
    @ConditionalOnMissingBean(ObjectStorage.class)
    public S3ObjectStorage objectStorage(StorageProperties props) {
        return new S3ObjectStorage(props);
    }

    /**
     * Stateless and cheap to construct, but a bean so it can be injected and, more importantly,
     * so its limits come from configuration rather than from constants recompiled into a service.
     */
    @Bean
    @ConditionalOnMissingBean
    public ImageIngest imageIngest(StorageProperties props) {
        return new ImageIngest(props);
    }
}
