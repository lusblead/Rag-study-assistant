<template>
  <section class="page-grid">
    <aside class="side-panel">
      <Panel title="课程">
        <CourseForm @created="emit('course-created')" @error="notifyError" />

        <div class="list compact-list">
          <button
            v-for="item in courses"
            :key="item.id"
            :class="['list-row', { active: course?.id === item.id }]"
            type="button"
            @click="emit('course-selected', item.id)"
          >
            <span>
              <strong>{{ item.name }}</strong>
              <small>{{ item.term || "未设置学期" }}</small>
            </span>
          </button>
        </div>

        <button v-if="course" class="danger block" :disabled="busy" type="button" @click="removeCourse(course.id)">
          删除当前课程
        </button>
      </Panel>
    </aside>

    <section class="content-panel">
      <div class="section-heading tight">
        <div>
          <p class="eyebrow">知识库管理</p>
          <h1>{{ course ? course.name : "请选择课程" }}</h1>
          <p>支持上传 TXT、Markdown、DOC、DOCX、PPTX 和文字版 PDF，并触发 Agent 解析入库。</p>
        </div>
        <button :disabled="!course || loading" type="button" @click="loadDocuments">刷新</button>
      </div>

      <Panel title="上传并处理文档">
        <form class="upload-row" @submit.prevent="upload">
          <label class="file-input">
            <input
              accept=".txt,.md,.markdown,.doc,.docx,.pptx,.pdf"
              :disabled="!course || busy"
              type="file"
              @change="onFileChange"
            />
            <span>{{ file ? file.name : "选择课程资料文件" }}</span>
          </label>
          <label class="checkbox">
            <input v-model="autoIngest" type="checkbox" />
            上传后自动入库
          </label>
          <button :disabled="!course || !file || busy" type="submit">
            {{ busy ? "处理中" : "上传" }}
          </button>
        </form>
      </Panel>

      <Panel title="文档列表">
        <EmptyState v-if="loading" title="正在加载文档" />
        <EmptyState v-else-if="!documents.length" title="暂无文档" text="上传资料后即可开始 RAG 入库。" />
        <div class="doc-list">
          <article v-for="document in documents" :key="document.id" class="doc-row">
            <div>
              <strong>{{ document.filename }}</strong>
              <small>
                {{ document.fileType || "unknown" }} · {{ formatDate(document.createdAt) }} ·
                {{ document.chunkCount ?? 0 }} 个片段
              </small>
            </div>
            <StatusBadge :status="document.parseStatus" />
            <div class="row-actions">
              <button :disabled="busy" type="button" @click="ingest(document.id)">
                {{ document.parseStatus === "PARSED" ? "重新入库" : "解析入库" }}
              </button>
              <button class="danger" :disabled="busy" type="button" @click="removeDocument(document.id)">删除</button>
            </div>
          </article>
        </div>
      </Panel>
    </section>
  </section>
</template>

<script setup lang="ts">
import { ref, watch } from "vue";
import { api } from "../api";
import CourseForm from "../components/CourseForm.vue";
import EmptyState from "../components/EmptyState.vue";
import Panel from "../components/Panel.vue";
import StatusBadge from "../components/StatusBadge.vue";
import type { Course, CourseDocument } from "../types";

const props = defineProps<{
  course: Course | null;
  courses: Course[];
}>();

const emit = defineEmits<{
  "course-created": [];
  "course-deleted": [];
  "course-selected": [courseId: number | null];
  notify: [tone: "success" | "error" | "info", message: string];
}>();

const documents = ref<CourseDocument[]>([]);
const file = ref<File | null>(null);
const autoIngest = ref(true);
const busy = ref(false);
const loading = ref(false);

watch(
  () => props.course?.id,
  () => {
    void loadDocuments();
  },
  { immediate: true }
);

function notifyError(message: string) {
  emit("notify", "error", message);
}

function onFileChange(event: Event) {
  file.value = (event.target as HTMLInputElement).files?.[0] ?? null;
}

async function loadDocuments() {
  if (!props.course) {
    documents.value = [];
    return;
  }
  loading.value = true;
  try {
    documents.value = await api.listDocuments(props.course.id);
  } catch (error) {
    emit("notify", "error", error instanceof Error ? error.message : "文档加载失败");
  } finally {
    loading.value = false;
  }
}

async function upload() {
  if (!props.course || !file.value) {
    emit("notify", "error", "请选择课程和文件");
    return;
  }

  busy.value = true;
  try {
    const uploaded = await api.uploadDocument(props.course.id, file.value);
    if (autoIngest.value) {
      const chunks = await api.ingestDocument(uploaded.id);
      emit("notify", "success", `上传完成，已入库 ${chunks} 个知识片段`);
    } else {
      emit("notify", "success", "上传完成，可在列表中手动入库");
    }
    file.value = null;
    await loadDocuments();
  } catch (error) {
    emit("notify", "error", error instanceof Error ? error.message : "上传失败");
  } finally {
    busy.value = false;
  }
}

async function ingest(documentId: number) {
  busy.value = true;
  try {
    const chunks = await api.ingestDocument(documentId);
    emit("notify", "success", `入库完成，共 ${chunks} 个知识片段`);
    await loadDocuments();
  } catch (error) {
    emit("notify", "error", error instanceof Error ? error.message : "入库失败");
  } finally {
    busy.value = false;
  }
}

async function removeDocument(documentId: number) {
  if (!window.confirm("确定删除该文档及其知识片段吗？")) return;
  busy.value = true;
  try {
    await api.deleteDocument(documentId);
    emit("notify", "success", "文档已删除");
    await loadDocuments();
  } catch (error) {
    emit("notify", "error", error instanceof Error ? error.message : "删除失败");
  } finally {
    busy.value = false;
  }
}

async function removeCourse(courseId: number) {
  if (!window.confirm("确定删除该课程吗？相关文档、题目和记录也会被后端清理。")) return;
  busy.value = true;
  try {
    await api.deleteCourse(courseId);
    emit("course-selected", null);
    emit("course-deleted");
  } catch (error) {
    emit("notify", "error", error instanceof Error ? error.message : "课程删除失败");
  } finally {
    busy.value = false;
  }
}

function formatDate(value?: string) {
  if (!value) return "未知时间";
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  return new Intl.DateTimeFormat("zh-CN", {
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit"
  }).format(date);
}
</script>
