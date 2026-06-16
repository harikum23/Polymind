package com.polymind.inference;

/** Raised when an inference engine call fails. Wrapped by the resilience module in later steps. */
public class EngineException extends RuntimeException {

    public EngineException(String message) {
        super(message);
    }

    public EngineException(String message, Throwable cause) {
        super(message, cause);
    }
}
