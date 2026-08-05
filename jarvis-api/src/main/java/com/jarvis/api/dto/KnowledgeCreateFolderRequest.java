package com.jarvis.api.dto;

/**
 * Request to create a knowledge folder.
 *
 * @param parentId parent node identifier
 * @param name folder name
 */
public record KnowledgeCreateFolderRequest(String parentId, String name) {
}
