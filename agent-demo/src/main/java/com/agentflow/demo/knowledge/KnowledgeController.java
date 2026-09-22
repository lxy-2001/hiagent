package com.agentflow.demo.knowledge;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/knowledge")
public class KnowledgeController {

    @PostMapping("/reload")
    public org.springframework.http.ResponseEntity<java.util.Map<String, String>> reload() {
        return org.springframework.http.ResponseEntity.status(410)
                .header("Cache-Control", "no-store")
                .body(java.util.Map.of("code", "KNOWLEDGE_RELOAD_RETIRED",
                        "message", "Use the offline RAG import command."));
    }
}
