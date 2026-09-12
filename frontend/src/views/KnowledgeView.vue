<script setup lang="ts">
// 阶段0 占位页。阶段3（大脑搭建 · RAG 知识库）在此实现：
//   文档上传（PDF/Word/TXT 解析）→ 文本分块 → nomic-embed-text 向量化 → 存入 pgvector
//   问答：向量检索 TopK → 拼接 Prompt → 调用大模型生成答案
</script>

<template>
  <div class="page">
    <h2 class="page-title">知识库</h2>

    <el-alert
      type="info"
      :closable="false"
      show-icon
      title="阶段3 交付"
      description="RAG 知识库（文档上传 → 向量入库 → 检索问答）落地后，此页实现知识库管理界面；当前为占位页。"
    />
  </div>
</template>

<style scoped>
.page {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.page-title {
  margin: 0;
  font-size: 18px;
}
</style>
