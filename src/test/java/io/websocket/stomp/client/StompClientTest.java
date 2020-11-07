package io.websocket.stomp.client;

import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;


class StompClientTest {
    private final String endpoint = "ws://localhost:8080/";

    @Test
    void stompClientSynchronousEchoMessageTest() {

        try(StompClient stompClient = new StompClient(endpoint)) {

            EchoModel echoModel = stompClient.subscribeAndSend("/echo/message", EchoModel.class, Optional.of(new EchoModel("hello world")));
            assertEquals("hello world", echoModel.message);

        } catch (InterruptedException | ExecutionException e) {
            fail("Connection failed");
        }
    }

    @Test
    void stompClientAsynchronousTopicTest() {
        CompletableFuture<Boolean> future = new CompletableFuture<>();

        try(StompClient stompClient = new StompClient(endpoint)) {

            stompClient.subscribeToTopic("/topic/events", Event.class, (result, error) -> {
                assertEquals("testing event topic", result.name);
                future.complete(true);
            });


            // start after 2 seconds
            ScheduledExecutorService scheduledExecutorService = Executors.newSingleThreadScheduledExecutor();
            scheduledExecutorService.schedule(() ->
                    stompClient.send("/events/add", Optional.of(new Event("testing event topic"))),
                    2,
                    TimeUnit.SECONDS
            );
            scheduledExecutorService.shutdown();


            // wait for 4 seconds
            future.get(4, TimeUnit.SECONDS);

        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            fail("Connection failed");
        }
    }


    /**
     * Model class used for tests.
     */
    private static class EchoModel {
        public final String message;

        public EchoModel(String message) {
            this.message = message;
        }
    }

    /**
     * Model class used for tests.
     */
    private static class Event {
        public final String name;

        public Event(String name) {
            this.name = name;
        }
    }

}
