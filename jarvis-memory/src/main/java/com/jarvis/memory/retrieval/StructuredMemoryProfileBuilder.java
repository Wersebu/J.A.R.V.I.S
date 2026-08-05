package com.jarvis.memory.retrieval;

import com.jarvis.common.memory.MemoryCategory;
import com.jarvis.common.memory.MemoryRecord;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Builds a grouped user profile for prompt injection.
 */
@Component
public class StructuredMemoryProfileBuilder implements MemoryProfileBuilder {

    @Override
    public String buildProfile(List<MemoryRecord> memories) {
        if (memories == null || memories.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        builder.append("=== USER PROFILE ===\n\n");
        Map<MemoryCategory, List<MemoryRecord>> grouped = memories.stream()
                .sorted(Comparator.comparing(MemoryRecord::updatedAt).reversed())
                .collect(Collectors.groupingBy(MemoryRecord::category));
        append(builder, grouped, MemoryCategory.DEVICE, "Hardware");
        append(builder, grouped, MemoryCategory.PROGRAMMING, "Programming");
        append(builder, grouped, MemoryCategory.PROJECT, "Current Projects");
        append(builder, grouped, MemoryCategory.VEHICLE, "Vehicles");
        append(builder, grouped, MemoryCategory.WORK, "Work");
        append(builder, grouped, MemoryCategory.PREFERENCE, "Preferences");
        append(builder, grouped, MemoryCategory.PERSON, "People");
        append(builder, grouped, MemoryCategory.LOCATION, "Locations");
        append(builder, grouped, MemoryCategory.SEMANTIC, "Facts");
        return builder.toString();
    }

    private void append(
            StringBuilder builder,
            Map<MemoryCategory, List<MemoryRecord>> grouped,
            MemoryCategory category,
            String heading
    ) {
        List<MemoryRecord> records = grouped.get(category);
        if (records == null || records.isEmpty()) {
            return;
        }
        builder.append(heading).append("\n");
        for (MemoryRecord record : records) {
            builder.append("- ").append(clean(record.content())).append("\n");
        }
        builder.append("\n");
    }

    private String clean(String content) {
        if (content == null) {
            return "";
        }
        return content.replaceFirst("(?i)^user\\s+", "User ");
    }
}
