package com.agentflow.web.run;

import org.springframework.http.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice
public class RunApiExceptionHandler {
    @ExceptionHandler({IllegalArgumentException.class, MethodArgumentNotValidException.class,
            org.springframework.http.converter.HttpMessageNotReadableException.class,
            org.springframework.web.bind.MissingServletRequestParameterException.class})
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
    @ExceptionHandler(Exception.class)
    ResponseEntity<RunApiErrorWriter.ErrorResponse> internal() { return response(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", null); }
    private static ResponseEntity<RunApiErrorWriter.ErrorResponse> response(HttpStatus status, String code, String taskId) {
        HttpHeaders headers = new HttpHeaders(); headers.setCacheControl("no-store");
        if (taskId != null) headers.setLocation(java.net.URI.create("/api/agent/tasks/" + taskId));
        return new ResponseEntity<>(new RunApiErrorWriter.ErrorResponse(code, RunApiErrorWriter.message(code), taskId), headers, status);
    }
}
