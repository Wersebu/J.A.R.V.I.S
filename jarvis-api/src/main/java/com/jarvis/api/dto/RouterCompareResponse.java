package com.jarvis.api.dto;

import java.util.List;

/**
 * Router comparison response.
 *
 * @param decisions decisions for each query
 */
public record RouterCompareResponse(List<RouterAnalyzeResponse> decisions) {
}
