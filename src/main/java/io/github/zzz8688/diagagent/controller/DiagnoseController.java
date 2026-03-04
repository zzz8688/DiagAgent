package io.github.zzz8688.diagagent.controller;

import io.github.zzz8688.diagagent.orchestrator.DiagnosticOrchestrator;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.constraints.NotBlank;

@RestController
@RequestMapping("/api/diagnose")
@RequiredArgsConstructor
@Slf4j
public class DiagnoseController {

    private final DiagnosticOrchestrator orchestrator;

    @PostMapping("/orchestrated")
    public ResponseEntity<DiagnosticOrchestrator.DiagnosticResult> orchestrate(@Valid @RequestBody DiagnoseRequest request) {
        log.info("Received diagnosis request with query: '{}'", request.getQuery());

        DiagnosticOrchestrator.DiagnosticResult result = orchestrator.diagnose(request.getQuery());

        return ResponseEntity.ok(result);
    }

    public static class DiagnoseRequest {
        @NotBlank(message = "The query field cannot be blank")
        private String query;

        public String getQuery() {
            return query;
        }

        public void setQuery(String query) {
            this.query = query;
        }
    }
}
