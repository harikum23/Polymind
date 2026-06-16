package com.polymind.admission;

/** Thrown when the admission queue is saturated; mapped to HTTP 429 at the edge. */
public class BackpressureException extends RuntimeException {

    public BackpressureException(String message) {
        super(message);
    }
}
