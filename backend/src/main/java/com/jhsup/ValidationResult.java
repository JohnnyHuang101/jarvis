package com.jhsup;

import java.util.List;

/**
 * Result returned by every StageValidator.
 * On failure, 'repromptHint' is appended to the next LLM call so the model
 * knows exactly what it got wrong.
 */
public record ValidationResult(
    boolean passed,
    List<String> failures,
    String repromptHint     // null when passed == true
) {
    /** Convenience: a clean pass with no failures. */
    public static ValidationResult ok() {
        return new ValidationResult(true, List.of(), null);
    }

    /** Convenience: build a failure result from a list of issues. */
    public static ValidationResult fail(List<String> failures) {
        String hint = "Fix these issues before returning: " + String.join("; ", failures);
        return new ValidationResult(false, failures, hint);
    }
}
