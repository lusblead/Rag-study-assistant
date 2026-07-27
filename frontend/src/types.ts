export type RouteKey = "home" | "knowledge" | "chat" | "practice" | "settings" | "help";

export type Paper = {
  id: number; courseId: number; subject: string; title: string; paperType?: string;
  gradeLevel?: string; difficulty?: string; durationMinutes?: number; totalScore?: number;
  templateCode?: string; requirements?: string; createdAt?: string; updatedAt?: string;
};
export type PaperQuestion = {
  id: number; paperId: number; questionId: number; sectionKey: string; sectionTitle: string;
  sectionInstructions?: string; questionOrder: number; score?: number; question: Question;
};
export type PaperDetail = {
  paper: Paper;
  sections: Array<{ sectionKey: string; title: string; instructions?: string; score: number; questions: PaperQuestion[] }>;
  warnings: string[];
};
export type PaperGeneratePayload = {
  courseId: number; documentIds: number[]; subject: "chinese"; title?: string;
  paperType?: string; gradeLevel?: string; difficulty?: string; durationMinutes?: number;
  totalScore?: number; templateCode?: string; requirements?: string;
};

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

export type SubjectType = "general" | "chinese";
export type QuestionType = "single_choice" | "multi_choice" | "true_false" | "short_answer"
  | "fill_blank" | "composition" | "classical_chinese_reading" | "poetry_appreciation"
  | "modern_reading" | "translation" | "sentence_break" | "explanation" | "language_basic" | string;
export type Difficulty = "easy" | "medium" | "hard" | string;

export type Question = {
  id: number;
  courseId: number;
  sourceChunkId?: number | null;
  sourceDocumentId?: number | null;
  batchId?: number | null;
  type: QuestionType;
  stem: string;
  options?: string;
  answer: string;
  explanation?: string;
  difficulty?: Difficulty;
  knowledgePoint?: string;
  chapterTags?: string;
  subject?: SubjectType;
  questionData?: string;
  answerSchema?: string;
  gradingStrategy?: "rule" | "manual" | "ai" | "mixed";
  createdAt?: string;
};

export type PracticeRecord = {
  id: number;
  courseId: number;
  questionId: number;
  userAnswer: string;
  isCorrect?: boolean | null;
  gradingMode?: "rule" | "ai" | "manual" | "mixed" | string;
  gradingFeedback?: string;
  answerPayload?: string;
  score?: number | null;
  maxScore?: number | null;
  gradingStatus?: "graded" | "pending" | "manual_required" | "ai_graded" | string;
  subResults?: Array<{
    subQuestionKey: string;
    correct?: boolean | null;
    referenceAnswer?: string;
    explanation?: string;
    score?: number | null;
    maxScore?: number | null;
    gradingStatus?: string;
  }>;
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
  rerankProvider: "none" | "local" | "siliconflow" | string;
  rerankBaseUrl: string;
  rerankModel: string;
  rerankApiKey: string;
  rerankApiKeySet: boolean;
  clearRerankApiKey: boolean;
  rerankFailOpen: boolean;
};

export type QuestionBatch = {
  id: number;
  courseId: number;
  title: string;
  mode: "practice" | "exam" | string;
  requirement?: string;
  questionCount: number;
  questionType?: string;
  difficulty?: string;
  referenceRealQuestions?: boolean;
  styleSummary?: string;
  createdAt?: string;
};

export type QuestionBatchDetail = {
  batch: QuestionBatch;
  questions: Question[];
  documentIds: number[];
  chunkIds: number[];
};

export type QuestionGenerationPayload = {
  courseId: number;
  count: number;
  type: string;
  difficulty: string;
  requirement: string;
  mode: "practice" | "exam";
  title?: string;
  documentIds: number[];
  referenceRealQuestions: boolean;
  styleDocumentIds: number[];
  subject?: SubjectType;
  questionTypes?: string[];
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

export type RerankSettingsResponse = {
  provider: "none" | "local" | "siliconflow" | string;
  baseUrl: string;
  model: string;
  apiKeySet: boolean;
  failOpen: boolean;
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
