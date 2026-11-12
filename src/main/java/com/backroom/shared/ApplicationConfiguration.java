package com.backroom.shared;

import java.time.Clock;
import org.springframework.context.annotation.*;

@Configuration(proxyBeanMethods = false)
public class ApplicationConfiguration {
    @Bean
    Clock applicationClock() { return Clock.systemUTC(); }
}
