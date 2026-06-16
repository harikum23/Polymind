package com.polymind.tools;

/** Raised on web-search failures (provider error or quota exceeded). */
public class WebSearchException extends RuntimeException {

    public WebSearchException(String message) {
        super(message);
    }

    public WebSearchException(String message, Throwable cause) {
        super(message, cause);
    }
}
