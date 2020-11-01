package io.websocket.stomp.client.internal;


import io.websocket.stomp.client.exceptions.InternalFailureException;
import io.websocket.stomp.client.models.ErrorModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;

import java.lang.reflect.Type;
import java.util.concurrent.BlockingQueue;

/**
 * Class which handles the messages of a websocket topic. It uses a queue to publish and consume the result.
 * 
 * @param <T> the type of the result
 */
public class FrameHandler<T> implements StompFrameHandler {
    private final static Logger LOGGER = LoggerFactory.getLogger(FrameHandler.class);
    private final Class<T> resultTypeClass;
    private final BlockingQueue<Object> queue;

    public FrameHandler(Class<T> resultTypeClass, BlockingQueue<Object> queue) {
        this.resultTypeClass = resultTypeClass;
        this.queue = queue;
    }

    @Override
    public Type getPayloadType(StompHeaders headers) {
        return resultTypeClass;
    }

    @Override
    public void handleFrame(StompHeaders headers, Object payload) {

        try {
            if (payload == null)
                queue.put(new ErrorModel(new InternalFailureException("Received a null payload")));
            else if (payload instanceof GsonMessageConverter.NullObject)
                queue.put(new GsonMessageConverter.NullObject());
            else if (payload instanceof ErrorModel)
                queue.put(payload);
            else if (payload.getClass() != resultTypeClass)
                queue.put(new ErrorModel(new InternalFailureException(String.format("Unexpected payload type [%s]: expected [%s]" + payload.getClass().getName(), resultTypeClass))));
            else
                queue.put(payload);
        }
        catch (Exception e) {
            LOGGER.info("[WsClient] Queue put error: " + e.getMessage());
        }

    }
}