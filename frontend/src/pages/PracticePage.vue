<template>
  <section class="page-grid practice-grid">
    <aside class="side-panel practice-sidebar">
      <Panel title="AI 出题">
        <div class="mode-switch" role="group" aria-label="出题模式">
          <button :class="{ active: generationMode === 'practice' }" type="button" @click="setMode('practice')">
            练习题
          </button>
          <button :class="{ active: generationMode === 'exam' }" type="button" @click="setMode('exam')">
            生成套卷
          </button>
        </div>

        <label>
          批次名称（可选）
          <input v-model="batchTitle" :disabled="!course || busy" :placeholder="generationMode === 'exam' ? '例如：期末模拟卷 A' : '例如：第 3 章专项练习'" />
        </label>

        <div class="generation-grid">
          <label v-if="generationMode === 'practice'">
            学科
            <select v-model="generateSubject" :disabled="!course || busy">
              <option value="general">通用</option>
              <option value="chinese">语文</option>
            </select>
          </label>
          <label v-if="generationMode === 'practice' && generateSubject === 'general'">
            数量
            <input v-model.number="questionCount" :disabled="!course || busy" max="20" min="1" type="number" />
          </label>
          <label v-if="generationMode === 'practice' && generateSubject === 'general'">
            题型
            <select v-model="generateType" :disabled="!course || busy">
              <option value="mixed">混合题型</option>
              <option value="single_choice">单选题</option>
              <option value="multi_choice">多选题</option>
              <option value="true_false">判断题</option>
              <option value="short_answer">简答题</option>
            </select>
          </label>
          <label>
            难度
            <select v-model="generateDifficulty" :disabled="!course || busy">
              <option value="mixed">混合难度</option>
              <option value="easy">简单</option>
              <option value="medium">中等</option>
              <option value="hard">困难</option>
            </select>
          </label>
        </div>

        <fieldset v-if="generationMode === 'practice' && generateSubject === 'chinese'" class="document-scope">
          <legend>语文题型（可多选）</legend>
          <div class="scope-list">
            <label v-for="option in chineseTypeOptions" :key="option.value" class="scope-option">
              <input v-model="generateTypes" :value="option.value" type="checkbox" />
              <span>{{ option.label }}</span>
            </label>
          </div>
        </fieldset>

        <fieldset v-if="generationMode === 'exam'" class="document-scope paper-template-summary">
          <legend>语文套卷结构</legend>
          <p>套卷题型由语文模板统一规划，不使用通用题型筛选。</p>
          <div class="scope-list">
            <div v-for="section in paperTemplateSections" :key="section" class="scope-option fixed-option">
              <span>{{ section }}</span>
            </div>
          </div>
        </fieldset>

        <div class="scope-field">
          <span class="field-label">出题范围</span>
          <button
            class="scope-trigger"
            :disabled="!course || busy || !parsedDocuments.length"
            type="button"
            @click="scopeDialogOpen = true"
          >
            <span>
              <strong>{{ scopeSummary }}</strong>
              <small>点击选择一个或多个已解析文件</small>
            </span>
            <span aria-hidden="true">›</span>
          </button>
        </div>

        <label v-if="generationMode === 'practice'" class="inline-check reference-toggle">
          <input v-model="referenceRealQuestions" :disabled="busy" type="checkbox" />
          参考真实题目的命题习惯
        </label>

        <fieldset v-if="generationMode === 'practice' && referenceRealQuestions" class="document-scope style-scope">
          <legend>真实题目参考文件（可选）</legend>
          <p>选择包含真题的文件后，AI 会总结成可复用的命题风格画像；留空时沿用最近一次画像。</p>
          <div class="scope-list">
            <label v-for="document in parsedDocuments" :key="`style-${document.id}`" class="scope-option">
              <input v-model="styleDocumentIds" :disabled="busy" :value="document.id" type="checkbox" />
              <span>{{ document.filename }}</span>
            </label>
          </div>
        </fieldset>

        <label>
          出题要求
          <textarea v-model="requirement" :disabled="!course || busy" rows="4" />
        </label>
        <button class="block" :disabled="!course || busy || !parsedDocuments.length" type="button" @click="generate">
          {{ busy ? "正在生成..." : generationMode === "exam" ? "生成并保存套卷" : "生成并保存本批题目" }}
        </button>
      </Panel>

      <Panel title="练习概览">
        <div class="metric-grid">
          <div class="metric"><strong>{{ questions.length }}</strong><span>题目</span></div>
          <div class="metric"><strong>{{ batches.length }}</strong><span>批次</span></div>
          <div class="metric"><strong>{{ wrongRecords.length }}</strong><span>错题</span></div>
        </div>
      </Panel>
    </aside>

    <section class="content-panel practice-content">
      <div class="section-heading tight">
        <div>
          <p class="eyebrow">题库练习</p>
          <h1>{{ course ? course.name : "请选择课程" }}</h1>
        </div>
        <div class="filters">
          <select v-model="subjectFilter" aria-label="按学科筛选">
            <option value="">全部学科</option>
            <option value="general">通用</option>
            <option value="chinese">语文</option>
          </select>
          <select v-model="chapterFilter" aria-label="按章节筛选">
            <option value="">全部章节</option>
            <option v-for="chapter in availableChapters" :key="chapter" :value="chapter">{{ chapter }}</option>
          </select>
          <select v-model="fileFilter" aria-label="按来源文件筛选">
            <option value="">全部文件</option>
            <option v-for="document in parsedDocuments" :key="document.id" :value="String(document.id)">
              {{ document.filename }}
            </option>
          </select>
          <select v-model="typeFilter">
            <option value="">全部题型</option>
            <option value="single_choice">单选题</option>
            <option value="multi_choice">多选题</option>
            <option value="true_false">判断题</option>
            <option value="short_answer">简答题</option>
            <option v-for="option in chineseTypeOptions" :key="`filter-${option.value}`" :value="option.value">{{ option.label }}</option>
          </select>
          <select v-model="difficultyFilter">
            <option value="">全部难度</option>
            <option value="easy">简单</option>
            <option value="medium">中等</option>
            <option value="hard">困难</option>
          </select>
          <button class="ghost" :disabled="!course || !filteredQuestions.length" type="button" @click="exportQuestions(filteredQuestions, `${course?.name || '课程'} - 全部题目`) ">
            导出全部
          </button>
          <button class="ghost icon-only" :disabled="!course || busy" title="刷新题库" type="button" aria-label="刷新题库" @click="loadPracticeData">
            ↻
          </button>
        </div>
      </div>

      <EmptyState v-if="!course" title="请选择课程后查看题库" />
      <section v-else-if="papers.length" class="question-batch paper-shelf">
        <header class="batch-header"><div><p class="eyebrow">结构化套卷</p><h2>已生成试卷</h2></div></header>
        <div class="scope-list">
          <button v-for="paper in papers" :key="paper.id" class="scope-trigger" type="button" @click="openPaper(paper.id)">
            <span><strong>{{ paper.title }}</strong><small>{{ paper.totalScore || 0 }} 分 · {{ paper.durationMinutes || 0 }} 分钟 · {{ paper.createdAt ? formatDate(paper.createdAt) : '' }}</small></span><span>›</span>
          </button>
        </div>
      </section>
      <EmptyState v-else-if="!displayGroups.length" title="暂无题目" text="可从左侧选择范围并生成练习题或套卷。" />

      <section v-if="selectedPaper" class="question-batch paper-preview">
        <header class="batch-header"><div><p class="eyebrow">试卷预览</p><h2>{{ selectedPaper.paper.title }}</h2><p>满分 {{ selectedPaper.paper.totalScore }} 分 · 建议用时 {{ selectedPaper.paper.durationMinutes }} 分钟</p></div>
          <div class="batch-actions"><button class="ghost" type="button" @click="printPaper(false)">打印试题</button><button class="ghost" type="button" @click="printPaper(true)">打印答案</button><button class="danger" type="button" @click="deletePaper(selectedPaper.paper.id)">删除试卷</button></div></header>
        <div v-if="selectedPaper.warnings.length" class="banner"><strong>部分生成提示：</strong>{{ selectedPaper.warnings.join('；') }}</div>
        <section v-for="section in selectedPaper.sections" :key="section.sectionKey" class="paper-section">
          <h2>{{ section.title }}（{{ section.score }} 分）</h2><p>{{ section.instructions }}</p>
          <article v-for="item in section.questions" :key="item.id" class="question-card">
            <div class="question-head">
              <span>#{{ item.questionOrder }}</span><span class="badge">{{ item.score }} 分</span><span class="badge">{{ typeText(item.question.type) }}</span>
            </div>
            <h2>{{ item.question.stem }}</h2>
            <p v-if="questionMaterial(item.question)" class="preserve-lines">{{ questionMaterial(item.question)?.text }}</p>
            <div v-if="questionOptions(item.question).length" class="option-list paper-option-list">
              <div v-for="option in questionOptions(item.question)" :key="option" class="option">{{ option }}</div>
            </div>
            <div v-if="isComposite(item.question)" class="sub-question-list">
              <section v-for="sub in subQuestions(item.question)" :key="sub.key" class="sub-question">
                <h3>{{ sub.stem }}</h3>
                <div v-if="subQuestionOptions(sub).length" class="option-list paper-option-list">
                  <div v-for="option in subQuestionOptions(sub)" :key="option" class="option">{{ option }}</div>
                </div>
                <div class="paper-answer">
                  <p><strong>参考答案：</strong>{{ sub.answer || '（未提供）' }}</p>
                  <p v-if="sub.explanation"><strong>解析：</strong>{{ sub.explanation }}</p>
                </div>
              </section>
            </div>
            <div class="paper-answer">
              <p><strong>答案：</strong>{{ item.question.answer || '（未提供）' }}</p>
              <p v-if="item.question.explanation"><strong>解析：</strong>{{ item.question.explanation }}</p>
            </div>
          </article>
        </section>
      </section>

      <section v-if="pendingManualRecords.length" class="question-batch">
        <header class="batch-header"><div><p class="eyebrow">人工批改</p><h2>待批改主观题（{{ pendingManualRecords.length }}）</h2></div></header>
        <article v-for="record in pendingManualRecords" :key="record.id" class="question-card">
          <h2>{{ questionById(record.questionId)?.stem || `题目 ${record.questionId}` }}</h2>
          <p><strong>作答：</strong>{{ record.userAnswer || record.answerPayload }}</p>
          <button type="button" @click="manualGrade(record)">录入分数与评语</button>
        </article>
      </section>

      <div v-else class="batch-picker">
        <label for="batch-select">选择批次</label>
        <select id="batch-select" v-model="selectedGroupId">
          <option v-for="group in displayGroups" :key="group.id" :value="group.id">
            {{ group.title }}（{{ group.questions.length }} 题）
          </option>
        </select>
      </div>

      <div class="batch-list">
        <section v-for="group in selectedDisplayGroups" :key="group.id" class="question-batch">
          <header class="batch-header">
            <div>
              <div class="batch-title-row">
                <h2>{{ group.title }}</h2>
                <span class="badge">{{ group.mode === "exam" ? "套卷" : group.legacy ? "历史题目" : "练习批次" }}</span>
                <span class="badge">{{ group.questions.length }} 题</span>
                <span v-if="group.referenceRealQuestions" class="badge">真实题风格</span>
              </div>
              <p>
                {{ group.createdAt ? formatDate(group.createdAt) : "未记录生成时间" }}
                <template v-if="group.documentIds.length"> · 范围：{{ documentNames(group.documentIds) }}</template>
              </p>
            </div>
            <div class="batch-actions">
              <button class="ghost" type="button" @click="exportQuestions(group.questions, group.title, group.mode !== 'exam')">导出</button>
              <button v-if="!group.legacy" class="danger" :disabled="busy" type="button" @click="deleteBatch(group.batchId!)">删除批次</button>
            </div>
          </header>

          <details v-if="group.styleSummary" class="style-summary">
            <summary>查看本批命题风格画像</summary>
            <p>{{ group.styleSummary }}</p>
          </details>

          <div class="question-list">
            <article v-for="(item, index) in group.questions" :key="item.id" class="question-card">
              <div class="question-head">
                <span>#{{ index + 1 }}</span>
                <span class="badge">{{ typeText(item.type) }}</span>
                <span class="badge">{{ difficultyText(item.difficulty) }}</span>
                <span v-if="item.knowledgePoint" class="badge">{{ item.knowledgePoint }}</span>
                <span v-for="chapter in parseChapterTags(item.chapterTags)" :key="chapter" class="badge chapter-badge">{{ chapter }}</span>
                <span v-if="item.sourceDocumentId" class="badge">{{ documentName(item.sourceDocumentId) }}</span>
                <span v-if="item.sourceChunkId" class="badge">片段 {{ item.sourceChunkId }}</span>
                <button class="ghost question-delete" :disabled="busy" type="button" @click="editQuestion(item)">编辑</button>
                <button class="danger question-delete" :disabled="busy" type="button" @click="deleteQuestion(item)">删除</button>
              </div>

              <h2>{{ item.stem }}</h2>

              <div v-if="questionMaterial(item)" class="question-material">
                <h3>{{ parsedQuestionData(item)?.title || '阅读材料' }}</h3>
                <p class="preserve-lines">{{ questionMaterial(item)?.text }}</p>
                <p v-if="questionMaterial(item)?.author || questionMaterial(item)?.source">
                  {{ questionMaterial(item)?.author || '' }} {{ questionMaterial(item)?.source || '' }}
                </p>
                <ul v-if="questionMaterial(item)?.annotations?.length">
                  <li v-for="note in questionMaterial(item)?.annotations" :key="`${note.word}-${note.explanation}`">
                    {{ note.word }}：{{ note.explanation }}
                  </li>
                </ul>
              </div>

              <div v-if="isComposite(item) && group.mode !== 'exam'" class="sub-question-list">
                <section v-for="sub in subQuestions(item)" :key="sub.key" class="sub-question">
                  <h3>{{ sub.stem }}</h3>
                  <div v-if="subQuestionOptions(sub).length" class="option-list">
                    <button v-for="option in subQuestionOptions(sub)" :key="option" type="button"
                      :class="['option', { active: subAnswerSelected(item.id, sub, option) }]"
                      @click="toggleSubOption(item.id, sub, option)">{{ option }}</button>
                  </div>
                  <textarea v-else v-model="compositeAnswers[item.id][sub.key]" rows="3" placeholder="填写本小题答案" />
                </section>
                <button :disabled="busy" type="button" @click="submitComposite(item)">提交整组答案</button>
              </div>
              <div v-if="isComposite(item) && group.mode === 'exam'" class="sub-question-list">
                <section v-for="sub in subQuestions(item)" :key="sub.key" class="sub-question">
                  <h3>{{ sub.stem }}</h3>
                  <div v-if="subQuestionOptions(sub).length" class="option-list paper-option-list">
                    <div v-for="option in subQuestionOptions(sub)" :key="option" class="option">{{ option }}</div>
                  </div>
                  <div class="paper-answer">
                    <p><strong>参考答案：</strong>{{ sub.answer || '（未提供）' }}</p>
                    <p v-if="sub.explanation"><strong>解析：</strong>{{ sub.explanation }}</p>
                  </div>
                </section>
              </div>

              <div v-else-if="questionOptions(item).length" class="option-list">
                <button
                  v-for="option in questionOptions(item)"
                  :key="option"
                  :class="['option', { active: isOptionSelected(item, option) }]"
                  type="button"
                  @click="toggleOption(item, option)"
                >
                  {{ option }}
                </button>
              </div>

              <div v-if="group.mode === 'exam' && !isComposite(item)" class="paper-answer">
                <p><strong>答案：</strong>{{ item.answer || '（未提供）' }}</p>
                <p v-if="item.explanation"><strong>解析：</strong>{{ item.explanation }}</p>
              </div>
              <template v-else-if="!isComposite(item)">
                <div class="answer-row">
                  <textarea v-if="item.type === 'composition'" v-model="answers[item.id]" rows="8" placeholder="输入作文内容" />
                  <input v-else v-model="answers[item.id]" placeholder="填写答案，例如 A、AB、正确，或简答文本" />
                  <button :disabled="busy" type="button" @click="submit(item)">提交</button>
                  <small v-if="item.type === 'composition'">当前 {{ (answers[item.id] || '').replace(/\s/g, '').length }} 字</small>
                </div>
              </template>

              <div v-if="resultByQuestion[item.id]" :class="['result', resultByQuestion[item.id].isCorrect === true ? 'correct' : resultByQuestion[item.id].isCorrect === false ? 'wrong' : 'pending']">
                <strong>{{ resultText(resultByQuestion[item.id]) }}</strong>
                <span>标准答案：{{ item.answer }}</span>
                <p v-if="resultByQuestion[item.id].gradingFeedback">{{ resultByQuestion[item.id].gradingFeedback }}</p>
                <p v-if="item.explanation">{{ item.explanation }}</p>
                <ul v-if="resultByQuestion[item.id].subResults?.length">
                  <li v-for="sub in resultByQuestion[item.id].subResults" :key="sub.subQuestionKey">
                    {{ sub.subQuestionKey }}：{{ sub.correct == null ? '待评价' : sub.correct ? '正确' : '不正确' }}
                    <span v-if="sub.referenceAnswer">；参考答案：{{ sub.referenceAnswer }}</span>
                  </li>
                </ul>
              </div>
            </article>
          </div>
        </section>
      </div>
    </section>

    <div v-if="scopeDialogOpen" class="scope-dialog-backdrop" @click.self="scopeDialogOpen = false">
      <section aria-labelledby="scope-dialog-title" aria-modal="true" class="scope-dialog" role="dialog">
        <header>
          <div>
            <p class="eyebrow">AI 出题</p>
            <h2 id="scope-dialog-title">选择出题范围</h2>
          </div>
          <button aria-label="关闭" class="ghost icon-only" type="button" @click="scopeDialogOpen = false">×</button>
        </header>
        <p>不选择具体文件时，将从当前课程全部已解析资料中随机出题。</p>
        <button
          :class="['scope-all-option', { active: !selectedDocumentIds.length }]"
          type="button"
          @click="selectedDocumentIds = []"
        >
          <strong>全部已解析文件</strong>
          <span>让系统在整个课程范围内随机选择片段</span>
        </button>
        <div class="scope-list dialog-scope-list">
          <label v-for="document in parsedDocuments" :key="document.id" class="scope-option">
            <input v-model="selectedDocumentIds" :disabled="busy" :value="document.id" type="checkbox" />
            <span>{{ document.filename }}</span>
          </label>
        </div>
        <footer>
          <span>{{ scopeSummary }}</span>
          <button type="button" @click="scopeDialogOpen = false">完成</button>
        </footer>
      </section>
    </div>
  </section>
</template>

<script setup lang="ts">
import { computed, ref, watch } from "vue";
import { api } from "../api";
import EmptyState from "../components/EmptyState.vue";
import Panel from "../components/Panel.vue";
import type { Course, CourseDocument, Paper, PaperDetail, PracticeRecord, Question, QuestionBatchDetail } from "../types";

type DisplayGroup = {
  id: string;
  batchId?: number;
  title: string;
  mode: string;
  createdAt?: string;
  documentIds: number[];
  referenceRealQuestions: boolean;
  styleSummary?: string;
  questions: Question[];
  legacy?: boolean;
};

type SubQuestion = { key: string; type: string; stem: string; options?: string[]; answer?: string; explanation?: string };
type QuestionData = {
  title?: string;
  material?: { text?: string; author?: string; source?: string; annotations?: Array<{ word: string; explanation: string }> };
  requirements?: Record<string, unknown>;
  subQuestions?: SubQuestion[];
};

const props = defineProps<{ course: Course | null }>();
const emit = defineEmits<{ notify: [tone: "success" | "error" | "info", message: string] }>();

const questions = ref<Question[]>([]);
const batches = ref<QuestionBatchDetail[]>([]);
const documents = ref<CourseDocument[]>([]);
const records = ref<PracticeRecord[]>([]);
const wrongRecords = ref<PracticeRecord[]>([]);
const papers = ref<Paper[]>([]);
const selectedPaper = ref<PaperDetail | null>(null);
const typeFilter = ref("");
const subjectFilter = ref("");
const difficultyFilter = ref("");
const chapterFilter = ref("");
const fileFilter = ref("");
const questionCount = ref(5);
const generateType = ref("mixed");
const generateSubject = ref<"general" | "chinese">("general");
const generateTypes = ref<string[]>(["composition", "classical_chinese_reading", "poetry_appreciation", "fill_blank", "translation"]);
const generateDifficulty = ref("medium");
const generationMode = ref<"practice" | "exam">("practice");
const batchTitle = ref("");
const requirement = ref("覆盖所选资料的核心知识点，避免重复考查同一片段，题干、选项和解析使用中文。");
const selectedDocumentIds = ref<number[]>([]);
const referenceRealQuestions = ref(false);
const styleDocumentIds = ref<number[]>([]);
const answers = ref<Record<number, string>>({});
const resultByQuestion = ref<Record<number, PracticeRecord>>({});
const compositeAnswers = ref<Record<number, Record<string, string>>>({});
const busy = ref(false);
const scopeDialogOpen = ref(false);
const selectedGroupId = ref("");
const chineseTypeOptions = [
  { value: "composition", label: "作文题" },
  { value: "classical_chinese_reading", label: "文言文阅读" },
  { value: "poetry_appreciation", label: "古诗词鉴赏" },
  { value: "modern_reading", label: "现代文阅读" },
  { value: "fill_blank", label: "名句默写" },
  { value: "translation", label: "翻译题" },
  { value: "sentence_break", label: "断句题" },
  { value: "explanation", label: "字词解释" },
  { value: "language_basic", label: "语言基础" }
];
const paperTemplateSections = ["语言文字运用", "现代文阅读", "文言文阅读", "古诗词鉴赏", "名篇名句默写", "写作"];

const parsedDocuments = computed(() => documents.value.filter((item) => item.parseStatus === "PARSED"));
const availableChapters = computed(() => Array.from(new Set(
  questions.value.flatMap((item) => parseChapterTags(item.chapterTags))
)).sort((left, right) => left.localeCompare(right, "zh-CN", { numeric: true })));
const scopeSummary = computed(() => {
  if (!parsedDocuments.value.length) return "暂无已解析文件";
  if (!selectedDocumentIds.value.length) return `全部文件（${parsedDocuments.value.length}）`;
  return `已选择 ${selectedDocumentIds.value.length} 个文件`;
});
const filteredQuestions = computed(() => questions.value.filter(matchesFilters));
const pendingManualRecords = computed(() => records.value.filter(item => item.gradingStatus === "manual_required"));
const displayGroups = computed<DisplayGroup[]>(() => {
  const groups: DisplayGroup[] = batches.value
    .map((detail) => ({
      id: `batch-${detail.batch.id}`,
      batchId: detail.batch.id,
      title: detail.batch.title,
      mode: detail.batch.mode,
      createdAt: detail.batch.createdAt,
      documentIds: detail.documentIds || [],
      referenceRealQuestions: Boolean(detail.batch.referenceRealQuestions),
      styleSummary: detail.batch.styleSummary,
      questions: detail.questions.filter(matchesFilters)
    }))
    .filter((group) => group.questions.length);
  const legacy = filteredQuestions.value.filter((item) => !item.batchId);
  if (legacy.length) {
    groups.push({
      id: "legacy",
      title: "历史未分批题目",
      mode: "practice",
      documentIds: [],
      referenceRealQuestions: false,
      questions: legacy,
      legacy: true
    });
  }
  return groups;
});
const selectedDisplayGroups = computed(() => {
  const selected = displayGroups.value.find((group) => group.id === selectedGroupId.value);
  return selected ? [selected] : displayGroups.value.slice(0, 1);
});

watch(displayGroups, (groups) => {
  if (!groups.some((group) => group.id === selectedGroupId.value)) {
    selectedGroupId.value = groups[0]?.id || "";
  }
}, { immediate: true });

watch(() => props.course?.id, () => {
  selectedDocumentIds.value = [];
  styleDocumentIds.value = [];
  selectedGroupId.value = "";
  chapterFilter.value = "";
  fileFilter.value = "";
  scopeDialogOpen.value = false;
  void loadPracticeData();
}, { immediate: true });

function setMode(mode: "practice" | "exam") {
  generationMode.value = mode;
  if (mode === "exam") {
    generateSubject.value = "chinese";
    generateType.value = "mixed";
    generateDifficulty.value = "mixed";
    questionCount.value = Math.max(questionCount.value, 10);
  } else if (questionCount.value > 20) {
    questionCount.value = 5;
  }
}

async function loadPracticeData() {
  if (!props.course) {
    questions.value = []; batches.value = []; papers.value = []; selectedPaper.value = null; documents.value = []; records.value = []; wrongRecords.value = [];
    return;
  }
  try {
    const [nextQuestions, nextBatches, nextDocuments, nextRecords, nextWrong, nextPapers] = await Promise.all([
      api.listQuestions(props.course.id),
      api.listQuestionBatches(props.course.id),
      api.listDocuments(props.course.id),
      api.listPracticeRecords(props.course.id),
      api.listWrongQuestions(props.course.id),
      api.listPapers(props.course.id)
    ]);
    questions.value = nextQuestions;
    batches.value = nextBatches;
    documents.value = nextDocuments;
    records.value = nextRecords;
    wrongRecords.value = nextWrong;
    papers.value = nextPapers;
  } catch (error) {
    emit("notify", "error", error instanceof Error ? error.message : "题库加载失败");
  }
}

async function generate() {
  if (!props.course) return;
  busy.value = true;
  try {
    if (generationMode.value === "exam") {
      const generated = await api.generatePaper({ courseId: props.course.id, documentIds: selectedDocumentIds.value,
        subject: "chinese", title: batchTitle.value.trim() || undefined, difficulty: generateDifficulty.value === "mixed" ? "medium" : generateDifficulty.value,
        durationMinutes: 120, totalScore: 100, templateCode: "chinese_high_school_standard_v1", requirements: requirement.value.trim() });
      selectedPaper.value = generated;
      emit("notify", generated.warnings.length ? "info" : "success", generated.warnings.length ? `试卷已部分生成，${generated.warnings.length} 个分区有提示` : "完整语文套卷已生成");
      batchTitle.value = ""; await loadPracticeData(); return;
    }
    const generated = await api.generateQuestionBatch({
      courseId: props.course.id,
      count: Math.max(1, Number(questionCount.value) || 1),
      type: generateType.value,
      subject: generateSubject.value,
      questionTypes: generateSubject.value === "chinese" ? generateTypes.value : undefined,
      difficulty: generateDifficulty.value,
      requirement: requirement.value.trim(),
      mode: generationMode.value,
      title: batchTitle.value.trim() || undefined,
      documentIds: selectedDocumentIds.value,
      referenceRealQuestions: referenceRealQuestions.value,
      styleDocumentIds: styleDocumentIds.value
    });
    emit("notify", "success", `已生成 ${generated.questions.length} 道题，并保存为“${generated.batch.title}”`);
    batchTitle.value = "";
    await loadPracticeData();
  } catch (error) {
    emit("notify", "error", error instanceof Error ? error.message : "生成失败");
  } finally {
    busy.value = false;
  }
}

async function openPaper(id: number) { try { selectedPaper.value = await api.getPaper(id); } catch (error) { emit("notify", "error", error instanceof Error ? error.message : "试卷加载失败"); } }
async function deletePaper(id: number) { if (!window.confirm("删除试卷结构？题库中的原题会保留。")) return; await api.deletePaper(id); selectedPaper.value = null; await loadPracticeData(); }
function printPaper(showAnswers: boolean) {
  if (!selectedPaper.value) return;
  const paper = selectedPaper.value;
  const sections = paper.sections.map(section => `<section><h2>${escapeHtml(section.title)}（${section.score}分）</h2><p>${escapeHtml(section.instructions)}</p>${section.questions.map(item => {
    const q=item.question; const data=parsedQuestionData(q); const material=data?.material?.text?`<div class="material">${escapeHtml(data.material.text).replace(/\n/g,"<br>")}</div>`:"";
    const options=questionOptions(q).map(option=>`<li>${escapeHtml(option)}</li>`).join("");
    const subs=(data?.subQuestions||[]).map((sub,index)=>{const subOptions=subQuestionOptions(sub).map(option=>`<li>${escapeHtml(option)}</li>`).join("");return `<div><b>${item.questionOrder}.${index+1} ${escapeHtml(sub.stem)}</b>${subOptions?`<ol>${subOptions}</ol>`:""}${showAnswers?`<p>参考答案：${escapeHtml(sub.answer||"")}<br>解析：${escapeHtml(sub.explanation||"")}</p>`:""}</div>`;}).join("");
    return `<article><h3>${item.questionOrder}. ${escapeHtml(q.stem)} <small>（${item.score||0}分）</small></h3>${material}${options?`<ol>${options}</ol>`:""}${subs}${showAnswers?`<p class="answer">答案：${escapeHtml(q.answer)}<br>解析：${escapeHtml(q.explanation||"")}</p>`:""}</article>`;
  }).join("")}</section>`).join("");
  const win=window.open("","_blank","width=960,height=720"); if(!win)return;
  win.document.write(`<!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><title>${escapeHtml(paper.paper.title)}</title><style>body{font-family:"Microsoft YaHei";line-height:1.7;margin:32px}header{text-align:center}article{break-inside:avoid;margin:18px 0}.material,.answer{padding:12px;background:#f5f5f5}@page{margin:16mm}</style></head><body><header><h1>${escapeHtml(paper.paper.title)}</h1><p>满分 ${paper.paper.totalScore} 分　建议用时 ${paper.paper.durationMinutes} 分钟</p></header>${sections}</body></html>`); win.document.close(); setTimeout(()=>win.print(),250);
}

async function deleteBatch(batchId: number) {
  if (!window.confirm("确定删除该批次及其全部题目和答题记录吗？")) return;
  busy.value = true;
  try {
    await api.deleteQuestionBatch(batchId);
    await loadPracticeData();
    emit("notify", "success", "出题批次已删除");
  } catch (error) {
    emit("notify", "error", error instanceof Error ? error.message : "删除批次失败");
  } finally {
    busy.value = false;
  }
}

async function deleteQuestion(question: Question) {
  if (!window.confirm("确定删除这道题吗？")) return;
  busy.value = true;
  try {
    await api.deleteQuestion(question.id);
    await loadPracticeData();
    emit("notify", "success", "题目已删除");
  } catch (error) {
    emit("notify", "error", error instanceof Error ? error.message : "删除题目失败");
  } finally {
    busy.value = false;
  }
}

async function editQuestion(question: Question) {
  const stem=window.prompt("编辑题干",question.stem); if(stem==null||!stem.trim())return;
  let questionData=question.questionData;
  if(isComposite(question)){ const edited=window.prompt("编辑复合题 JSON（material 与 subQuestions）",question.questionData||"{}"); if(edited==null)return; questionData=edited; }
  try { await api.updateQuestion(question.id,{...question,stem:stem.trim(),questionData}); await loadPracticeData(); if(selectedPaper.value)await openPaper(selectedPaper.value.paper.id); emit("notify","success","题目已更新"); }
  catch(error){emit("notify","error",error instanceof Error?error.message:"更新失败");}
}

function matchesFilters(question: Question) {
  return (!typeFilter.value || question.type === typeFilter.value)
    && (!subjectFilter.value || (question.subject || "general") === subjectFilter.value)
    && (!difficultyFilter.value || question.difficulty === difficultyFilter.value)
    && (!chapterFilter.value || parseChapterTags(question.chapterTags).includes(chapterFilter.value))
    && (!fileFilter.value || String(question.sourceDocumentId || "") === fileFilter.value);
}

function parseChapterTags(raw?: string) {
  if (!raw) return [];
  try {
    const parsed = JSON.parse(raw);
    return Array.isArray(parsed) ? parsed.map(String).map((item) => item.trim()).filter(Boolean) : [];
  } catch {
    return raw.split(/[,，;；]/).map((item) => item.trim()).filter(Boolean);
  }
}

function documentNames(ids: number[]) {
  const names = ids.map((id) => documents.value.find((item) => item.id === id)?.filename || `文件 ${id}`);
  return names.length > 3 ? `${names.slice(0, 3).join("、")} 等 ${names.length} 个文件` : names.join("、");
}

function documentName(id: number) {
  return documents.value.find((item) => item.id === id)?.filename || `文件 ${id}`;
}

async function submit(question: Question) {
  if (!props.course) return;
  const answer = (answers.value[question.id] || "").trim();
  if (!answer) { emit("notify", "error", "请先填写答案"); return; }
  busy.value = true;
  try {
    const record = await api.submitAnswer(props.course.id, question.id, answer);
    resultByQuestion.value = { ...resultByQuestion.value, [question.id]: record };
    emit("notify", record.isCorrect === true ? "success" : "info", resultText(record));
    await loadPracticeData();
  } catch (error) {
    emit("notify", "error", error instanceof Error ? error.message : "提交失败");
  } finally {
    busy.value = false;
  }
}

async function submitComposite(question: Question) {
  if (!props.course) return;
  const submitted = compositeAnswers.value[question.id] || {};
  const payload = subQuestions(question).map((sub) => ({ subQuestionKey: sub.key, answer: (submitted[sub.key] || "").trim() }));
  if (!payload.some((item) => item.answer)) { emit("notify", "error", "请至少填写一个小题答案"); return; }
  busy.value = true;
  try {
    const record = await api.submitCompositeAnswer(props.course.id, question.id, payload);
    resultByQuestion.value = { ...resultByQuestion.value, [question.id]: record };
    emit("notify", "info", resultText(record));
    await loadPracticeData();
  } catch (error) {
    emit("notify", "error", error instanceof Error ? error.message : "提交失败");
  } finally {
    busy.value = false;
  }
}

function parsedQuestionData(question: Question): QuestionData | null {
  if (!question.questionData) return null;
  try { return JSON.parse(question.questionData) as QuestionData; } catch { return null; }
}

function questionMaterial(question: Question) { return parsedQuestionData(question)?.material; }
function isComposite(question: Question) { return Boolean(parsedQuestionData(question)?.subQuestions?.length); }
function subQuestions(question: Question): SubQuestion[] {
  const items = parsedQuestionData(question)?.subQuestions || [];
  if (!compositeAnswers.value[question.id]) compositeAnswers.value[question.id] = {};
  return items.map((item, index) => ({ ...item, key: item.key || `q${index + 1}` }));
}
function toggleSubOption(questionId: number, sub: SubQuestion, option: string) {
  const value = optionAnswer(option);
  const current = compositeAnswers.value[questionId] || (compositeAnswers.value[questionId] = {});
  if (sub.type !== "multi_choice") { current[sub.key] = value; return; }
  const selected = new Set((current[sub.key] || "").split(""));
  selected.has(value) ? selected.delete(value) : selected.add(value);
  current[sub.key] = Array.from(selected).sort().join("");
}
function subAnswerSelected(questionId: number, sub: SubQuestion, option: string) {
  const current = compositeAnswers.value[questionId]?.[sub.key] || "";
  const value = optionAnswer(option);
  return sub.type === "multi_choice" ? current.includes(value) : current === value;
}
function resultText(record: PracticeRecord) {
  if (record.isCorrect === true) return "回答正确";
  if (record.isCorrect === false) return "答案不正确";
  return record.gradingStatus === "manual_required" ? "已提交，等待评价" : "答案已保存";
}
function questionById(id: number) { return questions.value.find(item => item.id === id); }
async function manualGrade(record: PracticeRecord) {
  const maxText=window.prompt("本题满分", String(record.maxScore || 30)); if(maxText==null)return;
  const scoreText=window.prompt("本次得分", String(record.score || 0)); if(scoreText==null)return;
  const feedback=window.prompt("批改评语（可选）", record.gradingFeedback || "") || "";
  const maxScore=Number(maxText), score=Number(scoreText); if(!Number.isFinite(maxScore)||!Number.isFinite(score)){emit("notify","error","请输入有效分数");return;}
  try { await api.manualGrade(record.id,score,maxScore,feedback); await loadPracticeData(); emit("notify","success","人工批改已保存"); }
  catch(error){emit("notify","error",error instanceof Error?error.message:"批改失败");}
}

function exportQuestions(items: Question[], title: string, showAnswers = true) {
  if (!items.length) return;
  const printWindow = window.open("", "_blank", "width=960,height=720");
  const html = buildPrintableQuestions(items, title, showAnswers);
  if (!printWindow) {
    const blob = new Blob([html], { type: "text/html;charset=utf-8" });
    const url = URL.createObjectURL(blob);
    const link = document.createElement("a"); link.href = url; link.download = `${title}.html`; link.click(); URL.revokeObjectURL(url);
    emit("notify", "info", "打印窗口被拦截，已下载可打印的 HTML 文件");
    return;
  }
  printWindow.document.open(); printWindow.document.write(html); printWindow.document.close(); printWindow.focus();
  window.setTimeout(() => printWindow.print(), 250);
}

function buildPrintableQuestions(items: Question[], title: string, showAnswers: boolean) {
  const body = items.map((question, index) => {
    const options = questionOptions(question).map((option) => `<li>${escapeHtml(option)}</li>`).join("");
    const data = parsedQuestionData(question);
    const material = data?.material?.text ? `<section class="material">${escapeHtml(data.material.text).replace(/\n/g, "<br>")}</section>` : "";
    const subs = (data?.subQuestions || []).map((sub, subIndex) => `<div class="sub"><b>${index + 1}.${subIndex + 1} ${escapeHtml(sub.stem)}</b>${sub.options?.length ? `<ol>${sub.options.map((option) => `<li>${escapeHtml(option)}</li>`).join("")}</ol>` : ""}${showAnswers ? `<p class="answer">答案：${escapeHtml(sub.answer || "")}<br>解析：${escapeHtml(sub.explanation || "")}</p>` : ""}</div>`).join("");
    const answer = showAnswers ? `<div class="answer"><b>答案：</b>${escapeHtml(question.answer)}<br><b>解析：</b>${escapeHtml(question.explanation || "")}</div>` : "";
    return `<article><h2>${index + 1}. ${escapeHtml(question.stem)}</h2>${material}${options ? `<ol>${options}</ol>` : ""}${subs}${answer}</article>`;
  }).join("");
  return `<!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><title>${escapeHtml(title)}</title><style>body{font-family:"Microsoft YaHei",sans-serif;line-height:1.7;margin:32px;color:#17211d}header{border-bottom:2px solid #0f766e}article{break-inside:avoid;border-bottom:1px solid #ddd;padding:16px 0}h2{font-size:17px}.answer{margin-top:12px;background:#f4f8f6;padding:10px}@media print{body{margin:0}.answer{break-inside:avoid}}@page{margin:16mm}</style></head><body><header><h1>${escapeHtml(title)}</h1><p>共 ${items.length} 道题</p></header>${body}</body></html>`;
}

function parseOptions(raw?: unknown): string[] {
  if (Array.isArray(raw)) return raw.map(String).map((item) => item.trim()).filter(Boolean);
  if (raw == null) return [];
  let value: unknown = raw;
  for (let depth = 0; depth < 2 && typeof value === "string"; depth += 1) {
    const text = value.trim();
    if (!text) return [];
    try {
      value = JSON.parse(text) as unknown;
      if (Array.isArray(value)) return value.map(String).map((item) => item.trim()).filter(Boolean);
    } catch {
      return text.split(/\n|;|；/).map((item) => item.trim()).filter(Boolean);
    }
  }
  return [];
}

function questionOptions(question: Question) {
  const options = parseOptions(question.options);
  if (options.length) return options;
  const answer = (question.answer || "").trim().toLowerCase();
  const isTrueFalse = question.type === "true_false"
    || ["正确", "错误", "对", "错", "true", "false"].includes(answer)
    || /^判断/.test(question.stem.trim());
  return isTrueFalse ? ["正确", "错误"] : [];
}

function subQuestionOptions(question: SubQuestion) {
  const options = parseOptions(question.options);
  if (options.length) return options;
  const answer = (question.answer || "").trim().toLowerCase();
  return question.type === "true_false" || ["正确", "错误", "对", "错", "true", "false"].includes(answer)
    ? ["正确", "错误"] : [];
}

function toggleOption(question: Question, option: string) {
  const value = optionAnswer(option);
  if (question.type !== "multi_choice") { answers.value = { ...answers.value, [question.id]: value }; return; }
  const current = new Set((answers.value[question.id] || "").toUpperCase().split("").filter(Boolean));
  current.has(value) ? current.delete(value) : current.add(value);
  answers.value = { ...answers.value, [question.id]: Array.from(current).sort().join("") };
}

function isOptionSelected(question: Question, option: string) {
  const value = optionAnswer(option); const current = answers.value[question.id] || "";
  return question.type === "multi_choice" ? current.includes(value) : current === value;
}

function optionAnswer(option: string) {
  const match = option.trim().match(/^([A-Za-z])[.、\s]/); return match ? match[1].toUpperCase() : option.trim();
}

function typeText(type?: string) {
  return ({ single_choice: "单选题", multi_choice: "多选题", true_false: "判断题", short_answer: "简答题",
    fill_blank: "名句默写", composition: "作文题", classical_chinese_reading: "文言文阅读",
    poetry_appreciation: "古诗词鉴赏", modern_reading: "现代文阅读", translation: "翻译题",
    sentence_break: "断句题", explanation: "字词解释", language_basic: "语言基础" } as Record<string, string>)[type || ""] || type || "题目";
}

function difficultyText(difficulty?: string) {
  return ({ easy: "简单", medium: "中等", hard: "困难" } as Record<string, string>)[difficulty || ""] || difficulty || "未设难度";
}

function formatDate(value: string) {
  const date = new Date(value); return Number.isNaN(date.getTime()) ? value : new Intl.DateTimeFormat("zh-CN", { month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit" }).format(date);
}

function escapeHtml(value?: string | number | null) {
  return String(value ?? "").replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;").replace(/'/g, "&#39;");
}
</script>
