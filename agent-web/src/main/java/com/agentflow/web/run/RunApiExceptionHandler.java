package com.agentflow.web.run;

import org.springframework.http.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice
public class RunApiExceptionHandler {
    @ExceptionHandler(com.agentflow.web.approval.ApprovalService.ConflictException.class)
    ResponseEntity<RunApiErrorWriter.ErrorResponse> approvalConflict(com.agentflow.web.approval.ApprovalService.ConflictException e) {
        return response(HttpStatus.CONFLICT, e.code(), null);
    }
    @ExceptionHandler(com.agentflow.web.artifact.ArtifactController.GoneException.class)
    ResponseEntity<RunApiErrorWriter.ErrorResponse> artifactGone(com.agentflow.web.artifact.ArtifactController.GoneException failure) {
        return response(HttpStatus.GONE, failure.code(), null);
    }
    @ExceptionHandler(CitationSnapshotCodec.UnavailableException.class)
    ResponseEntity<RunApiErrorWriter.ErrorResponse> citationUnavailable() {
        return response(HttpStatus.SERVICE_UNAVAILABLE, "CITATION_DATA_UNAVAILABLE", null);
    }
    @ExceptionHandler({IllegalArgumentException.class, MethodArgumentNotValidException.class,
            org.springframework.http.converter.HttpMessageNotReadableException.class,
            org.springframework.web.bind.MissingServletRequestParameterException.class,
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class})
    ResponseEntity<RunApiErrorWriter.ErrorResponse> invalidRequest(Exception ignored) {
        return response(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", null);
    }
    @ExceptionHandler(RunCoordinator.RunNotFoundException.class)
    ResponseEntity<RunApiErrorWriter.ErrorResponse> notFound() { return response(HttpStatus.NOT_FOUND, "NOT_FOUND", null); }
    @ExceptionHandler(com.agentflow.web.memory.ConfirmedMemoryService.VersionConflictException.class)
    ResponseEntity<RunApiErrorWriter.ErrorResponse> memoryConflict() { return response(HttpStatus.CONFLICT, "MEMORY_VERSION_CONFLICT", null); }
    @ExceptionHandler(RunCoordinator.SessionBusyException.class)
    ResponseEntity<RunApiErrorWriter.ErrorResponse> sessionBusy() { return response(HttpStatus.CONFLICT, "SESSION_BUSY", null); }
    @ExceptionHandler(RunCoordinator.RunCapacityException.class)
    ResponseEntity<RunApiErrorWriter.ErrorResponse> capacity() { return response(HttpStatus.TOO_MANY_REQUESTS, "RUN_CAPACITY_EXCEEDED", null); }
    @ExceptionHandler(RunCoordinator.RunUnavailableException.class)
    ResponseEntity<RunApiErrorWriter.ErrorResponse> unavailable(RunCoordinator.RunUnavailableException e) {
        return response(HttpStatus.SERVICE_UNAVAILABLE, e.code(), e.taskId());
    }
    @ExceptionHandler({org.springframework.dao.DataAccessException.class, jakarta.persistence.PersistenceException.class})
    ResponseEntity<RunApiErrorWriter.ErrorResponse> dependencyFailure() {
        return response(HttpStatus.SERVICE_UNAVAILABLE, "PERSISTENCE_UNAVAILABLE", null);
    }
    @ExceptionHandler(org.springframework.web.server.ResponseStatusException.class)
    ResponseEntity<RunApiErrorWriter.ErrorResponse> statusFailure(org.springframework.web.server.ResponseStatusException failure) {
        HttpStatus status = HttpStatus.valueOf(failure.getStatusCode().value());
        return response(status, status == HttpStatus.UNAUTHORIZED ? "UNAUTHORIZED" : "INVALID_REQUEST", null);
    }
    @ExceptionHandler(Exception.class)
    ResponseEntity<RunApiErrorWriter.ErrorResponse> internal() { return response(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", null); }
    private static ResponseEntity<RunApiErrorWriter.ErrorResponse> response(HttpStatus status, String code, String taskId) {
        HttpHeaders headers = new HttpHeaders(); headers.setCacheControl("no-store");
        if (taskId != null) headers.setLocation(java.net.URI.create("/api/agent/tasks/" + taskId));
        return new ResponseEntity<>(new RunApiErrorWriter.ErrorResponse(code, RunApiErrorWriter.message(code), taskId), headers, status);
    }
}
