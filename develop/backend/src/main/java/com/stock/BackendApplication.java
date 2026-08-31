package com.stock;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.util.TimeZone;

@SpringBootApplication
public class BackendApplication {

    // Explicit, not host-dependent: the whole system (DB container, JVM runtime, JDBC connection)
    // must agree on Asia/Taipei (specs/backend/stock-price-ingestion.md, "時區"). Set as the very
    // first statement, before Spring builds any bean or any LocalDate.now()/LocalDateTime.now()
    // call happens, so behavior never depends on the deploying/CI machine's own default timezone.
    // (Belt-and-suspenders with the -Duser.timezone=Asia/Taipei JVM args configured in pom.xml for
    // `mvn spring-boot:run` and `mvn test`.)
    static {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Taipei"));
    }

    public static void main(String[] args) {
        SpringApplication.run(BackendApplication.class, args);
    }

}
