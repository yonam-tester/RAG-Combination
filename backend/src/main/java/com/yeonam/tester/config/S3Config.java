package com.yeonam.tester.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import jakarta.annotation.PostConstruct;
import java.net.URI;

@Configuration
public class S3Config {

    @Value("${aws.s3.endpoint}")
    private String endpoint;

    @Value("${aws.s3.access-key}")
    private String accessKey;

    @Value("${aws.s3.secret-key}")
    private String secretKey;

    @Value("${aws.s3.region}")
    private String region;

    @Value("${aws.s3.buckets.documents}")
    private String documentsBucket;

    @Value("${aws.s3.buckets.reports}")
    private String reportsBucket;

    @Bean
    public S3Client s3Client() {
        S3Client client = S3Client.builder()
                .endpointOverride(URI.create(endpoint))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKey, secretKey)
                ))
                .region(Region.of(region))
                .forcePathStyle(true) // Required for MinIO/LocalS3
                .build();

        // Initialize buckets on startup using the created client
        initBuckets(client);

        return client;
    }

    private void initBuckets(S3Client client) {
        try {
            createBucketIfNotExists(client, documentsBucket);
            createBucketIfNotExists(client, reportsBucket);
        } catch (Exception e) {
            System.err.println("Failed to initialize S3 buckets: " + e.getMessage());
        }
    }

    private void createBucketIfNotExists(S3Client client, String bucketName) {
        try {
            client.headBucket(HeadBucketRequest.builder().bucket(bucketName).build());
            System.out.println("S3 Bucket already exists: " + bucketName);
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                try {
                    client.createBucket(CreateBucketRequest.builder().bucket(bucketName).build());
                    System.out.println("Successfully created S3 Bucket: " + bucketName);
                } catch (Exception ex) {
                    System.err.println("Failed to create bucket: " + bucketName + ". Error: " + ex.getMessage());
                }
            } else {
                System.err.println("Error checking bucket: " + bucketName + ". Error: " + e.getMessage());
            }
        } catch (Exception e) {
            System.err.println("Generic error checking bucket: " + bucketName + ". Error: " + e.getMessage());
        }
    }
}
