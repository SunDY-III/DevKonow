<template>
  <div class="knowledge-card">
    <div class="kp-header">
      <span class="kp-difficulty" :class="'level-' + (kp.difficultyLevel || 3)">L{{ kp.difficultyLevel || 3 }}</span>
      <h4 class="kp-title">{{ kp.title }}</h4>
      <span v-if="kp.patternName" class="kp-pattern">{{ kp.patternName }}</span>
    </div>
    <p v-if="kp.concept" class="kp-concept">{{ kp.concept }}</p>
    <div class="kp-meta">
      <span v-if="kp.feynmanPassCount > 0" class="kp-pass">✅ Feynman 通过 {{ kp.feynmanPassCount }} 次</span>
      <span v-if="kp.reviewCount > 0" class="kp-review">📖 复习 {{ kp.reviewCount }} 次</span>
    </div>
  </div>
</template>

<script setup>
defineProps({ kp: { type: Object, required: true } })
</script>

<style scoped>
.knowledge-card {
  background: #fff;
  border: 1px solid #e8e3d8;
  border-radius: 8px;
  padding: 12px;
  transition: box-shadow 0.2s;
}
.knowledge-card:hover { box-shadow: 0 2px 8px rgba(0,0,0,0.06); }
.kp-header { display: flex; align-items: center; gap: 6px; margin-bottom: 6px; }
.kp-difficulty {
  font-size: 10px; font-weight: 700; padding: 1px 6px; border-radius: 3px;
}
.kp-difficulty.level-1 { background: #e8f5e9; color: #2e7d32; }
.kp-difficulty.level-2 { background: #e3f2fd; color: #1565c0; }
.kp-difficulty.level-3 { background: #fff3e0; color: #e65100; }
.kp-difficulty.level-4 { background: #fce4ec; color: #c62828; }
.kp-difficulty.level-5 { background: #f3e5f5; color: #6a1b9a; }
.kp-title { font-size: 13px; font-weight: 600; color: #2d2a24; margin: 0; }
.kp-pattern { font-size: 10px; padding: 1px 5px; background: #f0ece4; border-radius: 3px; color: #5a5548; }
.kp-concept { font-size: 12px; color: #5a5548; line-height: 1.5; margin: 0 0 8px; }
.kp-meta { display: flex; gap: 12px; }
.kp-pass, .kp-review { font-size: 11px; color: #8a857a; }
</style>
