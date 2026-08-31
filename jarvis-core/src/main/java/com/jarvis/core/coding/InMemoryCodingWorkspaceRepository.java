package com.jarvis.core.coding;

import com.jarvis.api.service.CodingService;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

class InMemoryCodingWorkspaceRepository implements CodingWorkspaceRepository {

    private final ConcurrentMap<String, CodingService.CodingWorkspace> workspaces = new ConcurrentHashMap<>();

    @Override
    public CodingService.CodingWorkspace save(CodingService.CodingWorkspace workspace) {
        workspaces.put(workspace.id(), workspace);
        return workspace;
    }

    @Override
    public Optional<CodingService.CodingWorkspace> findById(String workspaceId) {
        return Optional.ofNullable(workspaces.get(workspaceId));
    }

    @Override
    public List<CodingService.CodingWorkspace> findAll() {
        return workspaces.values().stream()
                .sorted(Comparator.comparing(CodingService.CodingWorkspace::lastUsedAt, Comparator.reverseOrder()))
                .toList();
    }

    @Override
    public void delete(String workspaceId) {
        workspaces.remove(workspaceId);
    }
}
