# Workspace File Library Plan

## Goal

Provide PM chat with personal, team, and service workspaces where users and bots can store files, inspect submitted artifacts, share access, lock sensitive areas, and keep basic file version history.

## Phase 1: Core Library

- Add `PERSONAL`, `TEAM`, and `SERVICE` workspaces.
- Add workspace members with `OWNER`, `ADMIN`, `EDITOR`, `VIEWER`, and `SERVICE` roles.
- Store files independently from chat attachments under the workspace file directory.
- Add folders, file versions, lock state, bot source metadata, and explicit user/bot permissions.
- Expose authenticated APIs for listing workspaces, browsing contents, uploading files, adding versions, downloading files, locking workspaces/folders/files, and granting permissions.
- Add a desktop-oriented frontend library page with workspace navigation, upload, download, lock, and version inspection.

## Phase 2: Permission UI Completion

- Add member management UI for workspace roles.
- Add explicit share dialogs for users and bots on workspace/folder/file resources.
- Add inherited permission explanation so users can see why a file is visible or editable.
- Add folder lock controls in the UI.

## Phase 3: Bot And Agent Integration

- Let bot responses and agent task artifacts write directly into selected service workspaces.
- Add per-bot workspace allowlists and default target folders.
- Add audit views for bot-created and bot-edited files.
- Block bot writes by default unless workspace/folder/file bot access is explicitly enabled.

## Phase 4: Production Hardening

- Add storage quotas and orphan-file cleanup.
- Add file hash based duplicate detection.
- Add version restore and soft delete.
- Add preview/transcoding hooks for common document/image/video types.
- Add optional object storage backend and malware scanning hooks.
