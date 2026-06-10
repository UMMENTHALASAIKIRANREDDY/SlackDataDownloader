package com.cloudfuze.slackexport.validation;

/**
 * Thrown when export validation fails (JSON structure, Block Kit, thread integrity, date files, ordering).
 */
public class ValidationException extends RuntimeException {

    public ValidationException(String message) {
        super(message);
    }

    public ValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
