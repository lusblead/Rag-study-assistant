<template>
  <section class="chat-layout">
    <aside :class="['chat-sidebar', { collapsed: !sidebarOpen }]">
      <div class="sidebar-head">
        <strong>历史会话</strong>
        <button class="ghost" type="button" @click="sidebarOpen = !sidebarOpen">
          {{ sidebarOpen ? "收起" : "展开" }}
        </button>
      </div>

      <template v-if="sidebarOpen">
        <button class="block" type="button" @click="newSession">新建会话</button>
        <div class="list session-list">
          <div
            v-for="session in sessions"
            :key="session.id"
            :class="['session-row', { active: activeSessionId === session.id }]"
          >
            <button type="button" @click="openSession(session.id)">
              <span>
                <strong>{{ session.title || `会话 ${session.id}` }}</strong>
                <small>{{ formatDate(session.updatedAt || session.createdAt) }}</small>
              </span>
            </button>
            <button class="icon danger" type="button" @click="deleteSession(session.id)">删除</button>
          </div>
          <EmptyState v-if="!sessions.length" title="暂无历史会话" />
        </div>
      </template>
    </aside>

    <section class="chat-main">
      <div class="chat-head">
        <div>
          <p class="eyebrow">RAG 答疑</p>
          <h1>{{ course ? course.name : "请选择课程" }}</h1>
        </div>
        <label class="checkbox">
          <input v-model="streaming" type="checkbox" />
          流式输出
        </label>
      </div>

      <div class="message-list">
        <EmptyState v-if="!course" title="请选择课程后开始对话" />
        <EmptyState
          v-else-if="!messages.length"
          title="开始一次课程问答"
          text="例如：请总结这门课的核心概念，或者询问某个知识点的区别。"
        />

        <article v-for="message in messages" :key="message.id" :class="['message', message.role]">
          <div class="message-meta">{{ message.role === "user" ? "你" : "学习助手" }}</div>
          <div class="message-body">{{ message.content || "正在生成..." }}</div>
          <ReferencesList v-if="message.references?.length" :references="message.references" />
        </article>
      </div>

      <form class="chat-form" @submit.prevent="submitQuestion">
        <textarea
          v-model="question"
          :disabled="!course || busy"
          placeholder="输入课程问题，答案会基于已入库文档生成"
          rows="3"
        />
        <button :disabled="!course || busy || !question.trim()" type="submit">
          {{ busy ? "生成中" : "发送" }}
        </button>
      </form>
    </section>

    <aside class="references-panel">
      <Panel title="最新引用片段">
        <ReferencesList v-if="latestReferences.length" :references="latestReferences" />
        <EmptyState v-else title="暂无引用" />
      </Panel>
    </aside>
  </section>
</template>

<script setup lang="ts">
import { ref, watch } from "vue";
import { api, streamChat } from "../api";
import EmptyState from "../components/EmptyState.vue";
import Panel from "../components/Panel.vue";
import ReferencesList from "../components/ReferencesList.vue";
import type { ChatSession, Course, RetrievedChunk } from "../types";

type UiMessage = {
  id: string;
  role: "user" | "assistant";
  content: string;
  createdAt?: string;
  references?: RetrievedChunk[];
};

const props = defineProps<{
  course: Course | null;
}>();

const emit = defineEmits<{
  notify: [tone: "success" | "error" | "info", message: string];
}>();

const sessions = ref<ChatSession[]>([]);
const activeSessionId = ref<number | null>(null);
const messages = ref<UiMessage[]>([]);
const question = ref("");
const streaming = ref(true);
const busy = ref(false);
const sidebarOpen = ref(true);
const latestReferences = ref<RetrievedChunk[]>([]);

watch(
  () => props.course?.id,
  () => {
    activeSessionId.value = null;
    messages.value = [];
    latestReferences.value = [];
    void loadSessions();
  },
  { immediate: true }
);

async function loadSessions() {
  if (!props.course) {
    sessions.value = [];
    return;
  }
  try {
    sessions.value = await api.listSessions(props.course.id);
  } catch (error) {
    emit("notify", "error", error instanceof Error ? error.message : "会话加载失败");
  }
}

function newSession() {
  activeSessionId.value = null;
  messages.value = [];
  latestReferences.value = [];
}

async function openSession(sessionId: number) {
  activeSessionId.value = sessionId;
  latestReferences.value = [];
  try {
    const history = await api.listMessages(sessionId);
    messages.value = history.map((message) => ({
      id: String(message.id),
      role: message.role === "assistant" ? "assistant" : "user",
      content: message.content,
      createdAt: message.createdAt
    }));
  } catch (error) {
    emit("notify", "error", error instanceof Error ? error.message : "消息加载失败");
  }
}

async function deleteSession(sessionId: number) {
  if (!window.confirm("确定删除该会话吗？")) return;
  try {
    await api.deleteSession(sessionId);
    if (activeSessionId.value === sessionId) {
      newSession();
    }
    await loadSessions();
    emit("notify", "success", "会话已删除");
  } catch (error) {
    emit("notify", "error", error instanceof Error ? error.message : "删除失败");
  }
}

async function submitQuestion() {
  const text = question.value.trim();
  if (!props.course || !text) return;

  const assistantId = `assistant-${Date.now()}`;
  busy.value = true;
  question.value = "";
  latestReferences.value = [];
  messages.value = [
    ...messages.value,
    { id: `user-${Date.now()}`, role: "user", content: text },
    { id: assistantId, role: "assistant", content: streaming.value ? "" : "正在思考..." }
  ];

  try {
    if (streaming.value) {
      let nextSessionId = activeSessionId.value;
      await streamChat(
        { courseId: props.course.id, sessionId: activeSessionId.value, question: text },
        {
          onSession: (sessionId) => {
            nextSessionId = sessionId;
            activeSessionId.value = sessionId;
          },
          onReferences: (references) => {
            latestReferences.value = references;
            patchAssistant(assistantId, { references });
          },
          onDelta: (delta) => {
            const target = messages.value.find((message) => message.id === assistantId);
            patchAssistant(assistantId, { content: `${target?.content || ""}${delta}` });
          },
          onError: (message) => emit("notify", "error", message)
        }
      );
      activeSessionId.value = nextSessionId;
    } else {
      const response = await api.chat({
        courseId: props.course.id,
        sessionId: activeSessionId.value,
        question: text
      });
      activeSessionId.value = response.sessionId;
      latestReferences.value = response.references || [];
      patchAssistant(assistantId, {
        content: response.answer,
        references: response.references || []
      });
    }
    await loadSessions();
  } catch (error) {
    const message = error instanceof Error ? error.message : "回答失败";
    patchAssistant(assistantId, { content: message });
    emit("notify", "error", message);
  } finally {
    busy.value = false;
  }
}

function patchAssistant(id: string, patch: Partial<UiMessage>) {
  messages.value = messages.value.map((message) => (message.id === id ? { ...message, ...patch } : message));
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
