package com.salkcoding.oswl.exception;

/** Request payload failed validation — mapped to HTTP 400 with the message as JSON. */
public class InvalidRequestException extends RuntimeException {

    public InvalidRequestException(String message) {
        super(message);
    }
}
