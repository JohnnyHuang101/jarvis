package com.jhsup;

/**
 * Generic validator gate between pipeline stages.
 * Implement one per stage; wire via AgenticService.withRetry().
 */
@FunctionalInterface
public interface StageValidator<T> {
    ValidationResult validate(T output, ValidationContext ctx);
}
