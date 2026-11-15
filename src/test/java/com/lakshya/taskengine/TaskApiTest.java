package com.lakshya.taskengine;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.client.RestTestClient;

import com.lakshya.taskengine.common.TaskRepository;
import com.lakshya.taskengine.common.TaskStatus;

@Import(TestcontainersConfiguration.class)
@AutoConfigureRestTestClient
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "taskengine.scheduling.enabled=false")
class TaskApiTest {

    @LocalServerPort
    int port;

    @Autowired
    RestTestClient client;

    @Autowired
    TaskRepository repository;

    @Autowired
    JdbcClient jdbc;

    @BeforeEach
    void cleanTable() {
        jdbc.sql("DELETE FROM tasks").update();
    }

    private String url(String path) {
        return "http://localhost:%d%s".formatted(port, path);
    }

    private String postExpectingBadRequest(String json) {
        return client.post()
                .uri(url("/tasks"))
                .contentType(MediaType.APPLICATION_JSON)
                .body(json)
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();
    }

    private String post(String key, String json, int expectedStatus) {
        return client.post()
                .uri(url("/tasks"))
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .body(json)
                .exchange()
                .expectStatus().isEqualTo(expectedStatus)
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();
    }

    @Test
    void createTaskReturns201AndSavesAPendingTask() {
        String body = client.post()
                .uri(url("/tasks"))
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"type\":\"send-email\",\"maxAttempts\":5}")
                .exchange()
                .expectStatus().isCreated()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        assertThat(body).contains("\"id\"");
        assertThat(repository.countByStatus()).containsEntry(TaskStatus.PENDING, 1L);
    }

    @Test
    void payloadObjectIsSavedWithTheTask() {
        client.post()
                .uri(url("/tasks"))
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"type\":\"send-email\",\"payload\":{\"to\":\"a@example.com\"}}")
                .exchange()
                .expectStatus().isCreated();

        String saved = jdbc.sql("SELECT payload ->> 'to' FROM tasks").query(String.class).single();

        assertThat(saved).isEqualTo("a@example.com");
    }

    @Test
    void emptyTypeIsRejectedAndNamesTheField() {
        String body = postExpectingBadRequest("{\"type\":\"\"}");

        assertThat(body).contains("\"type\"").contains("must not be blank");
        assertThat(repository.countByStatus()).isEmpty();
    }

    @Test
    void maxAttemptsBelowOneIsRejected() {
        String body = postExpectingBadRequest("{\"type\":\"send-email\",\"maxAttempts\":0}");

        assertThat(body).contains("maxAttempts");
        assertThat(repository.countByStatus()).isEmpty();
    }

    @Test
    void payloadThatIsNotAnObjectIsRejected() {
        String body = postExpectingBadRequest("{\"type\":\"send-email\",\"payload\":[1,2,3]}");

        assertThat(body).contains("payload must be a JSON object");
        assertThat(repository.countByStatus()).isEmpty();
    }

    @Test
    void getReturnsThePayloadAsRealJson() {
        long id = repository.insert("send-email", "{\"to\":\"a@example.com\"}", 3);

        String body = client.get()
                .uri(url("/tasks/" + id))
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        assertThat(body).contains("\"status\":\"PENDING\"");
        assertThat(body).contains("a@example.com");
        assertThat(body).doesNotContain("\"payload\":\"");
    }

    @Test
    void unknownTaskReturns404WithAMessage() {
        String body = client.get()
                .uri(url("/tasks/999999"))
                .exchange()
                .expectStatus().isNotFound()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        assertThat(body).contains("Task not found");
    }

    @Test
    void repeatedKeyReturnsTheSameTaskWith200() {
        String first = post("key-1", "{\"type\":\"send-email\"}", 201);
        String second = post("key-1", "{\"type\":\"send-email\"}", 200);

        assertThat(second).isEqualTo(first);
        assertThat(repository.countByStatus()).containsEntry(TaskStatus.PENDING, 1L);
    }

    @Test
    void reusedKeyWithADifferentRequestIsRejectedWith422() {
        post("key-1", "{\"type\":\"send-email\"}", 201);

        String body = post("key-1", "{\"type\":\"send-sms\"}", 422);

        assertThat(body).contains("different request");
        assertThat(repository.countByStatus()).containsEntry(TaskStatus.PENDING, 1L);
    }

    @Test
    void aTooLongKeyIsRejected() {
        String body = post("k".repeat(201), "{\"type\":\"send-email\"}", 400);

        assertThat(body).contains("at most 200 characters");
        assertThat(repository.countByStatus()).isEmpty();
    }
}