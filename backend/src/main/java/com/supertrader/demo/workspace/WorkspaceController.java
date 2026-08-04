package com.supertrader.demo.workspace;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Module 5 workspace API — the entry object of the local platform shell.
 *
 * Contract (all inputs are validated server-side; NO credential is ever
 * returned; NO trading action is exposed):
 *   GET   /api/v1/workspaces           list + current selection
 *   POST  /api/v1/workspaces           create (server-validated unique name)
 *   GET   /api/v1/workspaces/current   the current workspace
 *   PUT   /api/v1/workspaces/current   switch the current workspace by id
 *   PATCH /api/v1/workspaces/{id}      rename a workspace
 *
 * There is deliberately NO delete route: at least one workspace always exists.
 * All state changes are persisted locally under rebuild/.run/ (Git-ignored).
 */
@RestController
@RequestMapping("/api/v1/workspaces")
public class WorkspaceController {

    private final WorkspaceStore store;

    public WorkspaceController(WorkspaceStore store) {
        this.store = store;
    }

    @GetMapping
    public WorkspaceDtos.WorkspaceListResponse list() {
        List<Workspace> workspaces = store.list();
        return new WorkspaceDtos.WorkspaceListResponse(
                "workspaces.list.v1", store.currentWorkspaceId(), workspaces);
    }

    @PostMapping
    public ResponseEntity<WorkspaceDtos.WorkspaceItemResponse> create(
            @RequestBody WorkspaceDtos.CreateRequest body) {
        Workspace created = store.create(body == null ? null : body.name());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new WorkspaceDtos.WorkspaceItemResponse(
                        "workspaces.item.v1", created));
    }

    @GetMapping("/current")
    public WorkspaceDtos.WorkspaceItemResponse current() {
        return new WorkspaceDtos.WorkspaceItemResponse(
                "workspaces.current.v1", store.current());
    }

    @PutMapping("/current")
    public WorkspaceDtos.WorkspaceItemResponse switchCurrent(
            @RequestBody WorkspaceDtos.SwitchRequest body) {
        Workspace current = store.switchTo(body == null ? null : body.id());
        return new WorkspaceDtos.WorkspaceItemResponse(
                "workspaces.current.v1", current);
    }

    @PatchMapping("/{id}")
    public WorkspaceDtos.WorkspaceItemResponse rename(
            @PathVariable("id") String id,
            @RequestBody WorkspaceDtos.RenameRequest body) {
        Workspace renamed = store.rename(id, body == null ? null : body.name());
        return new WorkspaceDtos.WorkspaceItemResponse(
                "workspaces.item.v1", renamed);
    }
}
