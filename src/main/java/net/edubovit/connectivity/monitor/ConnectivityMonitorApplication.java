package net.edubovit.connectivity.monitor;

import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@ConfigurationPropertiesScan
@SpringBootApplication
public class ConnectivityMonitorApplication {

    public static void main(String[] args) {
        SpringApplication.run(ConnectivityMonitorApplication.class, expandConfigArgument(args));
    }

    static String[] expandConfigArgument(String[] args) {
        List<String> expandedArgs = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if ("--config".equals(arg)) {
                if (i + 1 >= args.length || args[i + 1].isBlank()) {
                    throw new IllegalArgumentException("--config requires a configuration file path");
                }
                expandedArgs.add(toAdditionalLocationArgument(args[++i]));
            } else if (arg.startsWith("--config=")) {
                String configLocation = arg.substring("--config=".length());
                if (configLocation.isBlank()) {
                    throw new IllegalArgumentException("--config requires a configuration file path");
                }
                expandedArgs.add(toAdditionalLocationArgument(configLocation));
            } else {
                expandedArgs.add(arg);
            }
        }
        return expandedArgs.toArray(String[]::new);
    }

    private static String toAdditionalLocationArgument(String configLocation) {
        return "--spring.config.additional-location=file:" + configLocation;
    }
}
