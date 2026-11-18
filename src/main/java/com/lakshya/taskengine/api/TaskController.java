package com.lakshya.taskengine.api;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.lakshya.taskengine.common.Task;
import com.lakshya.taskengine.common.TaskRepository;
import com.lakshya.taskengine.common.TaskRepository.InsertResult;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/tasks")
public class TaskController {

    private static final int MAX_KEY_LENGTH = 200;

    private final TaskRepository repository;

    public TaskController(TaskRepository repository) {
        this.repository = repository;
    }

    @PostMapping
    public ResponseEntity<Map<String, Long>> create(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CreateTaskRequest request) {
        int maxAttempts = request.maxAttempts() != null ? request.maxAttempts() : 3;
        int delaySeconds = request.delaySeconds() != null ? request.delaySeconds() : 0;

        String payloadJson = "{}";
        if (request.payload() != null && !request.payload().isNull()) {
            if (!request.payload().isObject()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "payload must be a JSON object");
            }
            payloadJson = request.payload().toString();
        }

        if (idempotencyKey == null) {
            long id = repository.insert(request.type(), payloadJson, maxAttempts, delaySeconds, null).id();
            return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id", id));
        }

        if (idempotencyKey.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Idempotency-Key must not be blank");
        }
        if (idempotencyKey.length() > MAX_KEY_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Idempotency-Key must be at most " + MAX_KEY_LENGTH + " characters");
        }

        InsertResult result = repository.insert(request.type(), payloadJson, maxAttempts, delaySeconds,
                idempotencyKey);
        if (result.created()) {
            return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id", result.id()));
        }
        if (!repository.matchesRequest(result.id(), request.type(), payloadJson, maxAttempts)) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(422),
                    "Idempotency-Key was already used for a different request");
        }
        return ResponseEntity.ok(Map.of("id", result.id()));
    }

    @GetMapping("/{id}")
    public Task get(@PathVariable long id) {
        return repository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Task not found"));
    }
}