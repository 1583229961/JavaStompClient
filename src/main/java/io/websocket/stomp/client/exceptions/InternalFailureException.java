package io.websocket.stomp.client.exceptions;

public class InternalFailureException extends RuntimeException {

    public InternalFailureException(String message) {
        super(message);
    }

    private InternalFailureException(Throwable t) {
        super(t.getMessage(), t);
    }

    public static InternalFailureException of(Throwable t) {
        if (t instanceof InternalFailureException)
            return (InternalFailureException) t;
        else
            return new InternalFailureException(t);
    }
}
