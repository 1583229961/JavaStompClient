package io.websocket.stomp.client.exceptions;

import io.websocket.stomp.client.models.ErrorModel;

public class NetworkExceptionResponse extends RuntimeException {
    public final ErrorModel errorModel;

    public NetworkExceptionResponse(ErrorModel errorModel) {
        this.errorModel = errorModel;
    }
}
