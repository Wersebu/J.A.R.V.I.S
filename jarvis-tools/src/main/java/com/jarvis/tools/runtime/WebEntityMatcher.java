package com.jarvis.tools.runtime;

import java.text.Normalizer;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Validates whether web search/page candidates describe the requested entity.
 */
public class WebEntityMatcher {

    private static final Pattern GPU_PATTERN = Pattern.compile("(?i)\\b(rtx|gtx|rx)\\s*-?\\s*(\\d{3,4})(?:\\s*-?\\s*(ti|super|xt))?\\b");
    private static final Pattern MEMORY_PATTERN = Pattern.compile("(?i)\\b(\\d{1,3})\\s*(gb|gib)\\b");
    private static final Set<String> GPU_ITEM_TERMS = Set.of(
            "gpu", "graphics", "graphic", "geforce", "radeon", "karta", "graficzna", "karty", "grafiki"
    );
    private static final Set<String> WHOLE_DEVICE_TERMS = Set.of(
            "komputer", "pc", "desktop", "zestaw", "laptop", "notebook", "workstation", "gamingowy"
    );

    /**
     * Builds an entity descriptor from request text.
     *
     * @param text request text
     * @return descriptor
     */
    public EntityDescriptor describe(String text) {
        String normalized = normalize(text);
        Matcher gpu = GPU_PATTERN.matcher(normalized);
        if (gpu.find()) {
            Set<String> identity = new LinkedHashSet<>();
            identity.add(gpu.group(1).toLowerCase(Locale.ROOT));
            identity.add(gpu.group(2));
            String suffix = gpu.group(3);
            if (suffix != null && !suffix.isBlank()) {
                identity.add(suffix.toLowerCase(Locale.ROOT));
            }
            Set<String> variants = new LinkedHashSet<>();
            Matcher memory = MEMORY_PATTERN.matcher(normalized);
            while (memory.find()) {
                variants.add(memory.group(1) + "gb");
            }
            Set<String> soft = new LinkedHashSet<>(GPU_ITEM_TERMS);
            Matcher adjacent = Pattern.compile("(?i)\\b(aorus|eagle|ventus|gaming|strix|tuf|msi|gigabyte|asus|palit|zotac)\\b")
                    .matcher(normalized);
            while (adjacent.find()) {
                soft.add(adjacent.group(1).toLowerCase(Locale.ROOT));
            }
            String canonical = String.join(" ", identity) + (variants.isEmpty() ? "" : " " + String.join(" ", variants));
            return new EntityDescriptor("GPU", canonical.strip(), identity, variants, soft);
        }
        return new EntityDescriptor("UNKNOWN", "", Set.of(), Set.of(), Set.of());
    }

    /**
     * Validates a candidate against the requested entity.
     *
     * @param requested requested entity
     * @param candidateText candidate text
     * @return match result
     */
    public EntityMatchResult match(EntityDescriptor requested, String candidateText) {
        String haystack = normalize(candidateText);
        if (requested == null || !requested.hasIdentity()) {
            return new EntityMatchResult(true, 0.5d, "No concrete entity requested.");
        }
        if ("GPU".equals(requested.productType())) {
            return matchGpu(requested, haystack);
        }
        long identityMatches = requested.identityTokens().stream().filter(haystack::contains).count();
        double score = requested.identityTokens().isEmpty() ? 0.0d : identityMatches / (double) requested.identityTokens().size();
        return new EntityMatchResult(score >= 0.75d, score, "Generic identity match.");
    }

    private EntityMatchResult matchGpu(EntityDescriptor requested, String haystack) {
        Matcher candidateGpu = GPU_PATTERN.matcher(haystack);
        boolean foundGpu = false;
        boolean exactGpu = false;
        while (candidateGpu.find()) {
            foundGpu = true;
            Set<String> candidateIdentity = new LinkedHashSet<>();
            candidateIdentity.add(candidateGpu.group(1).toLowerCase(Locale.ROOT));
            candidateIdentity.add(candidateGpu.group(2));
            String suffix = candidateGpu.group(3);
            if (suffix != null && !suffix.isBlank()) {
                candidateIdentity.add(suffix.toLowerCase(Locale.ROOT));
            }
            if (candidateIdentity.equals(requested.identityTokens())) {
                exactGpu = true;
                break;
            }
        }
        if (foundGpu && !exactGpu) {
            return new EntityMatchResult(false, 0.0d, "Hard entity mismatch: candidate GPU model differs.");
        }
        if (!exactGpu) {
            return new EntityMatchResult(false, 0.0d, "Requested GPU identity not present.");
        }
        for (String variant : requested.variantTokens()) {
            if (!haystack.contains(variant)) {
                return new EntityMatchResult(false, 0.45d, "Variant mismatch: requested " + variant + " not present.");
            }
        }
        if (looksLikeWholeDevice(haystack) && !looksLikeGraphicsCardListing(haystack)) {
            return new EntityMatchResult(false, 0.25d, "Product type mismatch: candidate is a whole computer/laptop, not a GPU listing.");
        }
        double score = 0.75d;
        long softMatches = requested.softTokens().stream().filter(haystack::contains).count();
        score += Math.min(0.2d, softMatches * 0.04d);
        score += requested.variantTokens().isEmpty() ? 0.0d : 0.05d;
        return new EntityMatchResult(true, Math.min(1.0d, score), "Entity matched.");
    }

    private boolean looksLikeWholeDevice(String haystack) {
        return WHOLE_DEVICE_TERMS.stream().anyMatch(haystack::contains);
    }

    private boolean looksLikeGraphicsCardListing(String haystack) {
        return GPU_ITEM_TERMS.stream().anyMatch(haystack::contains);
    }

    private String normalize(String value) {
        String noMarks = Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
        return noMarks.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").strip();
    }
}
