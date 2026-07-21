<template>
  <div class="references">
    <details v-for="(reference, index) in references" :key="`${reference.documentId}-${reference.chunkId}-${index}`">
      <summary>
        {{ reference.title || `片段 ${reference.chunkId}` }}
        <span v-if="typeof reference.score === 'number'">{{ reference.score.toFixed(3) }}</span>
      </summary>
      <p>{{ reference.content }}</p>
    </details>
  </div>
</template>

<script setup lang="ts">
import type { RetrievedChunk } from "../types";

defineProps<{
  references: RetrievedChunk[];
}>();
</script>
