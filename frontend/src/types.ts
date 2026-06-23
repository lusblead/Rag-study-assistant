export type RouteKey = "home" | "knowledge" | "chat" | "practice" | "settings" | "help";

export type ApiResult<T> = {
  code: number;
  message: string;
  data: T;
};

export type Course = {
  id: number;
  name: string;
  description?: string;
  term?: string;
  createdAt?: string;
  updatedAt?: string;
};

export type CourseDocument = {
  id: number;
  courseId: number;
  filename: string;
  fileType?: string;
  filePath?: string;
  parseStatus?: "UPLOADED" | "PARSING" | "PARSED" | "FAILED" | string;
  chunkCount?: number;
  createdAt?: string;
  updatedAt?: string;
};

export type RetrievedChunk = {
  chunkId: number;
  documentId: number;
  title?: string;
  content: string;
  score?: number;
};

export type RagChatResponse = {
  sessionId: number;
  answer: string;
  references?: RetrievedChunk[];
};

export type ChatSession = {
  id: number;
  courseId: number;
  title?: string;
  createdAt?: string;
  updatedAt?: string;
};

export type ChatMessage = {
  id: number;
  sessionId: number;
  role: "user" | "assistant" | string;
  content: string;
  createdAt?: string;
};

export type QuestionType = "single_choice" | "multi_choice" | "true_false" | "short_answer" | string;
export type Difficulty = "easy" | "medium" | "hard" | string;

export type Question = {
  id: number;
  courseId: number;
  sourceChunkId?: number | null;
  type: QuestionType;
  stem: string;
  options?: string;
  answer: string;
  explanation?: string;
  difficulty?: Difficulty;
  knowledgePoint?: string;
  createdAt?: string;
};

export type PracticeRecord = {
  id: number;
  courseId: number;
  questionId: number;
  userAnswer: string;
  isCorrect?: boolean;
  gradingMode?: "rule" | "ai" | string;
  gradingFeedback?: string;
  createdAt?: string;
};

export type AppSettings = {
  apiBaseUrl: string;
  llmProvider: string;
  llmBaseUrl: string;
  llmModel: string;
  llmApiKey: string;
  llmApiKeySet: boolean;
  clearLlmApiKey: boolean;
  embeddingProvider: string;
  embeddingBaseUrl: string;
  embeddingModel: string;
  embeddingApiKey: string;
  embeddingApiKeySet: boolean;
  clearEmbeddingApiKey: boolean;
};

export type ModelSettingsResponse = {
  llmProvider: string;
  llmBaseUrl: string;
  llmModel: string;
  llmApiKeySet: boolean;
  embeddingProvider: string;
  embeddingBaseUrl: string;
  embeddingModel: string;
  embeddingApiKeySet: boolean;
};

export type ModelSettingsTestTarget = "llm" | "embedding";

export type ModelSettingsTestResponse = {
  target: ModelSettingsTestTarget;
  success: boolean;
  message: string;
  statusCode?: number | null;
  latencyMs: number;
};

export type StreamHandlers = {
  onSession?: (sessionId: number) => void;
  onReferences?: (references: RetrievedChunk[]) => void;
  onDelta?: (delta: string) => void;
  onDone?: () => void;
  onError?: (message: string) => void;
};
