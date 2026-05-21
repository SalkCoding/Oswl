package com.salkcoding.oswl.web.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Spring @Async 지원을 활성화하고 가상 스레드 실행기를 제공한다.
 * 애플리케이션의 모든 @Async 메서드는 이 실행기를 사용한다.
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean(name = "virtualThreadExecutor")
    public Executor virtualThreadExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}
