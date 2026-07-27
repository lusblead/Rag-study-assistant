<template>
  <div class="empty">
    <strong>{{ title }}</strong>
    <span v-if="text">{{ text }}</span>
  </div>
</template>

<script setup lang="ts">
defineProps<{
  title: string;
  text?: string;
}>();
</script>
