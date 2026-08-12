package com.jarvis.tools.runtime;

import java.util.Set;

/**
 * Marketplace domain constraint inferred from user and model text.
 *
 * @param allowedDomains allowed marketplace domains, empty means unrestricted
 */
public record MarketplaceDomainConstraint(Set<String> allowedDomains) {

    /**
     * Creates a constraint with defensive domain copy.
     *
     * @param allowedDomains allowed marketplace domains
     */
    public MarketplaceDomainConstraint {
        allowedDomains = allowedDomains == null ? Set.of() : Set.copyOf(allowedDomains);
    }

    /**
     * Returns whether marketplace search is restricted to explicit domains.
     *
     * @return true when one or more domains are constrained
     */
    public boolean restricted() {
        return !allowedDomains.isEmpty();
    }

    /**
     * Returns the first domain for compatibility with older call sites.
     *
     * @return first domain or blank
     */
    public String primaryDomain() {
        return allowedDomains.stream().findFirst().orElse("");
    }
}
