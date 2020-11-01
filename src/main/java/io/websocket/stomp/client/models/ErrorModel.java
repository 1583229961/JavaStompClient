package io.websocket.stomp.client.models;

import jdk.nashorn.internal.ir.annotations.Immutable;

@Immutable
public class ErrorModel {
    /**
     * The message of the exception.
     */
    public final String message;

    /**
     * The fully-qualified name of the class of the exception.
     */
    public final String exceptionClassName;

    /**
     * Builds the model of an exception.
     *
     * @param message the message of the exception
     * @param exceptionClass the class of the exception
     */
    public ErrorModel(String message, Class<? extends Exception> exceptionClass) {
        this.message = message;
        this.exceptionClassName = exceptionClass.getName();
    }

    public ErrorModel(Exception e) {
        this(e.getMessage() != null ? e.getMessage() : "", e.getClass());
    }

}
