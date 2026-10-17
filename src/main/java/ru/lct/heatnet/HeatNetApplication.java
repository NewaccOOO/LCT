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
        boolean cli = args.length > 0 && "--cli".equals(args[0]);
        // CLI сети не использует, а Spring при старте берёт имя хоста (PID в логах через RuntimeMXBean.getName и
        // строка Starting): на Mac, где имя *.local не записано в /etc/hosts, это 5 с таймаута mDNS — почти весь
        // старт. Без файла хостов поиск сразу падает, и вместо имени берётся localhost. До первого InetAddress.
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
