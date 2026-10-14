package com.salon.exception;

/** Base exception for all business-rule problems (shown to the user as a message). */
public class BookingException extends RuntimeException {
    public BookingException(String message) {
        super(message);
    }
}
