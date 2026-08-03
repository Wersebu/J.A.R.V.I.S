package com.jarvis.knowledge;

import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;

/**
 * Detects supported knowledge document file types.
 */
@Service
public class SupportedFileTypes {

    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of(
            "md",
            "txt",
            "pdf",
            "docx",
            "json",
            "xml",
            "yml",
            "yaml",
            "java",
            "properties"
    );

    /**
     * Checks whether a path is supported.
     *
     * @param path file path
     * @return true when supported
     */
    public boolean supports(Path path) {
        return SUPPORTED_EXTENSIONS.contains(extension(path));
    }

    /**
     * Extracts the lowercase extension.
     *
     * @param path file path
     * @return extension without dot
     */
    public String extension(Path path) {
        String fileName = path.getFileName().toString();
        int index = fileName.lastIndexOf('.');
        if (index < 0 || index == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(index + 1).toLowerCase(Locale.ROOT);
    }
}
