package com.jarvis.common.prompt;

/**
 * Result of grounded response validation.
 *
 * @param valid whether the response passed validation
 * @param reason validation failure reason
 * @param unsupportedClaimDetected whether an unsupported personal claim was detected
 */
public record GroundedValidationResult(
        boolean valid,
        String reason,
        boolean unsupportedClaimDetected
) {
    /**
     * Creates a successful validation result.
     *
     * @return valid result
     */
    public static GroundedValidationResult success() {
        return new GroundedValidationResult(true, "", false);
    }

    /**
     * Creates a failed validation result.
     *
     * @param reason failure reason
     * @return failed result
     */
    public static GroundedValidationResult invalid(String reason) {
        return new GroundedValidationResult(false, reason == null ? "" : reason, true);
    }
}
