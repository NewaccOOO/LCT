package ru.lct.heatnet;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import ru.lct.heatnet.rules.Rules;

@SpringBootApplication
public class HeatNetApplication {
    public static void main(String[] args) {
        // --rules=<путь> задаёт файл правил до загрузки бинов: Rules.load читает свойство при инициализации
        for (String arg : args) {
            if (arg.startsWith("--rules=")) {
                System.setProperty(Rules.RULES_PROPERTY, arg.substring("--rules=".length()));
            }
        }
        SpringApplication app = new SpringApplication(HeatNetApplication.class);
        if (args.length > 0 && "--cli".equals(args[0])) {
            app.setAdditionalProfiles("cli");
        }
        app.run(args);
    }
}
