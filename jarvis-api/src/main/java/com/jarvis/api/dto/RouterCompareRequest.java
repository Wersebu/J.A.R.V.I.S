package com.jarvis.api.dto;

import java.util.List;

/**
 * Request for comparing router decisions.
 *
 * @param queries queries to analyze
 */
public record RouterCompareRequest(List<String> queries) {
}
