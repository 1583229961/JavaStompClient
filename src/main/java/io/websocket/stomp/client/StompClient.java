package io.websocket.stomp.client;


import io.websocket.stomp.client.exceptions.InternalFailureException;
import io.websocket.stomp.client.exceptions.NetworkExceptionResponse;
import io.websocket.stomp.client.internal.FrameHandler;
import io.websocket.stomp.client.internal.StompClientSessionHandler;
import io.websocket.stomp.client.internal.GsonMessageConverter;
import io.websocket.stomp.client.models.ErrorModel;
import net.jcip.annotations.ThreadSafe;
import org.apache.tomcat.websocket.WsWebSocketContainer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSession.Subscription;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.function.BiConsumer;


/**
 * A thread safe webSocket client class to subscribe, send and receive messages from a websockets endpoint over STOMP.
 */
@ThreadSafe
public class StompClient implements AutoCloseable {
    private final static Logger LOGGER = LoggerFactory.getLogger(StompClient.class);

    private final static int MESSAGE_SIZE_LIMIT = 4 * 512 * 1024;

	/**
	 * The supporting STOMP client.
	 */
	private final WebSocketStompClient stompClient;

    /**
     * The unique identifier of this client. This allows more clients to connect to the same server.
     */
    private final String clientKey;

    /**
     * The websockets end-point.
     */
    private final String url;

    /**
     * The current session.
     */
    private StompSession stompSession;

    /**
     * The lock that guards all accessed to {@code stompSession}.
     */
    private final Object stompSessionLock = new Object();

    /**
     * The websockets subscriptions open so far with this client, per topic.
     */
    private final ConcurrentHashMap<String, Subscription> subscriptions = new ConcurrentHashMap<>();

    /**
     * The websockets queues where the results are published and consumed, per topic.
     */
    private final ConcurrentHashMap<String, BlockingQueue<Object>> queues = new ConcurrentHashMap<>();

    /**
     * Creates an instance of a websockets client to subscribe, send and receive messages from a websockets end-point.
     * 
     * @param url the websockets end-point
     * @throws ExecutionException if the computation threw an exception
     * @throws InterruptedException if the current thread was interrupted
     */
    public StompClient(String url) throws ExecutionException, InterruptedException {
        this.url = url;
        this.clientKey = generateClientKey();

        // container configuration with the message size limit
        WsWebSocketContainer wsWebSocketContainer = new WsWebSocketContainer();
        wsWebSocketContainer.setDefaultMaxTextMessageBufferSize(MESSAGE_SIZE_LIMIT); // default 8192
        wsWebSocketContainer.setDefaultMaxBinaryMessageBufferSize(MESSAGE_SIZE_LIMIT); // default 8192

        ThreadPoolTaskScheduler threadPoolTaskScheduler = new ThreadPoolTaskScheduler();
        threadPoolTaskScheduler.setPoolSize(Runtime.getRuntime().availableProcessors());
        threadPoolTaskScheduler.initialize();

        this.stompClient = new WebSocketStompClient(new StandardWebSocketClient(wsWebSocketContainer));
        this.stompClient.setInboundMessageSizeLimit(MESSAGE_SIZE_LIMIT); // default 64 * 1024
        this.stompClient.setMessageConverter(new GsonMessageConverter());
        this.stompClient.setTaskScheduler(threadPoolTaskScheduler);
        connect();
    }

    /**
     * Returns the key of this client instance. Each instance has a different key.
     * @return the client key
     */
    public String getClientKey() {
        return this.clientKey;
    }


    /**
     * Subscribes and sends a request for the given topic, expecting a result of the given type and
     * bearing the given payload. The subscription is recycled.
     *
     * @param topic the topic
     * @param resultTypeClass the result class type
     * @param payload the payload, if any
     * @return the result of the request
     * @throws NetworkExceptionResponse if the server returned an error
     * @throws InterruptedException if interrupted while waiting
     */
    @SuppressWarnings("unchecked")
	public <T> T subscribeAndSend(String topic, Class<T> resultTypeClass, Optional<Object> payload) throws InterruptedException {
        String resultTopic = "/user/" + clientKey + topic;
        Object result;

        BlockingQueue<Object> queue = queues.computeIfAbsent(topic, _key -> new LinkedBlockingQueue<>(1));
        synchronized (queue) {
            subscribe(resultTopic, resultTypeClass, queue);
            send(topic, payload);
            result = queue.take();
        }
        LOGGER.info("[WsClient] Received message from topic " + topic);

        if (result instanceof ErrorModel)
            throw new NetworkExceptionResponse((ErrorModel) result);
        else if (result instanceof GsonMessageConverter.NullObject)
            return null;
        else
            return (T) result;
    }

    /**
     * Sends an optional payload to a destination.
     * @param destination the destination
     * @param payload the payload
     */
    public void send(String destination, Optional<Object> payload) {
        LOGGER.info("[WsClient] Sending message to destination " + destination);
        synchronized (stompSessionLock) {
            stompSession.send(destination, payload.orElse(null));
        }
    }

    /**
     * Subscribes to a topic and then handles the result published by the topic.
     * @param topic the topic destination
     * @param resultTypeClass the result type class
     * @param handler the handler of the result
     * @param <T> the result type class
     */
    public <T> void subscribeToTopic(String topic, Class<T> resultTypeClass, BiConsumer<T, ErrorModel> handler) {
    	subscriptions.computeIfAbsent(topic, _topic -> {

    		StompFrameHandler stompHandler = new StompFrameHandler() {

    			@Override
    			public Type getPayloadType(StompHeaders headers) {
    				return resultTypeClass;
    			}

    			@SuppressWarnings("unchecked")
    			@Override
    			public void handleFrame(StompHeaders headers, Object payload) {
                    LOGGER.info("[WsClient] Received message from topic " + _topic);

    				CompletableFuture.runAsync(() -> {
    					if (payload == null)
    						handler.accept(null, new ErrorModel(new InternalFailureException("Received a null payload")));
    					else if (payload instanceof GsonMessageConverter.NullObject)
    						handler.accept(null, new ErrorModel(new InternalFailureException("Received a null object")));
    					else if (payload instanceof ErrorModel)
    						handler.accept(null, (ErrorModel) payload);
    					else if (payload.getClass() != resultTypeClass)
    						handler.accept(null, new ErrorModel(new InternalFailureException(String.format("Unexpected payload type [%s]: expected [%s]" + payload.getClass().getName(), resultTypeClass))));
    					else
    						handler.accept((T) payload, null);
    				});
    			}
    		};

            return subscribeInternal(topic, stompHandler);
    	});
    }

    /**
     * Subscribes to a topic and register its queue where to deliver the result. The subscription is recycled.
     * @param topic the topic
     * @param resultTypeClass the result type
     * @param queue the queue
     * @param <T> the type of the result
     */
    private <T> void subscribe(String topic, Class<T> resultTypeClass, BlockingQueue<Object> queue) {
        subscriptions.computeIfAbsent(topic, _topic -> subscribeInternal(_topic, new FrameHandler<>(resultTypeClass, queue)));
    }

    /**
     * Internal method to subscribe to a topic and to get a subscription. The subscription is recycled.
     * @param topic the topic
     * @param handler the frame handler of the topic
     * @return the stomp subscription
     */
    private Subscription subscribeInternal(String topic, StompFrameHandler handler) {
        CompletableFuture<Boolean> subscriptionCompletion = new CompletableFuture<>();
        StompHeaders stompHeaders = new StompHeaders();
        stompHeaders.setDestination(topic);
        stompHeaders.setReceipt("receipt_" + topic);

        Subscription stompSubscription;
        synchronized (stompSessionLock) {
            stompSubscription = stompSession.subscribe(stompHeaders, handler);
        }

        stompSubscription.addReceiptTask(() -> subscriptionCompletion.complete(true));
        stompSubscription.addReceiptLostTask(() -> subscriptionCompletion.complete(false));

        try {
            boolean successfulSubscription = subscriptionCompletion.get();
            if (!successfulSubscription) {
                throw new InternalFailureException("Subscription to " + topic + " failed");
            }
        }
        catch (InterruptedException | ExecutionException e) {
            throw InternalFailureException.of(e);
        }

        LOGGER.info("[WsClient] Subscribed to topic " + topic);
        return stompSubscription;
    }

    /**
     * Connects to the websockets end-point and creates the current session.
     * 
     * @throws CancellationException if the computation was cancelled
     * @throws ExecutionException if the computation threw an exception
     * @throws InterruptedException if the current thread was interrupted
     */
    private void connect() throws ExecutionException, InterruptedException {
        LOGGER.info("[WsClient] Connection to " + this.url);

        WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
        headers.add("uuid", clientKey);

        synchronized (stompSessionLock) {
        	stompSession = stompClient.connect(url, headers, new StompClientSessionHandler(this::onSessionError)).get();
        }

        LOGGER.info("[WsClient] Connected to stomp session " + stompSession.getSessionId());
    }

    private void onSessionError(Throwable throwable) {
        LOGGER.info("[WsClient] Got a session error: " + throwable.getMessage());

        try {
            // on session error, the session gets closed so we reconnect to the websocket endpoint
            subscriptions.values().forEach(Subscription::unsubscribe);
            subscriptions.clear();
            queues.clear();

            connect();
        }
        catch (ExecutionException | InterruptedException e) {
            LOGGER.info("[WsClient] Cannot reconnect to session");
            throw InternalFailureException.of(e);
        }
    }

    @Override
    public void close() {
        LOGGER.info("[WsClient] Closing session and websocket client");

    	subscriptions.values().forEach(Subscription::unsubscribe);
    	subscriptions.clear();
    	queues.clear();

    	synchronized (stompSessionLock) {
    		if (stompSession != null)
    			stompSession.disconnect();
    	}

    	stompClient.stop();
    }

    /**
     * Generates a unique key for this websockets client.
     * 
     * @return the unique key
     */
    private static String generateClientKey() {
        try {
            MessageDigest salt = MessageDigest.getInstance("SHA-256");
            salt.update(UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8));
            return bytesToHex(salt.digest());
        }
        catch (Exception e) {
            return UUID.randomUUID().toString();
        }
    }

    private static String bytesToHex(byte[] bytes) {
        byte [] HEX_ARRAY = "0123456789abcdef".getBytes();
        byte[] hexChars = new byte[bytes.length * 2];
        for (int j = 0; j < bytes.length; j++) {
            int v = bytes[j] & 0xFF;
            hexChars[j * 2] = HEX_ARRAY[v >>> 4];
            hexChars[j * 2 + 1] = HEX_ARRAY[v & 0x0F];
        }

        return new String(hexChars, StandardCharsets.UTF_8);
    }
}