package kr.paycore.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** 청산망 게이트웨이 (docs §5.3) */
@SpringBootApplication
public class ClearingGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(ClearingGatewayApplication.class, args);
    }
}
