<template>
  <span :class="['status', tone]">{{ status || "UNKNOWN" }}</span>
</template>

<script setup lang="ts">
import { computed } from "vue";

const props = defineProps<{
  status?: string;
}>();

const tone = computed(() => {
  if (props.status === "PARSED") return "ok";
  if (props.status === "FAILED") return "bad";
  if (props.status === "PARSING") return "busy";
  return "idle";
});
</script>
