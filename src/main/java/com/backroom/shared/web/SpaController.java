package com.backroom.shared.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
class SpaController {
    @GetMapping({
        "/terms",
        "/privacy",
        "/security",
        "/contact",
        "/signin",
        "/register",
        "/invite",
        "/reset",
        "/app",
        "/app/{*path}",
        "/s/{token}"
    })
    String application() {
        return "forward:/index.html";
    }
}
