package com.vigilia.risk;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@SpringBootApplication
@RestController
public class RiskManagementApplication {
    public static void main(String[] args) {
        SpringApplication.run(RiskManagementApplication.class, args);
    }

    @GetMapping("/health/live")
    public String live() {
        return "{\"status\":\"UP\"}";
    }
}
