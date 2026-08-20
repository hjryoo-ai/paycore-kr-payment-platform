package kr.paycore.recon;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** EOD 3-way 대사 배치 (docs §5.6) */
@SpringBootApplication
public class ReconBatchApplication {

    public static void main(String[] args) {
        SpringApplication.run(ReconBatchApplication.class, args);
    }
}
