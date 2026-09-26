package com.agentflow.demo.workbench;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import java.util.Map;

/** Unknown resource/API paths must never fall back to a successful SPA page. */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class WorkbenchNotFoundAdvice {
    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    ResponseEntity<Map<String, String>> notFound() {
        return ResponseEntity.status(404).cacheControl(CacheControl.noStore())
                .body(Map.of("code", "NOT_FOUND", "message", "The requested resource was not found."));
    }
}
