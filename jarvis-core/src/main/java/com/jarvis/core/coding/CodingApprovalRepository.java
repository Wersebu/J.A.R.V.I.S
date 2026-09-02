package com.jarvis.core.coding;

import com.jarvis.api.service.CodingService;

import java.util.List;
import java.util.Optional;

interface CodingApprovalRepository {

    CodingService.CodingApproval save(CodingService.CodingApproval approval);

    Optional<CodingService.CodingApproval> findById(String approvalId);

    List<CodingService.CodingApproval> findByTaskId(String taskId);
}
