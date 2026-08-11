package com.jarvis.tools.runtime;

import java.util.Set;

/**
 * Lightweight entity descriptor used to validate web research candidates.
 *
 * @param productType detected product type
 * @param canonicalName normalized entity name
 * @param identityTokens hard identity tokens
 * @param variantTokens requested variant tokens
 * @param softTokens additional relevance tokens
 */
public record EntityDescriptor(
        String productType,
        String canonicalName,
        Set<String> identityTokens,
        Set<String> variantTokens,
        Set<String> softTokens
) {

    /**
     * Creates an immutable descriptor.
     */
    public EntityDescriptor {
        productType = productType == null ? "UNKNOWN" : productType;
        canonicalName = canonicalName == null ? "" : canonicalName;
        identityTokens = identityTokens == null ? Set.of() : Set.copyOf(identityTokens);
        variantTokens = variantTokens == null ? Set.of() : Set.copyOf(variantTokens);
        softTokens = softTokens == null ? Set.of() : Set.copyOf(softTokens);
    }

    /**
     * Returns whether this descriptor contains a concrete identity.
     *
     * @return true when identity tokens exist
     */
    public boolean hasIdentity() {
        return !identityTokens.isEmpty();
    }
}
