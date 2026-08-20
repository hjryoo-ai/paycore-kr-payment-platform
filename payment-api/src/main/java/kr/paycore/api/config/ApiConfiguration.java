package kr.paycore.api.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(IntakeValidationProperties.class)
public class ApiConfiguration {}
