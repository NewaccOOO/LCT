package ru.lct.heatnet;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class HeatNetApplication {
    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(HeatNetApplication.class);
        if (args.length > 0 && "--cli".equals(args[0])) {
            app.setAdditionalProfiles("cli");
        }
        app.run(args);
    }
}
