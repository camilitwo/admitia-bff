package cl.mtn.admitiabff;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.annotation.EnableTransactionManagement;

@SpringBootApplication
@EnableTransactionManagement
public class AdmitiaBffApplication {
    public static void main(String[] args) {
        SpringApplication.run(AdmitiaBffApplication.class, args);

        /*String plainPassword = "Mithrandir1970.";

        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

        String hashedPassword = encoder.encode(plainPassword);

        System.out.println("Password plano : " + plainPassword);

        System.out.println("Hash BCrypt    : " + hashedPassword);*/
    }
}
