package kr.paycore.simulator;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** 청산망/상대은행 시뮬레이터 (docs §5.4) */
@SpringBootApplication
public class ClearingSimulatorApplication {

    public static void main(String[] args) {
        SpringApplication.run(ClearingSimulatorApplication.class, args);
    }
}
