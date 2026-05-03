<template>
  <div class="plan-root">

    <header class="top-bar">
      <button @click="goBack" class="back-link">
        <svg width="16" height="16" viewBox="0 0 16 16" fill="none">
          <path d="M10 3L5 8L10 13" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"/>
        </svg>
        Schedule
      </button>
      <div class="exam-tag">{{ examName }}</div>
    </header>

    <div class="page-header">
      <h1>Study Plan</h1>
      <p class="page-sub">{{ courseId }}</p>
    </div>

    <main class="content">

      <!-- Events timeline -->
      <section class="section">
        <h2 class="section-title">Calendar Sessions</h2>

        <div v-if="isLoading" class="state-block">
          <div class="scan-bar"></div>
          <p class="state-label">Loading plan&hellip;</p>
        </div>

        <div v-else-if="error" class="state-block error-block">
          <p>{{ error }}</p>
        </div>

        <div v-else-if="studyEvents.length === 0" class="state-block">
          <p class="state-label">No sessions found.</p>
        </div>

        <div v-else class="timeline">
          <div
            v-for="(event, i) in studyEvents"
            :key="i"
            class="event-row"
            :class="{ 'is-celebration': isCelebration(event) }"
          >
            <div class="event-date-col">
              <span class="ev-date">{{ formatDate(event.start?.dateTime) }}</span>
              <span class="ev-time">{{ formatTime(event.start?.dateTime) }} – {{ formatTime(event.end?.dateTime) }}</span>
            </div>
            <div class="event-spine"></div>
            <div class="event-body">
              <h3 class="event-title">{{ event.summary || 'Study Session' }}</h3>
              <p class="event-desc">{{ event.description }}</p>
              <span v-if="isCelebration(event)" class="celebration-chip">Celebration</span>
            </div>
          </div>
        </div>
      </section>

      <!-- Study guides -->
      <section class="section guides-section" v-if="!isLoading && studyEvents.length > 0">
        <div class="guides-header">
          <h2 class="section-title">Study Guides</h2>
          <div class="agent-status" :class="statusClass">
            <div v-if="isGenerating" class="pulse-dot"></div>
            <span>{{ statusLabel }}</span>
          </div>
        </div>

        <!-- Per-guide progress list -->
        <div class="guide-progress-list" v-if="isGenerating || guideItems.length > 0">
          <div
            v-for="item in guideItems"
            :key="item.name"
            class="guide-progress-row"
            :class="item.status"
            @click="item.status === 'ready' && (activeGuide = item)"
          >
            <div class="guide-row-indicator">
              <span v-if="item.status === 'ready'" class="dot ready-dot"></span>
              <span v-else-if="item.status === 'generating'" class="dot gen-dot"></span>
              <span v-else class="dot pending-dot"></span>
            </div>
            <span class="guide-row-name">{{ item.name }}</span>
            <span class="guide-row-badge" :class="item.status">
              {{ item.status === 'ready' ? 'Ready' : item.status === 'generating' ? 'Generating…' : 'Queued' }}
            </span>
          </div>
        </div>

        <!-- Active guide reader -->
        <div class="guide-reader" v-if="activeGuide">
          <div class="reader-header">
            <span class="reader-title">{{ activeGuide.name }}</span>
            <button @click="activeGuide = null" class="close-btn">
              <svg width="14" height="14" viewBox="0 0 14 14" fill="none">
                <path d="M2 2L12 12M12 2L2 12" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"/>
              </svg>
            </button>
          </div>
          <div class="reader-body markdown-body" v-html="activeGuide.html"></div>
        </div>

        <div v-if="!isGenerating && guideItems.length === 0 && !error" class="state-block">
          <p class="state-label">No guides generated yet.</p>
        </div>

        <div v-if="generationWarning" class="warning-bar">
          <span>⚠</span> {{ generationWarning }}
        </div>
      </section>

    </main>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { marked } from 'marked';

const route = useRoute();
const router = useRouter();

const courseId = route.params.courseId;
const examName = route.params.examName;

const studyEvents = ref([]);
const isLoading = ref(true);
const error = ref(null);

// Each item: { name, status: 'pending'|'generating'|'ready', html }
const guideItems = ref([]);
const activeGuide = ref(null);
const isGenerating = ref(false);
const generationWarning = ref('');

const goBack = () => router.back();

const statusClass = computed(() => {
  if (isGenerating.value) return 'status-running';
  if (guideItems.value.every(g => g.status === 'ready')) return 'status-done';
  return 'status-idle';
});

const statusLabel = computed(() => {
  if (isGenerating.value) {
    const done = guideItems.value.filter(g => g.status === 'ready').length;
    return `${done} / ${guideItems.value.length} guides ready`;
  }
  if (guideItems.value.length > 0) return 'All guides ready';
  return 'Waiting';
});

const isCelebration = (event) =>
  event.summary?.toLowerCase().includes('celebrat') || event.eventType === 'celebration';

const formatDate = (dt) => {
  if (!dt) return 'TBD';
  try { return new Date(dt).toLocaleDateString(undefined, { weekday: 'short', month: 'short', day: 'numeric' }); }
  catch { return dt; }
};
const formatTime = (dt) => {
  if (!dt) return '--:--';
  try { return new Date(dt).toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit' }); }
  catch { return dt; }
};

/**
 * Check whether guide files already exist on the server.
 * Returns a Set of event summaries that have a cached guide.
 */
const checkExistingGuides = async (events) => {
  const cached = new Set();
  await Promise.all(events.map(async (event) => {
    try {
      const safeName = event.summary?.replace(/[\\/:*?"<>|]/g, '_') ?? '';
      const res = await fetch(
        `http://localhost:8080/api/users/courses/${encodeURIComponent(courseId)}/study_guide/guide/${encodeURIComponent(safeName)}/exists`,
        { credentials: 'include' }
      );
      if (res.ok) {
        const { exists } = await res.json();
        if (exists) cached.add(event.summary);
      }
    } catch { /* non-fatal */ }
  }));
  return cached;
};

/**
 * Fetches the actual markdown content of a cached guide.
 */
const fetchGuideContent = async (eventSummary) => {
  const safeName = eventSummary?.replace(/[\\/:*?"<>|]/g, '_') ?? '';
  const res = await fetch(
    `http://localhost:8080/api/users/courses/${encodeURIComponent(courseId)}/study_guide/guide/${encodeURIComponent(safeName)}`,
    { credentials: 'include' }
  );
  if (!res.ok) throw new Error(`Failed to fetch guide: ${safeName}`);
  return res.text();
};

/**
 * Opens the SSE stream and processes events.
 * The server sends `guide_ready` with the event name as data — we then
 * fetch the actual markdown content separately.
 */
const streamGuides = async (events) => {
  isGenerating.value = true;

  try {
    const res = await fetch(
      `http://localhost:8080/api/users/courses/${encodeURIComponent(courseId)}/study_guide/generate-guides`,
      {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        credentials: 'include',
        body: JSON.stringify(events),
      }
    );

    if (!res.ok) throw new Error('Stream connection failed');

    const reader = res.body.getReader();
    const decoder = new TextDecoder();
    let eventName = '';

    while (true) {
      const { value, done } = await reader.read();
      if (done) break;

      const lines = decoder.decode(value).split('\n');
      for (const line of lines) {
        if (line.startsWith('event:')) {
          eventName = line.replace('event:', '').trim();
        } else if (line.startsWith('data:')) {
          const data = line.replace('data:', '').trim();
          await handleSseEvent(eventName, data);
        }
      }
    }
  } catch (err) {
    console.error('Stream error:', err);
    generationWarning.value = 'Guide generation encountered an error. Some guides may be missing.';
  } finally {
    isGenerating.value = false;
  }
};

const handleSseEvent = async (name, data) => {
  switch (name) {
    case 'progress': {
      // Mark the matching guide item as currently generating
      const match = guideItems.value.find(g => data.includes(g.name));
      if (match) match.status = 'generating';
      break;
    }
    case 'guide_ready': {
      // data = event summary name. Fetch the actual content now.
      const item = guideItems.value.find(g => g.name === data);
      if (item) {
        try {
          const markdown = await fetchGuideContent(data);
          item.html = marked.parse(markdown);
          item.status = 'ready';
          // Auto-open the first guide that becomes ready
          if (!activeGuide.value) activeGuide.value = item;
        } catch {
          item.status = 'ready';
          item.html = '<p><em>Could not load guide content.</em></p>';
        }
      }
      break;
    }
    case 'warning':
      generationWarning.value = data;
      break;
    case 'error':
      generationWarning.value = data;
      break;
  }
};

const fetchStudyPlan = async () => {
  try {
    const res = await fetch(
      `http://localhost:8080/api/users/courses/${encodeURIComponent(courseId)}/study_guide/study-plan/${encodeURIComponent(examName)}`,
      { credentials: 'include' }
    );

    if (res.status === 401) {
      window.location.href = 'http://localhost:8080/oauth2/authorization/google';
      return;
    }
    if (!res.ok) throw new Error('Failed to load study plan');

    const events = await res.json();
    studyEvents.value = events;

    if (events.length === 0) return;

    // Initialise the guide item list from the event list
    guideItems.value = events.map(e => ({
      name: e.summary ?? 'Untitled',
      status: 'pending',
      html: '',
    }));

    // Check which guides are already cached before opening the stream
    const cached = await checkExistingGuides(events);

    if (cached.size > 0) {
      // Load cached guides immediately without touching the SSE endpoint
      await Promise.all(
        events
          .filter(e => cached.has(e.summary))
          .map(async (e) => {
            const item = guideItems.value.find(g => g.name === e.summary);
            if (!item) return;
            try {
              const markdown = await fetchGuideContent(e.summary);
              item.html = marked.parse(markdown);
              item.status = 'ready';
            } catch {
              item.status = 'ready';
              item.html = '<p><em>Could not load cached guide.</em></p>';
            }
          })
      );
      if (!activeGuide.value) {
        activeGuide.value = guideItems.value.find(g => g.status === 'ready') ?? null;
      }
    }

    // Only open the SSE stream if at least one guide still needs generating
    const needsGeneration = events.some(e => !cached.has(e.summary));
    if (needsGeneration) {
      streamGuides(events);
    }

  } catch (err) {
    console.error(err);
    error.value = 'Failed to load the study plan. Try generating it again.';
  } finally {
    isLoading.value = false;
  }
};

onMounted(fetchStudyPlan);
</script>

<style scoped>
.plan-root {
  min-height: 100vh;
  background: var(--bg-dark, #0d0d0f);
  color: var(--text, #e8e6e0);
  font-family: 'DM Sans', sans-serif;
  padding: 0 0 100px;
}

.top-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 20px 40px;
  border-bottom: 1px solid rgba(255,255,255,0.07);
}

.back-link {
  display: flex; align-items: center; gap: 6px;
  background: none; border: none;
  color: rgba(255,255,255,0.4);
  cursor: pointer; font-size: 0.9rem;
  transition: color 0.2s; padding: 0;
}
.back-link:hover { color: rgba(255,255,255,0.9); }

.exam-tag {
  font-size: 0.78rem; font-weight: 600;
  letter-spacing: 0.08em; text-transform: uppercase;
  color: rgba(255,255,255,0.3);
  background: rgba(255,255,255,0.05);
  border: 1px solid rgba(255,255,255,0.1);
  padding: 4px 12px; border-radius: 4px;
  max-width: 300px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}

.page-header { padding: 56px 40px 20px; }
.page-header h1 {
  font-size: 2.4rem; font-weight: 700;
  letter-spacing: -0.03em; margin: 0 0 6px; color: #fff;
}
.page-sub { margin: 0; color: rgba(255,255,255,0.3); font-size: 0.9rem; }

.content { padding: 0 40px; }

.section { margin-top: 48px; }
.section-title {
  font-size: 0.8rem; font-weight: 600;
  letter-spacing: 0.12em; text-transform: uppercase;
  color: rgba(255,255,255,0.3);
  margin: 0 0 24px;
}

/* Timeline */
.timeline { display: flex; flex-direction: column; gap: 0; }

.event-row {
  display: flex;
  align-items: stretch;
  gap: 0;
  padding: 0 0 8px;
}

.event-date-col {
  width: 130px;
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  padding: 16px 20px 16px 0;
  gap: 3px;
}
.ev-date { font-size: 0.82rem; font-weight: 600; color: rgba(255,255,255,0.7); }
.ev-time { font-size: 0.75rem; color: rgba(255,255,255,0.3); white-space: nowrap; }

.event-spine {
  width: 1px;
  background: rgba(255,255,255,0.08);
  flex-shrink: 0;
  position: relative;
}
.event-spine::before {
  content: '';
  position: absolute;
  top: 18px; left: -3px;
  width: 7px; height: 7px;
  border-radius: 50%;
  background: rgba(124,92,252,0.6);
  border: 1px solid rgba(124,92,252,0.4);
}

.event-body {
  flex: 1;
  padding: 14px 0 14px 20px;
}
.event-title {
  font-size: 0.95rem; font-weight: 600;
  color: rgba(255,255,255,0.85);
  margin: 0 0 6px;
}
.event-desc {
  font-size: 0.85rem; color: rgba(255,255,255,0.4);
  line-height: 1.55; margin: 0;
}

.celebration-chip {
  display: inline-block;
  margin-top: 8px;
  font-size: 0.72rem; font-weight: 600;
  letter-spacing: 0.06em; text-transform: uppercase;
  background: rgba(250,200,50,0.1);
  color: rgba(250,200,50,0.8);
  border: 1px solid rgba(250,200,50,0.25);
  padding: 2px 8px; border-radius: 4px;
}
.event-row.is-celebration .event-spine::before {
  background: rgba(250,200,50,0.7);
  border-color: rgba(250,200,50,0.4);
}

/* Guides section */
.guides-section { border-top: 1px solid rgba(255,255,255,0.06); padding-top: 40px; }

.guides-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 24px;
}

.agent-status {
  display: flex; align-items: center; gap: 8px;
  font-size: 0.8rem; color: rgba(255,255,255,0.4);
}
.status-running { color: rgba(180,160,255,0.9); }
.status-done { color: rgba(100,200,120,0.9); }

.pulse-dot {
  width: 8px; height: 8px; border-radius: 50%;
  background: rgba(180,160,255,0.9);
  animation: pulse 1.4s ease-in-out infinite;
}
@keyframes pulse {
  0%, 100% { opacity: 1; transform: scale(1); }
  50% { opacity: 0.5; transform: scale(0.7); }
}

.guide-progress-list {
  display: flex; flex-direction: column; gap: 2px;
  margin-bottom: 24px;
}

.guide-progress-row {
  display: flex; align-items: center; gap: 12px;
  padding: 10px 14px;
  border-radius: 8px;
  border: 1px solid transparent;
  transition: all 0.2s;
}
.guide-progress-row.ready {
  cursor: pointer;
  border-color: rgba(255,255,255,0.06);
  background: rgba(255,255,255,0.02);
}
.guide-progress-row.ready:hover {
  background: rgba(255,255,255,0.05);
  border-color: rgba(124,92,252,0.2);
}

.guide-row-indicator { width: 16px; display: flex; justify-content: center; }
.dot { width: 8px; height: 8px; border-radius: 50%; display: inline-block; }
.ready-dot { background: rgba(100,200,120,0.8); }
.gen-dot { background: rgba(180,160,255,0.9); animation: pulse 1s ease-in-out infinite; }
.pending-dot { background: rgba(255,255,255,0.15); }

.guide-row-name {
  flex: 1; font-size: 0.875rem;
  color: rgba(255,255,255,0.7);
  white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
}

.guide-row-badge {
  font-size: 0.72rem; font-weight: 600;
  letter-spacing: 0.05em; text-transform: uppercase;
  padding: 2px 8px; border-radius: 4px;
}
.guide-row-badge.ready { background: rgba(100,200,120,0.1); color: rgba(100,200,120,0.9); }
.guide-row-badge.generating { background: rgba(180,160,255,0.1); color: rgba(180,160,255,0.9); }
.guide-row-badge.pending { background: rgba(255,255,255,0.05); color: rgba(255,255,255,0.3); }

/* Guide reader */
.guide-reader {
  background: rgba(255,255,255,0.03);
  border: 1px solid rgba(255,255,255,0.1);
  border-radius: 12px;
  overflow: hidden;
}

.reader-header {
  display: flex; align-items: center; justify-content: space-between;
  padding: 14px 20px;
  border-bottom: 1px solid rgba(255,255,255,0.07);
  background: rgba(255,255,255,0.02);
}
.reader-title {
  font-size: 0.875rem; font-weight: 600;
  color: rgba(255,255,255,0.8);
  white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
  max-width: 80%;
}
.close-btn {
  background: none; border: none; cursor: pointer;
  color: rgba(255,255,255,0.3); padding: 4px;
  transition: color 0.2s;
}
.close-btn:hover { color: rgba(255,255,255,0.8); }

.reader-body { padding: 28px 32px; max-height: 600px; overflow-y: auto; }

/* Markdown styles inside reader */
.markdown-body { color: rgba(255,255,255,0.8); line-height: 1.75; font-size: 0.925rem; }
.markdown-body :deep(h1), .markdown-body :deep(h2), .markdown-body :deep(h3) {
  color: #fff; font-weight: 600; margin: 1.5em 0 0.6em; line-height: 1.3;
}
.markdown-body :deep(h1) { font-size: 1.3rem; }
.markdown-body :deep(h2) { font-size: 1.1rem; }
.markdown-body :deep(h3) { font-size: 0.95rem; }
.markdown-body :deep(p) { margin: 0 0 1em; }
.markdown-body :deep(ul), .markdown-body :deep(ol) {
  padding-left: 1.4em; margin: 0.5em 0 1em;
}
.markdown-body :deep(li) { margin-bottom: 0.35em; }
.markdown-body :deep(code) {
  background: rgba(255,255,255,0.07);
  border-radius: 4px; padding: 2px 6px;
  font-size: 0.85em; font-family: 'JetBrains Mono', monospace;
  color: rgba(180,160,255,0.9);
}
.markdown-body :deep(pre) {
  background: rgba(0,0,0,0.4); border-radius: 8px;
  padding: 16px 20px; overflow-x: auto; margin: 1em 0;
}
.markdown-body :deep(pre code) { background: none; padding: 0; }
.markdown-body :deep(blockquote) {
  border-left: 3px solid rgba(124,92,252,0.5);
  padding-left: 16px; margin: 1em 0;
  color: rgba(255,255,255,0.5);
}
.markdown-body :deep(strong) { color: #fff; font-weight: 600; }

/* Warning bar */
.warning-bar {
  margin-top: 16px;
  padding: 10px 14px;
  background: rgba(250,180,50,0.08);
  border: 1px solid rgba(250,180,50,0.2);
  border-radius: 8px;
  font-size: 0.85rem;
  color: rgba(250,180,50,0.85);
  display: flex; gap: 8px; align-items: flex-start;
}

/* Shared states */
.state-block {
  display: flex; flex-direction: column;
  align-items: center; justify-content: center;
  padding: 60px 40px; text-align: center; gap: 14px;
}
.state-label { color: rgba(255,255,255,0.3); margin: 0; font-size: 0.9rem; }
.error-block { color: rgba(255,100,80,0.8); }

.scan-bar {
  width: 160px; height: 2px;
  background: rgba(255,255,255,0.06);
  border-radius: 2px; overflow: hidden; position: relative;
}
.scan-bar::after {
  content: ''; position: absolute;
  top: 0; left: -60%; width: 60%; height: 100%;
  background: linear-gradient(90deg, transparent, var(--primary, #7c5cfc), transparent);
  animation: scan 1.4s ease-in-out infinite;
}
@keyframes scan { to { left: 100%; } }
</style>
