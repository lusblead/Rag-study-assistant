<template>
  <div class="references">
    <details v-for="(reference, index) in references" :key="`${reference.documentId}-${reference.chunkId}-${index}`" @toggle="loadReference($event, reference)">
      <summary>
        <strong>[S{{ index + 1 }}]</strong>
        {{ reference.title || `片段 ${reference.chunkId}` }}
        <small v-if="reference.documentVersionId"> · 版本记录 {{ reference.documentVersionId }}</small>
        <span v-if="typeof reference.score === 'number'">{{ reference.score.toFixed(3) }}</span>
      </summary>
      <p>{{ sessionId ? (contents[reference.chunkId] || '正在检查引用版本…') : reference.content }}</p>
    </details>
  </div>
</template>

<script setup lang="ts">
import type { RetrievedChunk } from "../types";
import { ref } from "vue";
import { api } from "../api";

const props = defineProps<{
  references: RetrievedChunk[];
  sessionId?: number | null;
  courseId?: number;
}>();
const contents = ref<Record<number, string>>({});
async function loadReference(event: Event, reference: RetrievedChunk) {
  if (!(event.target as HTMLDetailsElement).open || !props.sessionId || !props.courseId) return;
  if (!reference.documentVersionId) {
    contents.value[reference.chunkId] = '旧记录未保存资料版本，无法核验原始引用。';
    return;
  }
  contents.value[reference.chunkId] = '正在检查引用版本…';
  try {
    contents.value[reference.chunkId] = (await api.sessionReference(props.sessionId, props.courseId,
      reference.chunkId, reference.documentVersionId)).content;
  } catch (error) {
    contents.value[reference.chunkId] = error instanceof Error ? error.message : '引用暂时无法读取';
  }
}
</script>
