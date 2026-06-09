package com.chatapp.config;

import com.chatapp.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class BuiltinAdminRoleInitializer implements ApplicationRunner {

    private final UserService userService;

    @Override
    public void run(ApplicationArguments args) {
        if (userService.ensureBuiltinAdminRole()) {
            log.info("builtin admin role verified");
        } else {
            log.info("builtin admin account not present; skipping admin role repair");
        }
    }
}
