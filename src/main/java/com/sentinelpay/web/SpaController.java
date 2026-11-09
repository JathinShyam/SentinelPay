package com.sentinelpay.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Serves the single-page wallet for client-side routes. Static assets under
 * {@code /assets} are handled by Spring Boot's resource handler.
 */
@Controller
public class SpaController {

    @GetMapping({"/", "/login", "/register", "/wallet", "/wallet/**"})
    public String wallet() {
        return "forward:/index.html";
    }
}
