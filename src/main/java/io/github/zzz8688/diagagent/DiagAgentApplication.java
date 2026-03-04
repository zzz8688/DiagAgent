package io.github.zzz8688.diagagent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class DiagAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(DiagAgentApplication.class, args);
    }

}
