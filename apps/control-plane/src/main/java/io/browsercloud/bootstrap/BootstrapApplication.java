package io.browsercloud.bootstrap;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Agent Browser Cloud Control Plane 启动类。 */
@SpringBootApplication(scanBasePackages = "io.browsercloud")
@EntityScan(basePackages = "io.browsercloud")
@EnableJpaRepositories(basePackages = "io.browsercloud")
@EnableScheduling
public class BootstrapApplication {

  public static void main(String[] args) {
    SpringApplication.run(BootstrapApplication.class, args);
  }
}
