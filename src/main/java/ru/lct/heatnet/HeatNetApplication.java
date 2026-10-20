package ru.lct.heatnet;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import ru.lct.heatnet.rules.Rules;

@SpringBootApplication
public class HeatNetApplication {
    public static void main(String[] args) {
        for (String arg : args) {
            if (arg.startsWith("--rules=")) {
                System.setProperty(Rules.RULES_PROPERTY, arg.substring("--rules=".length()));
            }
        }
        boolean cli = args.length > 0 && "--cli".equals(args[0]);
        if (cli && System.getProperty("jdk.net.hosts.file") == null) {
            System.setProperty("jdk.net.hosts.file", "heatnet-cli-no-hosts");
        }
        SpringApplication app = new SpringApplication(HeatNetApplication.class);
        if (cli) {
            app.setAdditionalProfiles("cli");
        }
        app.run(args);
    }
}
