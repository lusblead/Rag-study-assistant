<template>
  <section class="page-grid practice-grid">
    <aside class="side-panel">
      <Panel title="AI 出题">
        <div class="generation-grid">
          <label>
            数量
            <input v-model.number="questionCount" :disabled="!course || busy" max="10" min="1" type="number" />
          </label>
          <label>
            题型
            <select v-model="generateType" :disabled="!course || busy">
              <option value="single_choice">单选题</option>
              <option value="multi_choice">多选题</option>
              <option value="true_false">判断题</option>
              <option value="short_answer">简答题</option>
              <option value="mixed">混合题型</option>
            </select>
          </label>
          <label>
            难度
            <select v-model="generateDifficulty" :disabled="!course || busy">
              <option value="easy">简单</option>
              <option value="medium">中等</option>
              <option value="hard">困难</option>
            </select>
          </label>
        </div>
        <label>
          出题要求
          <textarea v-model="requirement" :disabled="!course || busy" rows="5" />
        </label>
        <button class="block" :disabled="!course || busy || !requirement.trim()" type="button" @click="generate">
          {{ busy ? "处理中..." : "生成并保存题目" }}
        </button>
      </Panel>

      <Panel title="练习概览">
        <div class="metric-grid">
          <div class="metric">
            <strong>{{ questions.length }}</strong>
            <span>题目</span>
          </div>
          <div class="metric">
            <strong>{{ records.length }}</strong>
            <span>提交</span>
          </div>
          <div class="metric">
            <strong>{{ wrongRecords.length }}</strong>
            <span>错题</span>
          </div>
        </div>
      </Panel>
    </aside>

    <section class="content-panel">
      <div class="section-heading tight">
        <div>
          <p class="eyebrow">题库练习</p>
          <h1>{{ course ? course.name : "请选择课程" }}</h1>
        </div>
        <div class="filters">
          <select v-model="typeFilter">
            <option value="">全部题型</option>
            <option value="single_choice">单选题</option>
            <option value="multi_choice">多选题</option>
            <option value="true_false">判断题</option>
            <option value="short_answer">简答题</option>
          </select>
          <select v-model="difficultyFilter">
            <option value="">全部难度</option>
            <option value="easy">简单</option>
            <option value="medium">中等</option>
            <option value="hard">困难</option>
          </select>
          <button class="ghost" :disabled="!course || !questions.length" type="button" @click="exportQuestions">
            导出 PDF
          </button>
          <button
            class="ghost icon-only"
            :disabled="!course || busy"
            title="刷新题库"
            type="button"
            aria-label="刷新题库"
            @click="loadPracticeData"
          >
            ↻
          </button>
        </div>
      </div>

      <EmptyState v-if="!course" title="请选择课程后查看题库" />
      <EmptyState v-else-if="!questions.length" title="暂无题目" text="可以先用左侧 Agent 出题生成题库。" />

      <div class="question-list">
        <article v-for="(item, index) in questions" :key="item.id" class="question-card">
          <div class="question-head">
            <span>#{{ index + 1 }}</span>
            <span class="badge">{{ typeText(item.type) }}</span>
            <span class="badge">{{ difficultyText(item.difficulty) }}</span>
            <span v-if="item.knowledgePoint" class="badge">{{ item.knowledgePoint }}</span>
            <span v-if="item.sourceChunkId" class="badge">片段 {{ item.sourceChunkId }}</span>
          </div>

          <h2>{{ item.stem }}</h2>

          <div v-if="parseOptions(item.options).length" class="option-list">
            <button
              v-for="option in parseOptions(item.options)"
              :key="option"
              :class="['option', { active: isOptionSelected(item, option) }]"
              type="button"
              @click="toggleOption(item, option)"
            >
              {{ option }}
            </button>
          </div>

          <div class="answer-row">
            <input v-model="answers[item.id]" placeholder="填写答案，例如 A、AB、正确，或简答文本" />
            <button :disabled="busy" type="button" @click="submit(item)">提交</button>
          </div>

          <div v-if="resultByQuestion[item.id]" :class="['result', resultByQuestion[item.id].isCorrect ? 'correct' : 'wrong']">
            <strong>{{ resultByQuestion[item.id].isCorrect ? "正确" : "不正确" }}</strong>
            <span>标准答案：{{ item.answer }}</span>
            <p v-if="item.explanation">{{ item.explanation }}</p>
          </div>
        </article>
      </div>
    </section>
  </section>
</template>

<script setup lang="ts">
import { ref, watch } from "vue";
import { api } from "../api";
import EmptyState from "../components/EmptyState.vue";
import Panel from "../components/Panel.vue";
import type { Course, PracticeRecord, Question } from "../types";

const props = defineProps<{
  course: Course | null;
}>();

const emit = defineEmits<{
  notify: [tone: "success" | "error" | "info", message: string];
}>();

const questions = ref<Question[]>([]);
const records = ref<PracticeRecord[]>([]);
const wrongRecords = ref<PracticeRecord[]>([]);
const typeFilter = ref("");
const difficultyFilter = ref("");
const questionCount = ref(3);
const generateType = ref("single_choice");
const generateDifficulty = ref("medium");
const requirement = ref("覆盖当前课程核心知识点，题目、选项和解析使用中文。");
const answers = ref<Record<number, string>>({});
const resultByQuestion = ref<Record<number, PracticeRecord>>({});
const busy = ref(false);

watch(
  [() => props.course?.id, typeFilter, difficultyFilter],
  () => {
    void loadPracticeData();
  },
  { immediate: true }
);

async function loadPracticeData() {
  if (!props.course) {
    questions.value = [];
    records.value = [];
    wrongRecords.value = [];
    return;
  }
  try {
    const [nextQuestions, nextRecords, nextWrong] = await Promise.all([
      api.listQuestions(props.course.id, typeFilter.value, difficultyFilter.value),
      api.listPracticeRecords(props.course.id),
      api.listWrongQuestions(props.course.id)
    ]);
    questions.value = nextQuestions;
    records.value = nextRecords;
    wrongRecords.value = nextWrong;
  } catch (error) {
    emit("notify", "error", error instanceof Error ? error.message : "题库加载失败");
  }
}

async function generate() {
  if (!props.course || !requirement.value.trim()) return;
  busy.value = true;
  try {
    const generated = await api.generateQuestions(props.course.id, buildRequirement());
    emit("notify", "success", `已生成 ${generated.length} 道题`);
    await loadPracticeData();
  } catch (error) {
    emit("notify", "error", error instanceof Error ? error.message : "生成失败");
  } finally {
    busy.value = false;
  }
}

function exportQuestions() {
  if (!props.course || !questions.value.length) {
    emit("notify", "error", "当前没有可导出的题目");
    return;
  }

  const printable = buildPrintableQuestions();
  const printWindow = window.open("", "_blank", "width=960,height=720");
  if (!printWindow) {
    downloadHtml(printable);
    emit("notify", "info", "浏览器阻止了打印窗口，已改为下载 HTML 文件，可用浏览器打开后另存为 PDF");
    return;
  }

  printWindow.document.open();
  printWindow.document.write(printable);
  printWindow.document.close();
  printWindow.focus();
  window.setTimeout(() => {
    printWindow.print();
  }, 250);
}

function buildPrintableQuestions() {
  const title = `${props.course?.name || "课程"} - 题目导出`;
  const exportedAt = new Intl.DateTimeFormat("zh-CN", {
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit"
  }).format(new Date());

  const body = questions.value
    .map((question, index) => {
      const options = parseOptions(question.options)
        .map((option) => `<li>${escapeHtml(option)}</li>`)
        .join("");
      return `
        <article class="question">
          <div class="meta">
            <strong>第 ${index + 1} 题</strong>
            <span>${escapeHtml(typeText(question.type))}</span>
            <span>${escapeHtml(difficultyText(question.difficulty))}</span>
            ${question.knowledgePoint ? `<span>${escapeHtml(question.knowledgePoint)}</span>` : ""}
          </div>
          <h2>${escapeHtml(question.stem)}</h2>
          ${options ? `<ol class="options">${options}</ol>` : ""}
          <p><strong>答案：</strong>${escapeHtml(question.answer)}</p>
          ${question.explanation ? `<p><strong>解析：</strong>${escapeHtml(question.explanation)}</p>` : ""}
          ${question.sourceChunkId ? `<p class="source">来源片段：${question.sourceChunkId}</p>` : ""}
        </article>
      `;
    })
    .join("");

  return `<!doctype html>
<html lang="zh-CN">
<head>
  <meta charset="utf-8" />
  <title>${escapeHtml(title)}</title>
  <style>
    * { box-sizing: border-box; }
    body { color: #17211d; font-family: "Microsoft YaHei", "PingFang SC", Arial, sans-serif; line-height: 1.65; margin: 0; padding: 28px; }
    header { border-bottom: 2px solid #0f766e; margin-bottom: 22px; padding-bottom: 12px; }
    h1 { font-size: 24px; margin: 0 0 6px; }
    .summary { color: #64736d; font-size: 13px; }
    .question { break-inside: avoid; border-bottom: 1px solid #dbe4df; padding: 16px 0; }
    .question h2 { font-size: 17px; margin: 10px 0; }
    .meta { align-items: center; display: flex; flex-wrap: wrap; gap: 8px; }
    .meta span { background: #eef6f3; border-radius: 999px; color: #0a5f59; font-size: 12px; padding: 2px 8px; }
    .options { margin: 8px 0 10px 22px; padding: 0; }
    .source { color: #64736d; font-size: 12px; }
    @page { margin: 18mm; }
    @media print { body { padding: 0; } button { display: none; } }
  </style>
</head>
<body>
  <header>
    <h1>${escapeHtml(title)}</h1>
    <div class="summary">导出时间：${escapeHtml(exportedAt)} · 共 ${questions.value.length} 道题</div>
  </header>
  ${body}
</body>
</html>`;
}

function downloadHtml(content: string) {
  const blob = new Blob([content], { type: "text/html;charset=utf-8" });
  const url = URL.createObjectURL(blob);
  const link = document.createElement("a");
  link.href = url;
  link.download = `${props.course?.name || "questions"}-题目导出.html`;
  document.body.appendChild(link);
  link.click();
  link.remove();
  URL.revokeObjectURL(url);
}

function escapeHtml(value?: string | number | null) {
  return String(value ?? "")
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;")
    .replace(/'/g, "&#39;");
}

function buildRequirement() {
  const count = Math.min(Math.max(Number(questionCount.value) || 1, 1), 10);
  const type = generateType.value === "mixed" ? "混合题型" : typeText(generateType.value);
  const difficulty = difficultyText(generateDifficulty.value);
  return `生成 ${count} 道${type}，难度 ${difficulty}。${requirement.value.trim()}`;
}

async function submit(question: Question) {
  if (!props.course) return;
  const answer = (answers.value[question.id] || "").trim();
  if (!answer) {
    emit("notify", "error", "请先填写答案");
    return;
  }

  busy.value = true;
  try {
    const record = await api.submitAnswer(props.course.id, question.id, answer);
    resultByQuestion.value = { ...resultByQuestion.value, [question.id]: record };
    emit("notify", record.isCorrect ? "success" : "info", record.isCorrect ? "回答正确" : "已提交，答案不正确");
    await loadPracticeData();
  } catch (error) {
    emit("notify", "error", error instanceof Error ? error.message : "提交失败");
  } finally {
    busy.value = false;
  }
}

function parseOptions(raw?: string) {
  if (!raw) return [];
  try {
    const parsed = JSON.parse(raw);
    return Array.isArray(parsed) ? parsed.map(String) : [];
  } catch {
    return raw
      .split(/\n|;|；/)
      .map((item) => item.trim())
      .filter(Boolean);
  }
}

function toggleOption(question: Question, option: string) {
  const value = optionAnswer(option);
  if (question.type !== "multi_choice") {
    answers.value = { ...answers.value, [question.id]: value };
    return;
  }

  const current = new Set((answers.value[question.id] || "").toUpperCase().split("").filter(Boolean));
  if (current.has(value)) {
    current.delete(value);
  } else {
    current.add(value);
  }
  answers.value = {
    ...answers.value,
    [question.id]: Array.from(current).sort().join("")
  };
}

function isOptionSelected(question: Question, option: string) {
  const value = optionAnswer(option);
  const current = answers.value[question.id] || "";
  return question.type === "multi_choice" ? current.includes(value) : current === value;
}

function optionAnswer(option: string) {
  const match = option.trim().match(/^([A-Za-z])[\.\、\s]/);
  return match ? match[1].toUpperCase() : option.trim();
}

function typeText(type?: string) {
  const labels: Record<string, string> = {
    single_choice: "单选题",
    multi_choice: "多选题",
    true_false: "判断题",
    short_answer: "简答题"
  };
  return labels[type || ""] || type || "题目";
}

function difficultyText(difficulty?: string) {
  const labels: Record<string, string> = {
    easy: "简单",
    medium: "中等",
    hard: "困难"
  };
  return labels[difficulty || ""] || difficulty || "未设难度";
}
</script>
