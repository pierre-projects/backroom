package com.backroom.shared;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
class SpaController {
    @GetMapping({"/signin", "/register", "/invite", "/app", "/app/{*path}"})
    String application() { return "forward:/index.html"; }
}
