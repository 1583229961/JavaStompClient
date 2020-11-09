package io.websocket.stomp.client;

import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;


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
    void stompClientAsynchronousEchoMessageTest() {
        CompletableFuture<Boolean> future = new CompletableFuture<>();

        try(StompClient stompClient = new StompClient(endpoint)) {

            // subscribe
            String topic = "/user/" + stompClient.getClientKey() + "/echo/message";
            stompClient.subscribeToTopic(topic, EchoModel.class, (result, error) -> {
                future.complete("hello world".equals(result.message));
            });

            // send message start after 2 seconds
            ScheduledExecutorService scheduledExecutorService = Executors.newSingleThreadScheduledExecutor();
            scheduledExecutorService.schedule(() ->
                            stompClient.send("/echo/message", Optional.of(new EchoModel("hello world"))),
                    2,
                    TimeUnit.SECONDS
            );
            scheduledExecutorService.shutdown();

            // wait for 4 seconds
            assertTrue(future.get(4, TimeUnit.SECONDS));

        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            fail("Connection failed");
        }
    }

    @Test
    void stompClientAsynchronousTopicTest() {
        CompletableFuture<Boolean> future = new CompletableFuture<>();

        try(StompClient stompClient = new StompClient(endpoint)) {

            stompClient.subscribeToTopic("/topic/events", Event.class, (result, error) -> {
                future.complete("testing event topic".equals(result.name));
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
            assertTrue(future.get(4, TimeUnit.SECONDS));

        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            fail("Connection failed");
        }
    }


    @Test
    void concurrentlySendEchoMessages() {
        int numOfThreads = 4;
        ExecutorService pool = Executors.newCachedThreadPool();
        CompletableFuture<Boolean> completableFuture = new CompletableFuture<>();
        Map<String, Boolean> results = new HashMap<>(numOfThreads);


        try(StompClient stompClient = new StompClient(endpoint)) {

            // subscribe
            String topic = "/user/" + stompClient.getClientKey() + "/echo/message";
            stompClient.subscribeToTopic(topic, EchoModel.class, (result, error) -> {
                results.put(result.message, true);
            });

            List<StompClientSendWorker> workers = new ArrayList<>();
            for (int i = 0; i < numOfThreads; i++) {
                workers.add(new StompClientSendWorker(new EchoModel("hello world " + i), stompClient, pool));
            }

            // start after 2 seconds
            ScheduledExecutorService scheduledExecutorService = Executors.newSingleThreadScheduledExecutor();
            scheduledExecutorService.schedule(() -> {
                        workers.parallelStream().forEach(StompClientSendWorker::call);
                        pool.shutdown();
                    },
                    2,
                    TimeUnit.SECONDS
            );

            scheduledExecutorService.schedule(() -> {
                        completableFuture.complete(results.values().parallelStream().allMatch(ok -> ok));
                    },
                    3,
                    TimeUnit.SECONDS
            );
            scheduledExecutorService.shutdown();


            // wait for 4 seconds
            assertTrue(completableFuture.get(6, TimeUnit.SECONDS));

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

    private static class StompClientSendWorker {
        private final EchoModel echoModel;
        private final StompClient stompClient;
        private final ExecutorService pool;

        public StompClientSendWorker(EchoModel echoModel, StompClient stompClient, ExecutorService pool) {
            this.echoModel = echoModel;
            this.stompClient = stompClient;
            this.pool = pool;
        }

        public void call() {
            CompletableFuture.runAsync(() -> {
                stompClient.send("/echo/message", Optional.of(echoModel));
            }, pool);
        }
    }
}
