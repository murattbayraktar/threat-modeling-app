package com.bank.tm.app.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** Frontend derlemesi backend'den sunulduğunda istemci tarafı rotaları index.html'e yönlendirir. */
@Controller
public class SpaForwardController {

    @GetMapping({"/reviews/**", "/new", "/knowledge", "/rules", "/metrics"})
    public String forward() {
        return "forward:/index.html";
    }
}
