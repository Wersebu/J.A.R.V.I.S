package com.jarvis.core.coding;

import com.jarvis.api.service.CodingService;

import java.util.List;
import java.util.Optional;

interface CodingWorkspaceRepository {

    CodingService.CodingWorkspace save(CodingService.CodingWorkspace workspace);

    Optional<CodingService.CodingWorkspace> findById(String workspaceId);

    List<CodingService.CodingWorkspace> findAll();

    void delete(String workspaceId);
}
