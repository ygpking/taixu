const API_ROOT = "/webchat/api";
let authToken = "";

export function setAuthToken(token: string): void {
  authToken = token.trim();
}

/**
 * 会话 Cookie 名，与后端 `WebChatBridgeServer.SESSION_COOKIE` 必须一致。
 */
const SESSION_COOKIE = "wc_session";

/**
 * 把配对码写入 Cookie，供 `EventSource` 复用。
 *
 * 背景：SSE 走 `EventSource`，而它**不能自定义请求头**，配对码只能通过 URL 传递 ——
 * 但 URL 会被写进访问日志、浏览器历史与 Referer，等于持续泄露
 * 一个能读写工作区文件的宿主级凭据。种成 Cookie 后，SSE 请求会自动带上它，
 * URL 里不再需要出现配对码。
 *
 * 不带 `HttpOnly`（前端读不到就无法复用），`SameSite=Strict` 限制跨站携带。
 */
export function plantSessionCookie(token: string): void {
  const value = token.trim();
  if (!value) return;
  document.cookie = `${SESSION_COOKIE}=${encodeURIComponent(value)}; Path=/webchat; SameSite=Strict; Max-Age=86400`;
}

export function clearSessionCookie(): void {
  document.cookie = `${SESSION_COOKIE}=; Path=/webchat; SameSite=Strict; Max-Age=0`;
}

interface RequestOptions {
  method?: "GET" | "POST" | "PATCH" | "PUT" | "DELETE";
  body?: unknown;
  query?: Record<string, string | number | boolean | null | undefined>;
}

export async function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const { method = "GET", body, query } = options;
  const url = new URL(`${API_ROOT}${path}`, window.location.origin);
  if (query) {
    Object.entries(query).forEach(([key, value]) => {
      if (value !== undefined && value !== null && value !== "") {
        url.searchParams.set(key, String(value));
      }
    });
  }

  const response = await fetch(url, {
    method,
    credentials: "same-origin",
    headers: {
      Accept: "application/json",
      ...(authToken ? { Authorization: `Bearer ${authToken}` } : {}),
      ...(body === undefined ? {} : { "Content-Type": "application/json" }),
    },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const responseText = await response.text();
  let payload: unknown = null;
  if (responseText) {
    try {
      payload = JSON.parse(responseText);
    } catch {
      payload = responseText;
    }
  }
  if (!response.ok) {
    const record = isRecord(payload) ? payload : null;
    const message = record?.error ?? record?.message ?? payload;
    throw new Error(String(message || `请求失败 (${response.status})`));
  }
  return payload as T;
}

export function workspaceDownloadUrl(path: string): string {
  return `${API_ROOT}/workspaces/download?path=${encodeURIComponent(path)}&token=${encodeURIComponent(authToken)}`;
}

export function eventsUrl(): string {
  // 不携带 query token：SSE 改由 wc_session Cookie 认证（见 plantSessionCookie）。
  // 保留本函数签名以便调用方无感迁移。
  return `${API_ROOT}/events`;
}

export function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}
