<template>
  <div class="app">
    <header class="topbar">
      <button class="brand" type="button" @click="navigate('home')">
        <span class="brand-mark">R</span>
        <span>
          <strong>RAG 学习助手</strong>
          <small>课程知识库与 Agent 演示台</small>
        </span>
      </button>

      <nav class="nav">
        <button
          v-for="item in mainRoutes"
          :key="item"
          :class="{ active: route === item }"
          type="button"
          @click="navigate(item)"
        >
          {{ routeLabels[item] }}
        </button>
      </nav>

      <div class="top-actions">
        <select :disabled="!courses.length" :value="courseId ?? ''" aria-label="当前课程" @change="onCourseChange">
          <option value="">未选择课程</option>
          <option v-for="course in courses" :key="course.id" :value="course.id">
            {{ course.name }}
          </option>
        </select>
        <button class="ghost" type="button" @click="navigate('help')">说明</button>
        <button class="ghost" type="button" @click="navigate('settings')">设置</button>
      </div>
    </header>

    <main class="main">
      <div v-if="courseError" class="banner error">
        <strong>无法连接后端：</strong>
        <span>{{ courseError }}</span>
        <button type="button" @click="loadCourses">重试</button>
      </div>

      <HomePage
        v-if="route === 'home'"
        :course="selectedCourse"
        :courses="courses"
        :loading-courses="loadingCourses"
        @course-created="handleCourseCreated"
        @navigate="navigate"
        @notify="notify"
      />

      <KnowledgePage
        v-else-if="route === 'knowledge'"
        :course="selectedCourse"
        :courses="courses"
        @course-created="handleCourseCreated"
        @course-deleted="handleCourseDeleted"
        @course-selected="changeCourse"
        @course-updated="handleCourseUpdated"
        @notify="notify"
      />

      <ChatPage v-else-if="route === 'chat'" :course="selectedCourse" @notify="notify" />

      <PracticePage v-else-if="route === 'practice'" :course="selectedCourse" @notify="notify" />

      <SettingsPage
        v-else-if="route === 'settings'"
        :loading="loadingModelSettings"
        :saving="savingSettings"
        :settings="settings"
        @reload="loadModelSettings"
        @save="handleSaveModelSettings"
      />

      <HelpPage v-else :api-base-url="settings.apiBaseUrl" />
    </main>

    <div class="toast-stack" aria-live="polite">
      <div v-for="toast in toasts" :key="toast.id" :class="['toast', toast.tone]">
        {{ toast.message }}
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from "vue";
import { api, loadSettings, saveSettings } from "./api";
import type { AppSettings, Course, ModelSettingsResponse, RouteKey } from "./types";
import ChatPage from "./pages/ChatPage.vue";
import HelpPage from "./pages/HelpPage.vue";
import HomePage from "./pages/HomePage.vue";
import KnowledgePage from "./pages/KnowledgePage.vue";
import PracticePage from "./pages/PracticePage.vue";
import SettingsPage from "./pages/SettingsPage.vue";

type ToastTone = "success" | "error" | "info";
type Toast = {
  id: number;
  tone: ToastTone;
  message: string;
};

const routeLabels: Record<RouteKey, string> = {
  home: "总览",
  knowledge: "知识库",
  chat: "开始对话",
  practice: "题库练习",
  settings: "设置",
  help: "说明"
};

const mainRoutes: RouteKey[] = ["home", "knowledge", "chat", "practice"];
const validRoutes = new Set<RouteKey>(["home", "knowledge", "chat", "practice", "settings", "help"]);

const route = ref<RouteKey>(getRouteFromHash());
const settings = ref<AppSettings>(loadSettings());
const courses = ref<Course[]>([]);
const courseId = ref<number | null>(loadSelectedCourseId());
const loadingCourses = ref(false);
const courseError = ref("");
const loadingModelSettings = ref(false);
const savingSettings = ref(false);
const toasts = ref<Toast[]>([]);

const selectedCourse = computed(() => courses.value.find((course) => course.id === courseId.value) ?? null);

onMounted(() => {
  window.addEventListener("hashchange", syncRoute);
  if (!window.location.hash) {
    setHashRoute("home");
  }
  void loadModelSettings();
  void loadCourses();
});

onUnmounted(() => {
  window.removeEventListener("hashchange", syncRoute);
});

function getRouteFromHash(): RouteKey {
  const nextRoute = window.location.hash.replace(/^#\/?/, "") as RouteKey;
  return validRoutes.has(nextRoute) ? nextRoute : "home";
}

function setHashRoute(nextRoute: RouteKey) {
  window.location.hash = nextRoute === "home" ? "#/" : `#/${nextRoute}`;
}

function syncRoute() {
  route.value = getRouteFromHash();
}

function navigate(nextRoute: RouteKey) {
  setHashRoute(nextRoute);
}

function loadSelectedCourseId() {
  const raw = window.localStorage.getItem("rag-study-assistant:selected-course");
  return raw ? Number(raw) : null;
}

function onCourseChange(event: Event) {
  const value = (event.target as HTMLSelectElement).value;
  changeCourse(value ? Number(value) : null);
}

function changeCourse(nextCourseId: number | null) {
  courseId.value = nextCourseId;
  if (nextCourseId) {
    window.localStorage.setItem("rag-study-assistant:selected-course", String(nextCourseId));
  } else {
    window.localStorage.removeItem("rag-study-assistant:selected-course");
  }
}

async function loadCourses() {
  loadingCourses.value = true;
  courseError.value = "";
  try {
    const nextCourses = await api.listCourses();
    courses.value = nextCourses;

    if (courseId.value && nextCourses.some((course) => course.id === courseId.value)) {
      return;
    }

    const nextId = nextCourses[0]?.id ?? null;
    changeCourse(nextId);
  } catch (error) {
    courseError.value = error instanceof Error ? error.message : "课程加载失败";
  } finally {
    loadingCourses.value = false;
  }
}

async function handleCourseCreated() {
  await loadCourses();
  notify("success", "课程已创建");
}

async function handleCourseDeleted() {
  await loadCourses();
  notify("success", "课程已删除");
}

async function handleCourseUpdated() {
  await loadCourses();
  notify("success", "课程信息已更新");
}

async function loadModelSettings() {
  loadingModelSettings.value = true;
  try {
    const modelSettings = await api.getModelSettings();
    settings.value = mergeModelSettings(settings.value, modelSettings);
    saveSettings(settings.value);
  } catch (error) {
    if (route.value === "settings") {
      notify("error", error instanceof Error ? error.message : "模型配置加载失败");
    }
  } finally {
    loadingModelSettings.value = false;
  }
}

async function handleSaveModelSettings(nextSettings: AppSettings) {
  savingSettings.value = true;
  settings.value = { ...nextSettings };
  saveSettings(nextSettings);

  try {
    const modelSettings = await api.updateModelSettings(nextSettings);
    settings.value = mergeModelSettings(nextSettings, modelSettings);
    saveSettings(settings.value);
    notify("success", "设置已保存，后端会立即使用新的模型配置");
    void loadCourses();
  } catch (error) {
    notify("error", error instanceof Error ? error.message : "设置保存失败");
  } finally {
    savingSettings.value = false;
  }
}

function mergeModelSettings(base: AppSettings, modelSettings: ModelSettingsResponse): AppSettings {
  return {
    ...base,
    llmProvider: modelSettings.llmProvider,
    llmBaseUrl: modelSettings.llmBaseUrl,
    llmModel: modelSettings.llmModel,
    llmApiKey: "",
    llmApiKeySet: modelSettings.llmApiKeySet,
    clearLlmApiKey: false,
    embeddingProvider: modelSettings.embeddingProvider,
    embeddingBaseUrl: modelSettings.embeddingBaseUrl,
    embeddingModel: modelSettings.embeddingModel,
    embeddingApiKey: "",
    embeddingApiKeySet: modelSettings.embeddingApiKeySet,
    clearEmbeddingApiKey: false
  };
}

function handleSaveSettings(nextSettings: AppSettings) {
  saveSettings(nextSettings);
  settings.value = nextSettings;
  notify("success", "设置已保存");
  void loadCourses();
}

function notify(tone: ToastTone, message: string) {
  const id = Date.now() + Math.random();
  toasts.value.push({ id, tone, message });
  window.setTimeout(() => {
    toasts.value = toasts.value.filter((toast) => toast.id !== id);
  }, 3600);
}
</script>
