package com.debugbundle.sdk;

/** Validation could not finish safely; restoring pre-hook content is forbidden. */
final class JsonValidationBudgetExceeded extends RuntimeException {
    JsonValidationBudgetExceeded() {
        super("JSON validation unavailable or exhausted");
    }
}
