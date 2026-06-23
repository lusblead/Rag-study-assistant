<template>
  <section class="page-grid">
    <aside class="side-panel">
      <Panel title="课程">
        <div class="course-actions">
          <button type="button" @click="showCreateCourseModal = true">新增课程</button>
          <button class="ghost" :disabled="!courses.length" type="button" @click="openEditCourseModal">修改课程</button>
        </div>

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
        <form class="upload-stack" @submit.prevent="upload">
          <label class="file-input">
            <input
              accept=".txt,.md,.markdown,.doc,.docx,.pptx,.pdf"
              :disabled="!course || busy"
              multiple
              type="file"
              @change="onFileChange"
            />
            <span>{{ files.length ? `已选择 ${files.length} 个文件` : "选择课程资料文件，可多选" }}</span>
          </label>

          <div
            :class="['drop-zone', { active: dragActive, disabled: !course || busy }]"
            @dragenter.prevent="onDragEnter"
            @dragover.prevent="onDragEnter"
            @dragleave.prevent="onDragLeave"
            @drop.prevent="onDrop"
          >
            <strong>拖拽文档到这里一键上传</strong>
            <span>支持 TXT、Markdown、DOC、DOCX、PPTX 和文字版 PDF，可一次拖入多个文件。</span>
          </div>

          <div v-if="files.length" class="selected-files">
            <span v-for="selected in files" :key="selected.name + selected.size">{{ selected.name }}</span>
          </div>

          <div class="upload-actions">
            <label class="checkbox">
              <input v-model="autoIngest" type="checkbox" />
              上传后自动入库
            </label>
            <button :disabled="!course || !files.length || busy" type="submit">
              {{ busy ? "处理中" : `上传 ${files.length || ""} 个文件` }}
            </button>
          </div>
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
              <button class="ghost" type="button" @click="openPreview(document)">查看</button>
              <button :disabled="busy" type="button" @click="ingest(document.id)">
                {{ document.parseStatus === "PARSED" ? "重新入库" : "解析入库" }}
              </button>
              <a class="button-link ghost" :href="api.documentFileUrl(document.id)" target="_blank" rel="noreferrer">
                下载
              </a>
              <button class="danger" :disabled="busy" type="button" @click="removeDocument(document.id)">删除</button>
            </div>
          </article>
        </div>
      </Panel>
    </section>
  </section>

  <div v-if="showCreateCourseModal" class="modal-backdrop" @click.self="showCreateCourseModal = false">
    <section class="modal-panel" role="dialog" aria-modal="true" aria-labelledby="create-course-title">
      <div class="modal-head">
        <h2 id="create-course-title">新增课程</h2>
        <button class="ghost icon-only" type="button" aria-label="关闭" @click="showCreateCourseModal = false">×</button>
      </div>
      <CourseForm @created="handleCourseCreated" @error="notifyError" />
    </section>
  </div>

  <div v-if="showEditCourseModal" class="modal-backdrop" @click.self="showEditCourseModal = false">
    <section class="modal-panel" role="dialog" aria-modal="true" aria-labelledby="edit-course-title">
      <div class="modal-head">
        <h2 id="edit-course-title">修改课程</h2>
        <button class="ghost icon-only" type="button" aria-label="关闭" @click="showEditCourseModal = false">×</button>
      </div>

      <label>
        选择课程
        <select :value="editCourseId ?? ''" @change="onEditCourseChange">
          <option disabled value="">请选择课程</option>
          <option v-for="item in courses" :key="item.id" :value="item.id">
            {{ item.name }}{{ item.term ? `（${item.term}）` : "" }}
          </option>
        </select>
      </label>

      <form v-if="selectedEditCourse" class="course-form" @submit.prevent="saveCourse">
        <input v-model="editName" placeholder="课程名称" />
        <input v-model="editTerm" placeholder="学期，例如 2026春" />
        <textarea v-model="editDescription" placeholder="课程描述" rows="5" />
        <div class="form-actions">
          <button :disabled="busy || !editName.trim() || !hasCourseChanges" type="submit">保存修改</button>
          <button class="danger" :disabled="busy" type="button" @click="removeCourse(selectedEditCourse.id)">
            删除课程
          </button>
        </div>
      </form>
      <EmptyState v-else title="暂无可修改课程" text="请先新增课程。" />
      <small class="muted-note">删除课程会同时清理该课程下的文档、知识片段、向量、题目、练习记录和会话历史。</small>
    </section>
  </div>

  <div v-if="previewDocument" class="modal-backdrop" @click.self="closePreview">
    <section class="modal-panel document-preview-panel" role="dialog" aria-modal="true" aria-labelledby="document-preview-title">
      <div class="modal-head">
        <h2 id="document-preview-title">{{ previewDocument.filename }}</h2>
        <button class="ghost icon-only" type="button" aria-label="关闭" @click="closePreview">×</button>
      </div>
      <iframe
        v-if="canInlinePreview(previewDocument)"
        class="document-preview-frame"
        :src="api.documentFileUrl(previewDocument.id)"
        title="文档预览"
      />
      <div v-else class="document-preview-empty">
        <strong>该类型可能无法在浏览器内直接预览</strong>
        <span>可以点击下方按钮在新窗口打开或下载原文件。</span>
      </div>
      <div class="form-actions">
        <a class="button-link" :href="api.documentFileUrl(previewDocument.id)" target="_blank" rel="noreferrer">
          新窗口打开
        </a>
        <button class="ghost" type="button" @click="closePreview">关闭</button>
      </div>
    </section>
  </div>
</template>

<script setup lang="ts">
import { computed, ref, watch } from "vue";
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
  "course-updated": [];
  "course-selected": [courseId: number | null];
  notify: [tone: "success" | "error" | "info", message: string];
}>();

const documents = ref<CourseDocument[]>([]);
const files = ref<File[]>([]);
const autoIngest = ref(true);
const busy = ref(false);
const loading = ref(false);
const dragActive = ref(false);
const showCreateCourseModal = ref(false);
const showEditCourseModal = ref(false);
const previewDocument = ref<CourseDocument | null>(null);
const editCourseId = ref<number | null>(null);
const editName = ref("");
const editTerm = ref("");
const editDescription = ref("");

const selectedEditCourse = computed(() => props.courses.find((item) => item.id === editCourseId.value) ?? null);

const hasCourseChanges = computed(() => {
  const target = selectedEditCourse.value;
  if (!target) {
    return false;
  }
  return (
    editName.value.trim() !== target.name ||
    editTerm.value.trim() !== (target.term || "") ||
    editDescription.value.trim() !== (target.description || "")
  );
});

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

function handleCourseCreated() {
  showCreateCourseModal.value = false;
  emit("course-created");
}

function openEditCourseModal() {
  editCourseId.value = props.course?.id ?? props.courses[0]?.id ?? null;
  syncEditForm();
  showEditCourseModal.value = true;
}

function onEditCourseChange(event: Event) {
  const value = (event.target as HTMLSelectElement).value;
  editCourseId.value = value ? Number(value) : null;
  syncEditForm();
}

function syncEditForm() {
  const target = selectedEditCourse.value;
  editName.value = target?.name ?? "";
  editTerm.value = target?.term ?? "";
  editDescription.value = target?.description ?? "";
}

function onFileChange(event: Event) {
  files.value = normalizeFiles((event.target as HTMLInputElement).files);
  (event.target as HTMLInputElement).value = "";
}

function onDragEnter() {
  if (!props.course || busy.value) return;
  dragActive.value = true;
}

function onDragLeave(event: DragEvent) {
  const current = event.currentTarget as HTMLElement | null;
  const related = event.relatedTarget as Node | null;
  if (current && related && current.contains(related)) {
    return;
  }
  dragActive.value = false;
}

function onDrop(event: DragEvent) {
  dragActive.value = false;
  if (!props.course || busy.value) return;
  files.value = normalizeFiles(event.dataTransfer?.files);
}

function normalizeFiles(fileList?: FileList | null) {
  const allowed = new Set(["txt", "md", "markdown", "doc", "docx", "pptx", "pdf"]);
  return Array.from(fileList || []).filter((item) => {
    const extension = item.name.split(".").pop()?.toLowerCase() || "";
    return allowed.has(extension);
  });
}

function openPreview(document: CourseDocument) {
  previewDocument.value = document;
}

function closePreview() {
  previewDocument.value = null;
}

function canInlinePreview(document: CourseDocument) {
  return new Set(["pdf", "txt", "md", "markdown"]).has((document.fileType || "").toLowerCase());
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
  if (!props.course || !files.value.length) {
    emit("notify", "error", "请选择课程和文件，或把文件拖拽到上传区域");
    return;
  }

  busy.value = true;
  try {
    let uploadedCount = 0;
    let chunkCount = 0;
    for (const selectedFile of files.value) {
      const uploaded = await api.uploadDocument(props.course.id, selectedFile);
      uploadedCount += 1;
      if (autoIngest.value) {
        chunkCount += await api.ingestDocument(uploaded.id);
      }
    }
    emit(
      "notify",
      "success",
      autoIngest.value
        ? `上传完成，${uploadedCount} 个文件已入库 ${chunkCount} 个知识片段`
        : `上传完成 ${uploadedCount} 个文件，可在列表中手动入库`
    );
    files.value = [];
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

async function saveCourse() {
  const target = selectedEditCourse.value;
  if (!target || !editName.value.trim()) return;
  busy.value = true;
  try {
    const updated = await api.updateCourse(target.id, {
      name: editName.value.trim(),
      term: editTerm.value.trim(),
      description: editDescription.value.trim()
    });
    editName.value = updated.name;
    editTerm.value = updated.term || "";
    editDescription.value = updated.description || "";
    if (props.course?.id !== updated.id) {
      emit("course-selected", updated.id);
    }
    showEditCourseModal.value = false;
    emit("course-updated");
  } catch (error) {
    emit("notify", "error", error instanceof Error ? error.message : "课程修改失败");
  } finally {
    busy.value = false;
  }
}

async function removeCourse(courseId: number) {
  const courseName = props.courses.find((item) => item.id === courseId)?.name || `课程 ${courseId}`;
  if (!window.confirm(`确定删除「${courseName}」吗？相关文档、题目、练习记录和会话历史也会被后端清理。`)) {
    return;
  }
  busy.value = true;
  try {
    await api.deleteCourse(courseId);
    if (props.course?.id === courseId) {
      emit("course-selected", null);
    }
    showEditCourseModal.value = false;
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
