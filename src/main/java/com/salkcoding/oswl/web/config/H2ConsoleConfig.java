package com.salkcoding.oswl.web.config;

import org.h2.server.web.JakartaWebServlet;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Registers the H2 web console servlet explicitly for the local profile.
 * Spring Boot 4.x's H2ConsoleAutoConfiguration condition is unreliable with
 * profile-scoped YAML properties, so we register the servlet directly.
 */
@Configuration
@Profile("local")
public class H2ConsoleConfig {

    @Bean
    public ServletRegistrationBean<JakartaWebServlet> h2ConsoleServlet() {
        JakartaWebServlet servlet = new JakartaWebServlet();
        ServletRegistrationBean<JakartaWebServlet> reg =
                new ServletRegistrationBean<>(servlet, "/h2-console", "/h2-console/*");
        reg.addInitParameter("-webAllowOthers", "false");
        reg.setLoadOnStartup(1);
        return reg;
    }
}
