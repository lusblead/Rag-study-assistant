import type {
  AppSettings,
  ApiResult,
  ChatMessage,
  ChatSession,
  Course,
  CourseDocument,
  ModelSettingsResponse,
  ModelSettingsTestResponse,
  ModelSettingsTestTarget,
  PracticeRecord,
  Question,
  RagChatResponse,
  RetrievedChunk,
  StreamHandlers
} from "./types";

const SETTINGS_KEY = "rag-study-assistant:settings";

function defaultApiBaseUrl() {
  const configured = import.meta.env.VITE_API_BASE_URL?.trim();
  if (configured) {
    return configured;
  }

  if (typeof window !== "undefined" && window.location.hostname) {
    const protocol = window.location.protocol === "https:" ? "https:" : "http:";
    return `${protocol}//${window.location.hostname}:8080`;
  }

  return "http://localhost:8080";
}

const defaultSettings: AppSettings = {
  apiBaseUrl: defaultApiBaseUrl(),
  llmProvider: "deepseek",
  llmBaseUrl: "https://api.deepseek.com",
  llmModel: "deepseek-v4-pro",
  llmApiKey: "",
  llmApiKeySet: false,
  clearLlmApiKey: false,
  embeddingProvider: "openai-compatible",
  embeddingBaseUrl: "https://api.siliconflow.com/v1",
  embeddingModel: "BAAI/bge-m3",
  embeddingApiKey: "",
  embeddingApiKeySet: false,
  clearEmbeddingApiKey: false
};

export function loadSettings(): AppSettings {
  try {
    const raw = window.localStorage.getItem(SETTINGS_KEY);
    if (!raw) {
      return defaultSettings;
    }
    const settings = { ...defaultSettings, ...JSON.parse(raw) };
    if (settings.apiBaseUrl === "http://localhost:8080" && defaultSettings.apiBaseUrl !== "http://localhost:8080") {
      settings.apiBaseUrl = defaultSettings.apiBaseUrl;
    }
    return settings;
  } catch {
    return defaultSettings;
  }
}

export function saveSettings(settings: AppSettings) {
  const safeSettings: AppSettings = {
    ...settings,
    llmApiKey: "",
    clearLlmApiKey: false,
    embeddingApiKey: "",
    clearEmbeddingApiKey: false
  };
  window.localStorage.setItem(SETTINGS_KEY, JSON.stringify(safeSettings));
}

function apiBaseUrl() {
  return loadSettings().apiBaseUrl.replace(/\/+$/, "");
}

function query(params: Record<string, string | number | undefined | null>) {
  const search = new URLSearchParams();
  Object.entries(params).forEach(([key, value]) => {
    if (value !== undefined && value !== null && value !== "") {
      search.set(key, String(value));
    }
  });
  const text = search.toString();
  return text ? `?${text}` : "";
}

async function parseError(response: Response) {
  const text = await response.text();
  if (!text) {
    return `${response.status} ${response.statusText}`;
  }
  try {
    const body = JSON.parse(text) as Partial<ApiResult<unknown>>;
    return body.message || text;
  } catch {
    return text;
  }
}

async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const headers = new Headers(init.headers);
  if (!(init.body instanceof FormData) && !headers.has("Content-Type")) {
    headers.set("Content-Type", "application/json");
  }

  const response = await fetch(`${apiBaseUrl()}${path}`, {
    ...init,
    headers
  });

  if (!response.ok) {
    throw new Error(await parseError(response));
  }

  const text = await response.text();
  if (!text) {
    return undefined as T;
  }

  const body = JSON.parse(text) as ApiResult<T>;
  if (typeof body.code === "number" && body.code !== 200) {
    throw new Error(body.message || "请求失败");
  }
  return body.data;
}

export const api = {
  getModelSettings: () => request<ModelSettingsResponse>("/api/agent/model-settings"),
  updateModelSettings: (settings: AppSettings) =>
    request<ModelSettingsResponse>("/api/agent/model-settings", {
      method: "PUT",
      body: JSON.stringify({
        llmProvider: settings.llmProvider,
        llmBaseUrl: settings.llmBaseUrl,
        llmModel: settings.llmModel,
        llmApiKey: settings.llmApiKey || null,
        clearLlmApiKey: settings.clearLlmApiKey,
        embeddingProvider: settings.embeddingProvider,
        embeddingBaseUrl: settings.embeddingBaseUrl,
        embeddingModel: settings.embeddingModel,
        embeddingApiKey: settings.embeddingApiKey || null,
        clearEmbeddingApiKey: settings.clearEmbeddingApiKey
      })
    }),
  testModelSettings: (target: ModelSettingsTestTarget, settings: AppSettings) =>
    request<ModelSettingsTestResponse>("/api/agent/model-settings/test", {
      method: "POST",
      body: JSON.stringify({
        target,
        llmProvider: settings.llmProvider,
        llmBaseUrl: settings.llmBaseUrl,
        llmModel: settings.llmModel,
        llmApiKey: settings.llmApiKey || null,
        clearLlmApiKey: settings.clearLlmApiKey,
        embeddingProvider: settings.embeddingProvider,
        embeddingBaseUrl: settings.embeddingBaseUrl,
        embeddingModel: settings.embeddingModel,
        embeddingApiKey: settings.embeddingApiKey || null,
        clearEmbeddingApiKey: settings.clearEmbeddingApiKey
      })
    }),

  listCourses: (name?: string) => request<Course[]>(`/api/courses${query({ name })}`),
  createCourse: (payload: Partial<Course>) =>
    request<Course>("/api/courses", { method: "POST", body: JSON.stringify(payload) }),
  updateCourse: (id: number, payload: Partial<Course>) =>
    request<Course>(`/api/courses/${id}`, { method: "PUT", body: JSON.stringify(payload) }),
  deleteCourse: (id: number) => request<void>(`/api/courses/${id}`, { method: "DELETE" }),

  listDocuments: (courseId: number) => request<CourseDocument[]>(`/api/documents${query({ courseId })}`),
  documentFileUrl: (documentId: number) => `${apiBaseUrl()}/api/documents/${documentId}/file`,
  uploadDocument: (courseId: number, file: File) => {
    const form = new FormData();
    form.set("courseId", String(courseId));
    form.set("file", file);
    return request<CourseDocument>("/api/documents/upload", { method: "POST", body: form });
  },
  ingestDocument: (documentId: number) =>
    request<number>(`/api/agent/documents/${documentId}/ingest`, { method: "POST" }),
  deleteDocument: (documentId: number) => request<void>(`/api/documents/${documentId}`, { method: "DELETE" }),

  chat: (payload: { courseId: number; sessionId?: number | null; question: string }) =>
    request<RagChatResponse>("/api/agent/chat", { method: "POST", body: JSON.stringify(payload) }),
  listSessions: (courseId: number) => request<ChatSession[]>(`/api/agent/chat/sessions${query({ courseId })}`),
  listMessages: (sessionId: number) =>
    request<ChatMessage[]>(`/api/agent/chat/sessions/${sessionId}/messages`),
  deleteSession: (sessionId: number) =>
    request<void>(`/api/agent/chat/sessions/${sessionId}`, { method: "DELETE" }),

  generateQuestions: (courseId: number, requirement: string) =>
    request<Question[]>("/api/agent/questions/generate", {
      method: "POST",
      body: JSON.stringify({ courseId, requirement })
    }),
  listQuestions: (courseId: number, type?: string, difficulty?: string) =>
    request<Question[]>(`/api/questions${query({ courseId, type, difficulty })}`),
  saveQuestion: (payload: Partial<Question>) =>
    request<Question>("/api/questions", { method: "POST", body: JSON.stringify(payload) }),

  submitAnswer: (courseId: number, questionId: number, userAnswer: string) =>
    request<PracticeRecord>("/api/practice/submit", {
      method: "POST",
      body: JSON.stringify({ courseId, questionId, userAnswer })
    }),
  listPracticeRecords: (courseId: number) =>
    request<PracticeRecord[]>(`/api/courses/${courseId}/practice/records`),
  listWrongQuestions: (courseId: number) =>
    request<PracticeRecord[]>(`/api/courses/${courseId}/practice/wrong-questions`)
};

export async function streamChat(
  payload: { courseId: number; sessionId?: number | null; question: string },
  handlers: StreamHandlers,
  signal?: AbortSignal
) {
  const response = await fetch(`${apiBaseUrl()}/api/agent/chat/stream`, {
    method: "POST",
    headers: {
      Accept: "text/event-stream",
      "Content-Type": "application/json"
    },
    body: JSON.stringify(payload),
    signal
  });

  if (!response.ok) {
    throw new Error(await parseError(response));
  }
  if (!response.body) {
    throw new Error("浏览器未返回流式响应体");
  }

  const reader = response.body.getReader();
  const decoder = new TextDecoder("utf-8");
  let buffer = "";

  while (true) {
    const { value, done } = await reader.read();
    buffer += decoder.decode(value, { stream: !done }).replace(/\r\n/g, "\n");

    let separator = buffer.indexOf("\n\n");
    while (separator >= 0) {
      const block = buffer.slice(0, separator);
      buffer = buffer.slice(separator + 2);
      dispatchSseBlock(block, handlers);
      separator = buffer.indexOf("\n\n");
    }

    if (done) {
      if (buffer.trim()) {
        dispatchSseBlock(buffer, handlers);
      }
      break;
    }
  }
}

function dispatchSseBlock(block: string, handlers: StreamHandlers) {
  let eventName = "message";
  const dataLines: string[] = [];

  block.split("\n").forEach((line) => {
    if (line.startsWith("event:")) {
      eventName = line.slice("event:".length).trim();
    }
    if (line.startsWith("data:")) {
      dataLines.push(line.slice("data:".length).replace(/^ /, ""));
    }
  });

  const data = dataLines.join("\n");
  if (!data && eventName !== "done") {
    return;
  }

  if (eventName === "session") {
    const parsed = parseJson<{ sessionId: number }>(data);
    if (parsed?.sessionId) {
      handlers.onSession?.(parsed.sessionId);
    }
    return;
  }

  if (eventName === "references") {
    handlers.onReferences?.(parseJson<RetrievedChunk[]>(data) || []);
    return;
  }

  if (eventName === "delta") {
    handlers.onDelta?.(data);
    return;
  }

  if (eventName === "error") {
    const parsed = parseJson<{ message: string }>(data);
    handlers.onError?.(parsed?.message || data || "流式响应失败");
    return;
  }

  if (eventName === "done") {
    handlers.onDone?.();
  }
}

function parseJson<T>(text: string): T | null {
  try {
    return JSON.parse(text) as T;
  } catch {
    return null;
  }
}
