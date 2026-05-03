package com.jhsup;

/**
 * Carries context through a retry loop so each attempt can include
 * the previous failure's hint in the reprompt.
 */
public record ValidationContext(
    String stage,           // e.g. "EXAMS_EXTRACTED"
    String repromptHint     // appended to user prompt on retry; null on first attempt
) {
    public ValidationContext withRepromptHint(String hint) {
        return new ValidationContext(this.stage, hint);
    }

    /** Convenience factory — no hint on first attempt. */
    public static ValidationContext of(String stage) {
        return new ValidationContext(stage, null);
    }
}
