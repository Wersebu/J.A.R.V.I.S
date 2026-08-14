package com.jarvis.common.model;

/**
 * Result of a request to change the active model.
 *
 * @param success whether the switch was applied
 * @param activeModel the active model after the request (unchanged when {@code success} is false)
 * @param error human-readable rejection reason, when {@code success} is false
 */
public record ModelSwitchResult(boolean success, String activeModel, String error) {

    /**
     * Creates a successful switch result.
     *
     * @param activeModel newly active model
     * @return success result
     */
    public static ModelSwitchResult success(String activeModel) {
        return new ModelSwitchResult(true, activeModel, null);
    }

    /**
     * Creates a rejected switch result.
     *
     * @param currentActiveModel model that remains active
     * @param error rejection reason
     * @return rejection result
     */
    public static ModelSwitchResult rejected(String currentActiveModel, String error) {
        return new ModelSwitchResult(false, currentActiveModel, error);
    }
}
