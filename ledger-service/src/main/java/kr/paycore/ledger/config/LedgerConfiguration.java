package kr.paycore.ledger.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** {@code Clock}/{@code Ids}/아웃박스는 payment-core 자동설정이 제공한다 (ADR-0003, ADR-0008). */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(LedgerProperties.class)
public class LedgerConfiguration {}
