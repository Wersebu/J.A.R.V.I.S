package com.jarvis.core.coding;

import com.jarvis.api.service.CodingService;
import org.springframework.stereotype.Repository;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Repository
class InMemoryCodingTaskRepository implements CodingTaskRepository {

    private final ConcurrentMap<String, CodingService.CodingTask> tasks = new ConcurrentHashMap<>();

    @Override
    public CodingService.CodingTask save(CodingService.CodingTask task) {
        tasks.put(task.id(), task);
        return task;
    }

    @Override
    public Optional<CodingService.CodingTask> findById(String taskId) {
        return Optional.ofNullable(tasks.get(taskId));
    }

    @Override
    public List<CodingService.CodingTask> findAll() {
        return tasks.values().stream()
                .sorted(Comparator.comparing(CodingService.CodingTask::startedAt, Comparator.reverseOrder()))
                .toList();
    }
}
