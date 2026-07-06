<template>
  <section class="settings-page">
    <div class="section-heading tight">
      <div>
        <p class="eyebrow">设置</p>
        <h1>接口与模型配置</h1>
        <p>
          这里保存的是后端实际使用的运行时配置。保存后无需重启，新的对话、出题和文档入库请求会使用最新模型。
        </p>
      </div>
      <button class="ghost" type="button" :disabled="loading || saving" @click="emit('reload')">
        重新读取
      </button>
    </div>

    <form class="settings-grid" @submit.prevent="submit">
      <Panel title="前端连接">
        <label>
          后端 API 地址
          <input v-model="form.apiBaseUrl" placeholder="http://localhost:8080" />
        </label>
        <p class="setting-note">
          修改后会用新的地址读取和保存后端模型配置。
        </p>
      </Panel>

      <Panel title="对话与出题模型">
        <label>
          Provider
          <select v-model="form.llmProvider">
            <option value="deepseek">deepseek</option>
            <option value="openai-compatible">openai-compatible</option>
            <option value="local">local</option>
          </select>
        </label>
        <label>
          Base URL
          <input v-model="form.llmBaseUrl" placeholder="https://api.deepseek.com" />
        </label>
        <label>
          Model
          <input v-model="form.llmModel" placeholder="deepseek-v4-pro" />
        </label>
        <label>
          API Key
          <input
            v-model="form.llmApiKey"
            autocomplete="off"
            :placeholder="llmKeyPlaceholder"
            type="password"
          />
        </label>
        <label class="inline-check">
          <input v-model="form.clearLlmApiKey" :disabled="!form.llmApiKeySet" type="checkbox" />
          清空已保存的对话模型 API Key
        </label>
        <div class="test-row">
          <button class="ghost" :disabled="saving || testingTarget === 'llm'" type="button" @click="testKey('llm')">
            {{ testingTarget === "llm" ? "测试中..." : "测试对话模型 Key" }}
          </button>
          <span v-if="llmTestMessage" :class="['test-result', llmTestSuccess ? 'success' : 'error']">
            {{ llmTestMessage }}
          </span>
        </div>
      </Panel>

      <Panel title="Embedding 模型">
        <label>
          Provider
          <select v-model="form.embeddingProvider">
            <option value="openai-compatible">openai-compatible</option>
            <option value="local">local</option>
          </select>
        </label>
        <label>
          Base URL
          <input v-model="form.embeddingBaseUrl" placeholder="https://api.siliconflow.com/v1" />
        </label>
        <label>
          Model
          <input v-model="form.embeddingModel" placeholder="BAAI/bge-m3" />
        </label>
        <label>
          API Key
          <input
            v-model="form.embeddingApiKey"
            autocomplete="off"
            :placeholder="embeddingKeyPlaceholder"
            type="password"
          />
        </label>
        <label class="inline-check">
          <input
            v-model="form.clearEmbeddingApiKey"
            :disabled="!form.embeddingApiKeySet"
            type="checkbox"
          />
          清空已保存的 Embedding API Key
        </label>
        <div class="test-row">
          <button
            class="ghost"
            :disabled="saving || testingTarget === 'embedding'"
            type="button"
            @click="testKey('embedding')"
          >
            {{ testingTarget === "embedding" ? "测试中..." : "测试 Embedding Key" }}
          </button>
          <span v-if="embeddingTestMessage" :class="['test-result', embeddingTestSuccess ? 'success' : 'error']">
            {{ embeddingTestMessage }}
          </span>
        </div>
      </Panel>

      <Panel title="检索重排序（Rerank）">
        <label>
          重排序方式
          <select v-model="form.rerankProvider">
            <option value="none">不启用重排序</option>
            <option value="local">本地轻量重排序</option>
            <option value="siliconflow">SiliconFlow Rerank 模型</option>
          </select>
        </label>
        <template v-if="form.rerankProvider === 'siliconflow'">
          <label>
            Base URL
            <input v-model="form.rerankBaseUrl" placeholder="https://api.siliconflow.cn/v1" />
          </label>
          <label>
            Model
            <input v-model="form.rerankModel" placeholder="Pro/BAAI/bge-reranker-v2-m3" />
          </label>
          <label>
            API Key
            <input
              v-model="form.rerankApiKey"
              autocomplete="off"
              :placeholder="rerankKeyPlaceholder"
              type="password"
            />
          </label>
          <label class="inline-check">
            <input v-model="form.clearRerankApiKey" :disabled="!form.rerankApiKeySet" type="checkbox" />
            清空已保存的 Rerank API Key
          </label>
          <label class="inline-check">
            <input v-model="form.rerankFailOpen" type="checkbox" />
            远程重排序失败时自动回退到本地重排序
          </label>
          <div class="test-row">
            <button class="ghost" :disabled="saving || testingRerank" type="button" @click="testRerank">
              {{ testingRerank ? "测试中..." : "测试 Rerank Key" }}
            </button>
            <span v-if="rerankTestMessage" :class="['test-result', rerankTestSuccess ? 'success' : 'error']">
              {{ rerankTestMessage }}
            </span>
          </div>
        </template>
        <p class="setting-note">
          不启用时保持向量检索原顺序；本地模式不需要 API Key；SiliconFlow 模式会调用 /rerank 接口。
        </p>
      </Panel>

      <Panel title="当前配置片段">
        <textarea :value="envSnippet" readonly rows="10" />
        <p class="setting-note warning">
          如果更换 Embedding 模型，请确认新模型向量维度与当前 Milvus collection 一致；维度不同需要新 collection
          并重新入库文档。
        </p>
      </Panel>

      <div class="settings-actions">
        <span class="setting-status" aria-live="polite">
          {{ statusText }}
        </span>
        <button type="submit" :disabled="saving">
          {{ saving ? "保存中..." : "保存设置" }}
        </button>
      </div>
    </form>
  </section>
</template>

<script setup lang="ts">
import { computed, reactive, ref, watch } from "vue";
import { api } from "../api";
import Panel from "../components/Panel.vue";
import type { AppSettings, ModelSettingsTestTarget } from "../types";

const props = defineProps<{
  loading: boolean;
  saving: boolean;
  settings: AppSettings;
}>();

const emit = defineEmits<{
  reload: [];
  save: [settings: AppSettings];
}>();

const form = reactive<AppSettings>({
  ...props.settings,
  llmApiKey: "",
  clearLlmApiKey: false,
  embeddingApiKey: "",
  clearEmbeddingApiKey: false,
  rerankApiKey: "",
  clearRerankApiKey: false
});
const testingTarget = ref<ModelSettingsTestTarget | null>(null);
const llmTestMessage = ref("");
const llmTestSuccess = ref(false);
const embeddingTestMessage = ref("");
const embeddingTestSuccess = ref(false);
const testingRerank = ref(false);
const rerankTestMessage = ref("");
const rerankTestSuccess = ref(false);

watch(
  () => props.settings,
  (settings) => {
    Object.assign(form, {
      ...settings,
      llmApiKey: "",
      clearLlmApiKey: false,
      embeddingApiKey: "",
      clearEmbeddingApiKey: false,
      rerankApiKey: "",
      clearRerankApiKey: false
    });
  },
  { deep: true }
);

const llmKeyPlaceholder = computed(() =>
  form.llmApiKeySet ? "已设置，留空保持现有 Key" : "未设置，输入后保存"
);

const embeddingKeyPlaceholder = computed(() =>
  form.embeddingApiKeySet ? "已设置，留空保持现有 Key" : "未设置，输入后保存"
);

const rerankKeyPlaceholder = computed(() =>
  form.rerankApiKeySet ? "已设置，留空保持现有 Key" : "未设置，可填写 SiliconFlow API Key"
);

const statusText = computed(() => {
  if (props.loading) {
    return "正在读取后端模型配置...";
  }
  if (props.saving) {
    return "正在保存到后端...";
  }
  return `对话 Key：${form.llmApiKeySet ? "已设置" : "未设置"}；Embedding Key：${
    form.embeddingApiKeySet ? "已设置" : "未设置"
  }；Rerank：${form.rerankProvider === "none" ? "未启用" : form.rerankProvider}`;
});

const envSnippet = computed(() =>
  [
    `LLM_PROVIDER=${form.llmProvider}`,
    `LLM_BASE_URL=${form.llmBaseUrl}`,
    `LLM_MODEL=${form.llmModel}`,
    `LLM_API_KEY=${form.llmApiKey ? "<new key>" : form.llmApiKeySet ? "<keep current key>" : ""}`,
    "",
    `EMBEDDING_PROVIDER=${form.embeddingProvider}`,
    `EMBEDDING_BASE_URL=${form.embeddingBaseUrl}`,
    `EMBEDDING_MODEL=${form.embeddingModel}`,
    `EMBEDDING_API_KEY=${
      form.embeddingApiKey ? "<new key>" : form.embeddingApiKeySet ? "<keep current key>" : ""
    }`,
    "",
    `RERANK_PROVIDER=${form.rerankProvider}`,
    `RERANK_BASE_URL=${form.rerankBaseUrl}`,
    `RERANK_MODEL=${form.rerankModel}`,
    `RERANK_API_KEY=${form.rerankApiKey ? "<new key>" : form.rerankApiKeySet ? "<keep current key>" : ""}`
  ].join("\n")
);

function submit() {
  emit("save", { ...form });
}

async function testKey(target: ModelSettingsTestTarget) {
  testingTarget.value = target;
  setTestResult(target, false, "");
  try {
    const result = await api.testModelSettings(target, { ...form });
    setTestResult(
      target,
      result.success,
      `${result.success ? "成功" : "失败"}：${result.message || "无返回信息"}（${result.latencyMs}ms${
        result.statusCode ? `，HTTP ${result.statusCode}` : ""
      }）`
    );
  } catch (error) {
    setTestResult(target, false, error instanceof Error ? error.message : "测试失败");
  } finally {
    testingTarget.value = null;
  }
}

function setTestResult(target: ModelSettingsTestTarget, success: boolean, message: string) {
  if (target === "llm") {
    llmTestSuccess.value = success;
    llmTestMessage.value = message;
    return;
  }
  embeddingTestSuccess.value = success;
  embeddingTestMessage.value = message;
}

async function testRerank() {
  testingRerank.value = true;
  rerankTestMessage.value = "";
  try {
    await api.testRerankSettings({ ...form });
    rerankTestSuccess.value = true;
    rerankTestMessage.value = form.rerankProvider === "siliconflow" ? "连接成功" : "当前模式无需远程连接";
  } catch (error) {
    rerankTestSuccess.value = false;
    rerankTestMessage.value = error instanceof Error ? error.message : "测试失败";
  } finally {
    testingRerank.value = false;
  }
}
</script>
