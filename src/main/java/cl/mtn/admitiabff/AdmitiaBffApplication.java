package cl.mtn.admitiabff;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.transaction.annotation.EnableTransactionManagement;

@SpringBootApplication
@EnableTransactionManagement
public class AdmitiaBffApplication {
    public static void main(String[] args) {
        SpringApplication.run(AdmitiaBffApplication.class, args);
    }
}
