package com.jarvis.core.coding;

import com.jarvis.api.service.CodingService;

import java.util.List;
import java.util.Optional;

interface CodingTaskRepository {

    CodingService.CodingTask save(CodingService.CodingTask task);

    Optional<CodingService.CodingTask> findById(String taskId);

    List<CodingService.CodingTask> findAll();
}
