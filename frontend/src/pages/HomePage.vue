<template>
  <section class="page-stack">
    <div class="section-heading">
      <div>
        <p class="eyebrow">当前演示课程</p>
        <h1>{{ course ? course.name : loadingCourses ? "正在加载课程" : "先创建或选择一门课程" }}</h1>
        <p>
          {{
            course?.description ||
            "前端围绕课程组织知识库、对话历史、题库练习和 Agent 调用，便于课堂展示完整流程。"
          }}
        </p>
      </div>
    </div>

    <div class="module-grid two">
      <button class="module-card" type="button" @click="emit('navigate', 'knowledge')">
        <span class="module-icon">KB</span>
        <strong>知识库管理</strong>
        <small>上传并处理课程文档，查看解析状态、切片数量，触发 Agent 入库或删除资料。</small>
      </button>
      <button class="module-card" type="button" @click="emit('navigate', 'chat')">
        <span class="module-icon">AI</span>
        <strong>开始对话</strong>
        <small>基于已入库文档进行 RAG 答疑，支持历史会话、流式输出和引用片段查看。</small>
      </button>
    </div>

    <div class="module-grid">
      <button class="module-card compact" type="button" @click="emit('navigate', 'practice')">
        <strong>题库练习</strong>
        <small>调用 Agent 生成题目，提交答案并查看练习记录和错题。</small>
      </button>
      <button class="module-card compact" type="button" @click="emit('navigate', 'settings')">
        <strong>模型与接口设置</strong>
        <small>维护前端 API 地址，并保存演示用模型、Key 和 .env 片段。</small>
      </button>
    </div>

    <Panel v-if="!course" title="创建第一门课程">
      <CourseForm @created="emit('course-created')" @error="(message) => emit('notify', 'error', message)" />
    </Panel>
  </section>
</template>

<script setup lang="ts">
import CourseForm from "../components/CourseForm.vue";
import Panel from "../components/Panel.vue";
import type { Course, RouteKey } from "../types";

defineProps<{
  course: Course | null;
  courses: Course[];
  loadingCourses: boolean;
}>();

const emit = defineEmits<{
  navigate: [route: RouteKey];
  "course-created": [];
  notify: [tone: "success" | "error" | "info", message: string];
}>();
</script>
