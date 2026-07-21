<template>
  <div v-if="report" class="safety-report">
    <!-- 头部 -->
    <div class="sr-header">
      <div class="sr-score" :class="scoreClass">
        <span class="sr-score-value">{{ report.score }}</span>
        <span class="sr-score-label">分</span>
      </div>
      <div class="sr-meta">
        <div class="sr-summary">{{ report.summary }}</div>
        <div class="sr-file" v-if="report.reviewedFile">
          📄 {{ report.reviewedFile.split('/').pop() }}
          <span v-if="report.startLine">:{{ report.startLine }}-{{ report.endLine }}</span>
        </div>
        <div class="sr-tag" :class="report.passed ? 'tag-pass' : 'tag-fail'">
          {{ report.passed ? '✅ 通过' : '❌ 未通过' }}
        </div>
      </div>
    </div>

    <!-- 问题列表 -->
    <div class="sr-issues">
      <div v-for="(issue, i) in report.issues" :key="i" class="sr-issue" :class="'sev-' + issue.severity">
        <div class="si-top">
          <span class="si-badge" :class="issue.severity">{{ severityLabel(issue.severity) }}</span>
          <span class="si-dimension">{{ dimensionLabel(issue.dimension) }}</span>
          <span v-if="issue.lineRef" class="si-line">{{ issue.lineRef }}</span>
        </div>
        <div class="si-title">{{ issue.title }}</div>
        <div class="si-desc">{{ issue.description }}</div>
        <div v-if="issue.fix" class="si-fix">
          <span class="fix-label">修复建议：</span>{{ issue.fix }}
        </div>
        <div v-if="issue.codeSnippet" class="si-code">
          <pre>{{ issue.codeSnippet }}</pre>
        </div>
      </div>
    </div>

    <!-- 无问题 -->
    <div v-if="report.issues && report.issues.length === 0" class="sr-clean">
      🎉 未发现安全问题
    </div>
  </div>

  <!-- 加载态 -->
  <div v-else-if="loading" class="sr-loading">
    <div class="loading-spinner"></div>
    <span>正在审查代码...</span>
  </div>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({
  report: { type: Object, default: null },
  loading: { type: Boolean, default: false }
})

const scoreClass = computed(() => {
  if (!props.report) return ''
  if (props.report.score >= 80) return 'score-good'
  if (props.report.score >= 50) return 'score-mid'
  return 'score-bad'
})

function severityLabel(s) {
  const map = { critical: '🔴 CRITICAL', major: '🟠 MAJOR', minor: '🟡 MINOR' }
  return map[s] || s
}

function dimensionLabel(d) {
  const map = {
    threading: '线程并发', database: '数据库', security: '安全',
    resource: '资源管理', cost: '成本', boundary: '边界处理', logging: '日志'
  }
  return map[d] || d
}
</script>

<style scoped>
.safety-report {
  background: #fff;
  border: 1px solid #e8e3d8;
  border-radius: 10px;
  overflow: hidden;
  margin-top: 8px;
}
.sr-header {
  display: flex;
  gap: 16px;
  padding: 16px;
  background: #faf9f5;
  border-bottom: 1px solid #e8e3d8;
}
.sr-score {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  width: 64px;
  height: 64px;
  border-radius: 50%;
  flex-shrink: 0;
}
.sr-score.score-good { background: #e8f5e9; color: #2e7d32; }
.sr-score.score-mid { background: #fff3e0; color: #e65100; }
.sr-score.score-bad { background: #fce4ec; color: #c62828; }
.sr-score-value { font-size: 22px; font-weight: 700; line-height: 1; }
.sr-score-label { font-size: 10px; opacity: 0.8; }
.sr-meta { flex: 1; }
.sr-summary { font-size: 13px; color: #2d2a24; font-weight: 500; margin-bottom: 4px; }
.sr-file { font-size: 12px; color: #8a857a; margin-bottom: 4px; }
.sr-tag { font-size: 11px; padding: 2px 8px; border-radius: 4px; display: inline-block; }
.tag-pass { background: #e8f5e9; color: #2e7d32; }
.tag-fail { background: #fce4ec; color: #c62828; }

.sr-issues { padding: 0; }
.sr-issue {
  padding: 12px 16px;
  border-bottom: 1px solid #f0ece4;
}
.sr-issue:last-child { border-bottom: none; }
.si-top { display: flex; align-items: center; gap: 6px; margin-bottom: 4px; flex-wrap: wrap; }
.si-badge {
  font-size: 10px; font-weight: 700; padding: 1px 6px; border-radius: 3px;
}
.si-badge.critical { background: #fce4ec; color: #c62828; }
.si-badge.major { background: #fff3e0; color: #e65100; }
.si-badge.minor { background: #e3f2fd; color: #1565c0; }
.si-dimension { font-size: 11px; color: #8a857a; }
.si-line { font-size: 11px; color: #5a5548; background: #f0ece4; padding: 1px 5px; border-radius: 3px; margin-left: auto; }
.si-title { font-size: 13px; font-weight: 600; color: #2d2a24; margin-bottom: 2px; }
.si-desc { font-size: 12px; color: #5a5548; line-height: 1.5; }
.si-fix { font-size: 12px; color: #2e7d32; margin-top: 4px; }
.fix-label { font-weight: 500; }
.si-code { margin-top: 6px; }
.si-code pre {
  font-size: 11px; background: #f8f6f0; padding: 8px; border-radius: 6px;
  overflow-x: auto; margin: 0;
}

.sr-clean { text-align: center; padding: 24px; font-size: 14px; color: #2e7d32; }
.sr-loading { display: flex; align-items: center; gap: 8px; padding: 24px; color: #8a857a; justify-content: center; }
.loading-spinner {
  width: 16px; height: 16px;
  border: 2px solid #e8e3d8;
  border-top-color: #c15f3c;
  border-radius: 50%;
  animation: spin 0.8s linear infinite;
}
@keyframes spin { to { transform: rotate(360deg); } }
</style>
