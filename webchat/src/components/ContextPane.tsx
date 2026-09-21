import { useState } from "react";
import { downloadWorkspaceFile, saveBlobAs } from "../api";
import { formatBytes } from "../format";
import type { WorkspaceItem } from "../types";
import { Icon } from "./Icon";

interface ContextPaneProps {
  workspacePath: string;
  workspaceItems: WorkspaceItem[];
  workspaceFilePath: string | null;
  workspaceContent: string;
  workspaceDirty: boolean;
  onOpenConversations: () => void;
  onWorkspacePath: () => void;
  onWorkspaceItem: (item: WorkspaceItem) => void;
  onWorkspaceRefresh: () => void;
  onWorkspaceContent: (content: string) => void;
  onWorkspaceSave: () => void;
}

export function ContextPane({
  workspacePath,
  workspaceItems,
  workspaceFilePath,
  workspaceContent,
  workspaceDirty,
  onOpenConversations,
  onWorkspacePath,
  onWorkspaceItem,
  onWorkspaceRefresh,
  onWorkspaceContent,
  onWorkspaceSave,
}: ContextPaneProps) {
  const [downloading, setDownloading] = useState(false);
  const [downloadError, setDownloadError] = useState("");

  const handleDownload = async () => {
    if (!workspaceFilePath || downloading) return;
    setDownloading(true);
    setDownloadError("");
    try {
      const blob = await downloadWorkspaceFile(workspaceFilePath);
      saveBlobAs(blob, workspaceFilePath.split("/").pop() || "workspace-file");
    } catch (error) {
      setDownloadError(error instanceof Error ? error.message : "下载失败");
    } finally {
      setDownloading(false);
    }
  };

  return (
    <aside className="context-pane">
      <div className="mobile-context-header">
        <button className="appbar-icon" type="button" aria-label="打开对话列表" onClick={onOpenConversations}>
          <Icon name="menu" size={20} />
        </button>
        <strong>Linux 工作区</strong>
        <span />
      </div>

      <section id="workspace-panel" className="context-panel active">
        <header className="context-header">
          <div>
            <strong>Linux 工作区</strong>
            <button className="path-button" type="button" title={workspacePath} onClick={onWorkspacePath}>
              {workspacePath || "/workspace"}
            </button>
          </div>
          <div className="header-actions">
            {workspaceFilePath && (
              <button
                className="quiet-link"
                type="button"
                title="下载文件"
                disabled={downloading}
                onClick={handleDownload}
              >
                <Icon name="download" size={15} /><span>{downloading ? "下载中…" : "下载"}</span>
              </button>
            )}
            {downloadError && <span className="workspace-download-error">{downloadError}</span>}
            <button className="quiet-button" type="button" onClick={onWorkspaceRefresh}>
              <Icon name="refresh" size={14} /><span>刷新</span>
            </button>
            <button
              className="primary-small-button"
              type="button"
              disabled={!workspaceDirty || !workspaceFilePath}
              onClick={onWorkspaceSave}
            ><Icon name="save" size={14} /><span>保存</span></button>
          </div>
        </header>
        <div className="workspace-layout">
          <div className="workspace-list">
            {!workspaceItems.length && <div className="list-empty">当前工作区为空</div>}
            {workspaceItems.map((item) => (
              <button
                className={`workspace-item${item.path === workspaceFilePath ? " active" : ""}`}
                type="button"
                onClick={() => onWorkspaceItem(item)}
                key={item.path}
              >
                <Icon name={item.isDirectory ? "folder" : "file"} size={15} />
                <span>{item.name}</span>
                <small>{item.isDirectory ? "" : formatBytes(item.size)}</small>
              </button>
            ))}
          </div>
          <div className="workspace-editor-wrap">
            <p>{workspaceFilePath || "选择文件以查看或编辑"}</p>
            <textarea
              id="workspace-editor"
              spellCheck={false}
              disabled={!workspaceFilePath}
              value={workspaceContent}
              onChange={(event) => onWorkspaceContent(event.target.value)}
            />
          </div>
        </div>
      </section>
    </aside>
  );
}
