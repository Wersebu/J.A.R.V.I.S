package com.jarvis.core.coding;

import com.jarvis.api.service.CodingService;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

class InMemoryCodingApprovalRepository implements CodingApprovalRepository {

    private final ConcurrentHashMap<String, CodingService.CodingApproval> approvals = new ConcurrentHashMap<>();

    @Override
    public CodingService.CodingApproval save(CodingService.CodingApproval approval) {
        approvals.put(approval.id(), approval);
        return approval;
    }

    @Override
    public Optional<CodingService.CodingApproval> findById(String approvalId) {
        return Optional.ofNullable(approvals.get(approvalId));
    }

    @Override
    public List<CodingService.CodingApproval> findByTaskId(String taskId) {
        return approvals.values().stream()
                .filter(approval -> approval.taskId().equals(taskId))
                .sorted(Comparator.comparing(CodingService.CodingApproval::createdAt))
                .toList();
    }
}
