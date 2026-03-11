package com.chatapp.integration;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.mockito.Mockito.mock;

/**
 * Test configuration that provides beans to break circular dependencies
 * and mock external infrastructure (Redis, etc.).
 */
@TestConfiguration
public class TestConfig {

    /**
     * Provide PasswordEncoder as a standalone bean to break the circular dependency
     * between SecurityConfig and UserService.
     * SecurityConfig defines PasswordEncoder as a @Bean, but also depends on UserService
     * (via UserDetailsService), which in turn needs PasswordEncoder via constructor injection.
     * This standalone primary bean breaks that cycle.
     */
    @Bean
    @Primary
    public PasswordEncoder testPasswordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    @Primary
    public StringRedisTemplate stringRedisTemplate() {
        return mock(StringRedisTemplate.class);
    }

    @Bean
    @Primary
    public RedisConnectionFactory redisConnectionFactory() {
        return mock(RedisConnectionFactory.class);
    }
}
