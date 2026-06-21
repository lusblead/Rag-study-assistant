<template>
  <section class="page-stack">
    <div class="section-heading tight">
      <div>
        <p class="eyebrow">说明</p>
        <h1>演示流程</h1>
        <p>后端地址：{{ apiBaseUrl }}</p>
      </div>
    </div>

    <div class="help-grid">
      <Panel title="1. 创建课程">
        <p>在知识库页面创建课程，后续文档、会话和题库都按课程隔离。</p>
      </Panel>
      <Panel title="2. 上传并入库">
        <p>上传课程资料，勾选自动入库会调用 Agent 文档解析接口，完成切片、Embedding 和向量写入。</p>
      </Panel>
      <Panel title="3. RAG 对话">
        <p>进入开始对话，选择课程后提问。流式模式会展示模型增量回答，并保留会话历史。</p>
      </Panel>
      <Panel title="4. 出题练习">
        <p>通过 Agent 生成题目并保存到题库，提交答案后可查看判分结果、练习记录和错题数量。</p>
      </Panel>
    </div>
  </section>
</template>

<script setup lang="ts">
import Panel from "../components/Panel.vue";

defineProps<{
  apiBaseUrl: string;
}>();
</script>
