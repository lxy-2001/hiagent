package com.agentflow.demo.workbench;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class UiContextController {
    private final UiContextConfiguration.UiContext context;

    public UiContextController(UiContextConfiguration.UiContext context) {
        this.context = context;
    }

    @GetMapping("/api/demo/ui-context")
    public ResponseEntity<UiContextConfiguration.UiContext> get() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(context);
    }
}
