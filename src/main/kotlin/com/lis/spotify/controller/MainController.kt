package com.lis.spotify.controller

import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping

@Controller
class MainController {
  @GetMapping("/favicon.ico") fun favicon(): String = "forward:/favicon.svg"

  @GetMapping("/") fun main(): String = "forward:/index.html"
}
