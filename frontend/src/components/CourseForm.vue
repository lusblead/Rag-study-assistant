<template>
  <form class="course-form" @submit.prevent="submit">
    <input v-model="name" placeholder="课程名称" />
    <input v-model="term" placeholder="学期，例如 2026春" />
    <textarea v-model="description" placeholder="课程描述" rows="3" />
    <button :disabled="busy || !name.trim()" type="submit">新增课程</button>
  </form>
</template>

<script setup lang="ts">
import { ref } from "vue";
import { api } from "../api";

const emit = defineEmits<{
  created: [];
  error: [message: string];
}>();

const name = ref("");
const term = ref("");
const description = ref("");
const busy = ref(false);

async function submit() {
  if (!name.value.trim()) return;
  busy.value = true;
  try {
    await api.createCourse({
      name: name.value.trim(),
      term: term.value.trim(),
      description: description.value.trim()
    });
    name.value = "";
    term.value = "";
    description.value = "";
    emit("created");
  } catch (error) {
    emit("error", error instanceof Error ? error.message : "课程创建失败");
  } finally {
    busy.value = false;
  }
}
</script>
