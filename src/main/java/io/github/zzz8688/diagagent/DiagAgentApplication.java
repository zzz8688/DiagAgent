package io.github.zzz8688.diagagent;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableKafka
@EnableScheduling
@MapperScan("io.github.zzz8688.diagagent.mapper")
@SpringBootApplication
public class DiagAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(DiagAgentApplication.class, args);
    }
}
