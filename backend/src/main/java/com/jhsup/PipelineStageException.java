package com.jhsup;

/**
 * Thrown when an agentic pipeline stage exhausts all retries without passing validation.
 * Carries the ValidationContext so callers can log the stage name and last reprompt hint.
 */
public class PipelineStageException extends RuntimeException {

    private final ValidationContext context;

    public PipelineStageException(String message, ValidationContext context) {
        super(message + " [stage=" + context.stage() + ", lastHint=" + context.repromptHint() + "]");
        this.context = context;
    }

    public ValidationContext getContext() {
        return context;
    }
}
