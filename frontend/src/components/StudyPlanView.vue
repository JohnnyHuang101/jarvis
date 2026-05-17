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

      <!-- ─── Events timeline ─────────────────────────────────────────────── -->
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

      <!-- ─── Study guides ────────────────────────────────────────────────── -->
      <section class="section guides-section" v-if="!isLoading && studyEvents.length > 0">
        <div class="guides-header">
          <h2 class="section-title">Study Guides</h2>
          <div class="agent-status" :class="statusClass">
            <div v-if="isGenerating" class="pulse-dot"></div>
            <span>{{ statusLabel }}</span>
          </div>
        </div>

        <div class="guide-progress-list" v-if="isGenerating || guideItems.length > 0">
          <div
            v-for="item in guideItems"
            :key="item.name"
            class="guide-progress-row"
            :class="item.status"
            @click="item.status === 'ready' && (activeGuide = item)"
          >
            <div class="guide-row-indicator">
              <span v-if="item.status === 'ready'"      class="dot ready-dot"></span>
              <span v-else-if="item.status === 'reranking'" class="dot rerank-dot"></span>
              <span v-else-if="item.status === 'cooling'"   class="dot cool-dot"></span>
              <span v-else-if="item.status === 'generating'" class="dot gen-dot"></span>
              <span v-else class="dot pending-dot"></span>
            </div>
            <span class="guide-row-name">{{ item.name }}</span>
            <span class="guide-row-badge" :class="item.status">
              {{ item.status === 'ready' ? 'Ready'
                : item.status === 'generating' ? 'Generating…'
                : 'Queued' }}
            </span>
          </div>
        </div>

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

      <!-- ─── HITL Approval section ────────────────────────────────────────
           Visible only after all guides are ready.
           Phases: idle → validating → review → executing → done
      ──────────────────────────────────────────────────────────────────── -->
      <section
        class="section approval-section"
        v-if="allGuidesReady && approvalPhase !== 'hidden'"
      >
        <div class="approval-header">
          <h2 class="section-title">Push to Calendar</h2>
          <div class="approval-phase-badge" :class="approvalPhase">
            {{ approvalPhaseBadgeLabel }}
          </div>
        </div>

        <!-- ── IDLE: prompt to kick off the agent ─────────────────────── -->
        <div v-if="approvalPhase === 'idle'" class="approval-idle">
          <p class="approval-explainer">
            The validator agent will check every session for calendar conflicts,
            ensure all guides are mapped, verify temporal order, and flag burnout
            days — then present a diff for your review before writing anything.
          </p>
          <button class="btn-primary" @click="startValidation">
            <svg width="16" height="16" viewBox="0 0 16 16" fill="none">
              <path d="M2 8h12M9 3l5 5-5 5" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/>
            </svg>
            Validate &amp; Review
          </button>
        </div>

        <!-- ── VALIDATING: agent progress stream ─────────────────────── -->
        <div v-else-if="approvalPhase === 'validating'" class="approval-progress">
          <div class="agent-progress-header">
            <div class="pulse-dot large-pulse"></div>
            <span>Validator agent running…</span>
          </div>
          <div class="agent-step-list">
            <div
              v-for="step in validationSteps"
              :key="step.id"
              class="agent-step"
              :class="step.done ? 'step-done' : 'step-active'"
            >
              <svg v-if="step.done" class="step-check" width="14" height="14" viewBox="0 0 14 14" fill="none">
                <path d="M2.5 7L5.5 10L11.5 4" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/>
              </svg>
              <div v-else class="step-spinner"></div>
              <span>{{ step.message }}</span>
            </div>
          </div>
        </div>

        <!-- ── REVIEW: show diff, approve / reject ───────────────────── -->
        <div v-else-if="approvalPhase === 'review'" class="approval-review">

          <div class="changes-panel" v-if="changeSummary.length > 0">
            <p class="changes-label">Agent made {{ changeSummary.length }} change(s)</p>
            <ul class="changes-list">
              <li v-for="(line, i) in changeSummary" :key="i">{{ line }}</li>
            </ul>
          </div>
          <div class="changes-panel no-changes" v-else>
            <p class="changes-label">No changes needed — schedule looks clean.</p>
          </div>

          <!-- Validated event preview -->
          <div class="validated-events" v-if="validatedEvents.length > 0">
            <p class="changes-label">Validated schedule ({{ validatedEvents.length }} events)</p>
            <div class="val-event-list">
              <div
                v-for="(ev, i) in validatedEvents"
                :key="i"
                class="val-event-row"
                :class="ev.type"
              >
                <span class="val-ev-type-dot" :class="ev.type"></span>
                <div class="val-ev-info">
                  <span class="val-ev-summary">{{ ev.summary }}</span>
                  <span class="val-ev-time">
                    {{ formatDate(ev.start.dateTime) }} · {{ formatTime(ev.start.dateTime) }}
                  </span>
                </div>
                <span class="val-ev-type-chip" :class="ev.type">{{ ev.eventType }}</span>
              </div>
            </div>
          </div>

          <div class="review-actions">
            <button class="btn-approve" @click="approveSchedule" :disabled="approvalExecuting">
              <svg width="16" height="16" viewBox="0 0 16 16" fill="none">
                <path d="M2 8l4 4 8-8" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/>
              </svg>
              {{ approvalExecuting ? 'Adding to Calendar…' : 'Approve & Push to Calendar' }}
            </button>
            <button class="btn-reject" @click="rejectSchedule" :disabled="approvalExecuting">
              Reject
            </button>
          </div>

          <p class="approval-expires">
            Approval expires {{ formatApprovalExpiry(approvalExpiresAt) }}.
            You can leave this page and return.
          </p>
        </div>

        <!-- ── EXECUTING: waiting for Calendar API inserts ───────────── -->
        <div v-else-if="approvalPhase === 'executing'" class="approval-executing">
          <div class="pulse-dot large-pulse"></div>
          <span>Adding {{ validatedEvents.length }} events to Google Calendar…</span>
        </div>

        <!-- ── DONE ───────────────────────────────────────────────────── -->
        <div v-else-if="approvalPhase === 'done'" class="approval-done">
          <div class="done-icon">
            <svg width="32" height="32" viewBox="0 0 32 32" fill="none">
              <circle cx="16" cy="16" r="14" stroke="rgba(100,200,120,0.5)" stroke-width="1.5"/>
              <path d="M9 16l5 5 9-9" stroke="rgba(100,200,120,0.9)" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>
            </svg>
          </div>
          <p class="done-label">
            {{ createdCount }} event{{ createdCount !== 1 ? 's' : '' }} added to Google Calendar.
          </p>
          <div class="done-errors" v-if="executionErrors.length > 0">
            <p class="changes-label">{{ executionErrors.length }} event(s) failed to insert:</p>
            <ul class="changes-list error-list">
              <li v-for="(e, i) in executionErrors" :key="i">{{ e }}</li>
            </ul>
          </div>
        </div>

        <!-- ── REJECTED ───────────────────────────────────────────────── -->
        <div v-else-if="approvalPhase === 'rejected'" class="approval-rejected">
          <p class="state-label">Schedule rejected — nothing was written to your calendar.</p>
          <button class="btn-secondary" @click="approvalPhase = 'idle'">Start over</button>
        </div>

        <div v-if="approvalError" class="warning-bar">
          <span>⚠</span> {{ approvalError }}
        </div>
      </section>

    </main>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { marked } from 'marked';

const route  = useRoute();
const router = useRouter();

const courseId = route.params.courseId;
const examName = route.params.examName;

// ── Existing state ────────────────────────────────────────────────────────────
const studyEvents       = ref([]);
const isLoading         = ref(true);
const error             = ref(null);
const guideItems        = ref([]);
const activeGuide       = ref(null);
const isGenerating      = ref(false);
const generationWarning = ref('');

// ── Approval / HITL state ─────────────────────────────────────────────────────
// Phases: 'hidden' | 'idle' | 'validating' | 'review' | 'executing' | 'done' | 'rejected'
const approvalPhase    = ref('hidden');    // hidden until guides are ready
const approvalId       = ref(null);
const approvalExpiresAt = ref(null);
const changeSummary    = ref([]);
const validatedEvents  = ref([]);
const validationSteps  = ref([]);         // live agent progress steps
const approvalError    = ref('');
const approvalExecuting = ref(false);
const createdCount     = ref(0);
const executionErrors  = ref([]);

// ── Computed ──────────────────────────────────────────────────────────────────
const goBack = () => router.back();

const allGuidesReady = computed(() =>
  guideItems.value.length > 0 && guideItems.value.every(g => g.status === 'ready')
);

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

const approvalPhaseBadgeLabel = computed(() => ({
  idle:       'Ready to validate',
  validating: 'Agent running…',
  review:     'Awaiting your review',
  executing:  'Pushing to calendar…',
  done:       'Done',
  rejected:   'Rejected',
}[approvalPhase.value] ?? ''));


// Helper so both call-sites stay DRY
const surfaceApproval = async () => {
  const resumed = await checkExistingApproval();
  if (!resumed) approvalPhase.value = 'idle';
};

// ── Check for a previously-created approval that's still pending ──────────────
// Called after guides are confirmed ready so the approval section is visible.
const checkExistingApproval = async () => {
  try {
    const res = await fetch(
      `http://localhost:8080/api/users/courses/${encodeURIComponent(courseId)}/approvals/pending`,
      { credentials: 'include' }
    );

    if (res.status === 204) return false;   // nothing pending — stay idle
    if (!res.ok) return false;

    const state = await res.json();
    approvalId.value = state.approvalId;

    // Rehydrate the review screen without re-running the agent
    await loadApprovalState(state.approvalId);
    return true;
  } catch {
    return false; // non-fatal — fall through to idle
  }
};

// ── HITL Phase 1 — kick off the validation agent via SSE ─────────────────────
const startValidation = async () => {
  approvalPhase.value   = 'validating';
  approvalError.value   = '';
  validationSteps.value = [];

  // Keep track of the currently active step so we can mark it "done" when the next one arrives
  let activeStepIdx = -1;

  const addStep = (message) => {
    validationSteps.value.push({ id: Date.now(), message, done: false });
    return validationSteps.value.length - 1;
  };
  
  const completeStep = (idx) => {
    if (validationSteps.value[idx]) validationSteps.value[idx].done = true;
  };

  activeStepIdx = addStep('Connecting to validator agent…');

  try {
    const availableGuides = guideItems.value
      .filter(g => g.status === 'ready')
      .map(g => g.name);

    const res = await fetch(
      `http://localhost:8080/api/users/courses/${encodeURIComponent(courseId)}/validate`,
      {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        credentials: 'include',
        body: JSON.stringify({
          events: studyEvents.value,
          availableGuides,
        }),
      }
    );

    if (res.status === 401) {
      window.location.href = 'http://localhost:8080/oauth2/authorization/google';
      return; 
    }

    if (!res.ok || !res.body) {
      throw new Error(`Validation request failed (${res.status})`);
    }

    // Complete the "Connecting..." step
    completeStep(activeStepIdx);

    // ── Parse the SSE stream ──────────────────────────
    const reader  = res.body.getReader();
    const decoder = new TextDecoder();
    let buf             = '';
    let lastEventName   = 'status';
    let streamDone      = false;

    while (!streamDone) {
      const { done, value } = await reader.read();
      if (done) break;

      buf += decoder.decode(value, { stream: true });
      const lines = buf.split('\n');
      buf = lines.pop();

      for (const line of lines) {
        if (line.startsWith('event:')) {
          lastEventName = line.slice(6).trim();
        } else if (line.startsWith('data:')) {
          try {
            const payload = JSON.parse(line.slice(5).trim());

            // 1. Handle Status Events
            if (lastEventName === 'status') {
              const stepLabels = {
                INIT:        'Initializing validator…',
                AGENT_START: 'Agent calling validation tools…',
              };
              const label = stepLabels[payload.step] ?? payload.message ?? payload.step;
              
              if (activeStepIdx !== -1) completeStep(activeStepIdx);
              activeStepIdx = addStep(label);
            }

            // 2. Handle the NEW Agent Step Events
            if (lastEventName === 'agent_step') {
              // Mark the previous tool/step as complete
              if (activeStepIdx !== -1) completeStep(activeStepIdx);
              
              // Add the new step from the agent's message (e.g. "Executing tool: batch_mutate_events")
              activeStepIdx = addStep(payload.message);
            }

            // 3. Handle Completion
            if (lastEventName === 'complete') {
              if (activeStepIdx !== -1) completeStep(activeStepIdx); // Finish the final step
              
              approvalId.value  = payload.approvalId;
              streamDone        = true;

              await loadApprovalState(payload.approvalId);
            }

            // 4. Handle Errors
            if (lastEventName === 'error') {
              if (activeStepIdx !== -1) completeStep(activeStepIdx);
              throw new Error(payload.message ?? 'Agent error');
            }
            
          } catch (parseErr) {
            if (parseErr.message === 'Agent error') {
              throw parseErr;
            }
          }
          lastEventName = 'status'; // Reset for next event block
        }
      }
    }
  } catch (err) {
    console.error('[validation]', err);
    approvalError.value = err.message ?? 'Validation failed.';
    approvalPhase.value = 'idle'; 
  }
};

// ── Load the persisted ApprovalState after the agent finishes ─────────────────
// This also works if the user returns to the page hours/days later:
// just call loadApprovalState(knownApprovalId) and the review screen rehydrates.
const loadApprovalState = async (id) => {
  try {
    const res = await fetch(
      `http://localhost:8080/api/users/courses/${encodeURIComponent(courseId)}/approvals/${encodeURIComponent(id)}`,
      { credentials: 'include' }
    );
    if (!res.ok) throw new Error(`Could not load approval (${res.status})`);

    const state = await res.json();

    changeSummary.value    = state.changeSummary   ?? [];
    validatedEvents.value  = state.validatedEvents ?? [];
    approvalExpiresAt.value = state.expiresAt;

    // Mark all validation steps done for visual completeness
    validationSteps.value.forEach(s => s.done = true);

    if (state.status === 'APPROVED') {
      buildDoneState(state);
    } else if (state.status === 'REJECTED') {
      approvalPhase.value = 'rejected';
    } else {
      approvalPhase.value = 'review';
    }
  } catch (err) {
    console.error('[loadApprovalState]', err);
    approvalError.value = err.message;
    approvalPhase.value = 'idle';
  }
};

// ── HITL Phase 3a — Approve ───────────────────────────────────────────────────
const approveSchedule = async () => {
  if (!approvalId.value || approvalExecuting.value) return;
  approvalExecuting.value = true;
  approvalPhase.value     = 'executing';
  approvalError.value     = '';

  try {
    const res = await fetch(
      `http://localhost:8080/api/users/courses/${encodeURIComponent(courseId)}/approvals/${encodeURIComponent(approvalId.value)}/approve`,
      { method: 'POST', credentials: 'include' }
    );
    if (!res.ok) throw new Error(`Approval failed (${res.status})`);

    const state = await res.json();
    buildDoneState(state);
  } catch (err) {
    console.error('[approve]', err);
    approvalError.value = err.message;
    approvalPhase.value = 'review'; // return to review so they can retry
  } finally {
    approvalExecuting.value = false;
  }
};

// ── HITL Phase 3b — Reject ────────────────────────────────────────────────────
const rejectSchedule = async () => {
  if (!approvalId.value || approvalExecuting.value) return;
  try {
    await fetch(
      `http://localhost:8080/api/users/courses/${encodeURIComponent(courseId)}/approvals/${encodeURIComponent(approvalId.value)}/reject`,
      { method: 'POST', credentials: 'include' }
    );
  } catch { /* non-fatal — local state is authoritative */ }
  approvalPhase.value = 'rejected';
};

// Parse the execution receipt from ApprovalState into display state
const buildDoneState = (state) => {
  const ids    = state.createdCalendarEventIds ?? {};
  const errors = Object.entries(ids)
    .filter(([, v]) => String(v).startsWith('ERROR'))
    .map(([k, v]) => `${k}: ${v}`);

  createdCount.value    = Object.values(ids).filter(v => !String(v).startsWith('ERROR')).length;
  executionErrors.value = errors;
  approvalPhase.value   = 'done';
};

// ── Helpers ───────────────────────────────────────────────────────────────────
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
const formatApprovalExpiry = (iso) => {
  if (!iso) return 'in 7 days';
  try {
    const d = new Date(iso);
    const now = new Date();
    const days = Math.round((d - now) / 86_400_000);
    if (days <= 0) return 'soon';
    if (days === 1) return 'tomorrow';
    return `in ${days} days`;
  } catch { return iso; }
};

// ── Guide streaming (unchanged from original) ─────────────────────────────────
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

const fetchGuideContent = async (eventSummary) => {
  const safeName = eventSummary?.replace(/[\\/:*?"<>|]/g, '_') ?? '';
  const res = await fetch(
    `http://localhost:8080/api/users/courses/${encodeURIComponent(courseId)}/study_guide/guide/${encodeURIComponent(safeName)}`,
    { credentials: 'include' }
  );
  if (!res.ok) throw new Error(`Failed to fetch guide: ${safeName}`);
  return res.text();
};

const streamGuide = (event) => new Promise((resolve) => {
  const item = guideItems.value.find(g => g.name === event.summary);
  if (!item) { resolve(); return; }

  const url = `http://localhost:8080/api/users/courses/${encodeURIComponent(courseId)}/study_guide/content`;
  fetch(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    credentials: 'include',
    body: JSON.stringify(event),
  }).then(res => {
    if (!res.ok || !res.body) {
      item.status = 'ready';
      item.html = '<p><em>Server error — could not open stream.</em></p>';
      generationWarning.value = 'One or more guides failed.';
      resolve();
      return;
    }
    const reader = res.body.getReader();
    const decoder = new TextDecoder();
    let buf = '';
    let lastEventName = 'status';

    const pump = () => reader.read().then(({ done, value }) => {
      if (done) { resolve(); return; }
      buf += decoder.decode(value, { stream: true });
      const lines = buf.split('\n');
      buf = lines.pop();
      for (const line of lines) {
        if (line.startsWith('event:')) {
          lastEventName = line.slice(6).trim();
        } else if (line.startsWith('data:')) {
          try {
            const payload = JSON.parse(line.slice(5).trim());
            handleSsePayload(item, lastEventName, payload, resolve);
          } catch { /* malformed chunk */ }
          lastEventName = 'status';
        }
      }
      pump();
    }).catch(() => {
      item.status = 'ready';
      item.html = '<p><em>Stream closed unexpectedly.</em></p>';
      resolve();
    });
    pump();
  }).catch(() => {
    item.status = 'ready';
    item.html = '<p><em>Network error.</em></p>';
    resolve();
  });
});

const handleSsePayload = (item, eventName, payload, resolve) => {
  if (eventName === 'complete' || payload.state === 'READY') {
    item.html = marked.parse(payload.guide ?? '');
    item.status = 'ready';
    // if (!activeGuide.value) activeGuide.value = item;
    resolve();
    return;
  }
  if (eventName === 'error' || payload.state === 'ERROR') {
    item.status = 'ready';
    item.html = `<p><em>Error: ${payload.message}</em></p>`;
    generationWarning.value = 'Some guides failed. Check console for details.';
    resolve();
    return;
  }
  const stateMap = {
    PENDING:      { status: 'generating' },
    SEARCHING:    { status: 'generating' },
    RERANKING:    { status: 'reranking'  },
    COOLING_DOWN: { status: 'cooling'    },
    GENERATING:   { status: 'generating' },
  };
  const mapped = stateMap[payload.state];
  if (mapped) item.status = mapped.status;
};

const sleep = (ms) => new Promise(resolve => setTimeout(resolve, ms));

// ── generateMissingGuides: when done, surface the approval section ─────────────
const generateMissingGuides = async (eventsToGenerate) => {
  isGenerating.value = true;
  try {
    for (let i = 0; i < eventsToGenerate.length; i++) {
      await streamGuide(eventsToGenerate[i]);
      if (i < eventsToGenerate.length - 1) await sleep(60_000);
    }
  } finally {
    isGenerating.value = false;
    await surfaceApproval();          // ← resume existing or show idle
  }
};


// ── fetchStudyPlan (unchanged except the missing-guide approval surfacing) ─────
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

    guideItems.value = events.map(e => ({
      name: e.summary ?? 'Untitled',
      status: 'pending',
      html: '',
    }));

    const cached = await checkExistingGuides(events);

    if (cached.size > 0) {
      await Promise.all(
        events.filter(e => cached.has(e.summary)).map(async (e) => {
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
      // if (!activeGuide.value) {
      //   activeGuide.value = guideItems.value.find(g => g.status === 'ready') ?? null;
      // }
    }

    const missingEvents = events.filter(e => !cached.has(e.summary) && !isCelebration(e));

    if (missingEvents.length > 0) {
      // Approval section surfaces at the end of generateMissingGuides
      generateMissingGuides(missingEvents);
    } else {
      // All guides were already cached — surface approval immediately
      await surfaceApproval();
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
/* ── Existing styles (unchanged) ──────────────────────────────────────────── */
.plan-root {
  min-height: 100vh;
  background: var(--bg-dark, #0d0d0f);
  color: var(--text, #e8e6e0);
  font-family: 'DM Sans', sans-serif;
  padding: 0 0 100px;
}
.top-bar {
  display: flex; align-items: center; justify-content: space-between;
  padding: 20px 40px;
  border-bottom: 1px solid rgba(255,255,255,0.07);
}
.back-link {
  display: flex; align-items: center; gap: 6px;
  background: none; border: none; color: rgba(255,255,255,0.4);
  cursor: pointer; font-size: 0.9rem; transition: color 0.2s; padding: 0;
}
.back-link:hover { color: rgba(255,255,255,0.9); }
.exam-tag {
  font-size: 0.78rem; font-weight: 600; letter-spacing: 0.08em; text-transform: uppercase;
  color: rgba(255,255,255,0.3); background: rgba(255,255,255,0.05);
  border: 1px solid rgba(255,255,255,0.1); padding: 4px 12px; border-radius: 4px;
  max-width: 300px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.page-header { padding: 56px 40px 20px; }
.page-header h1 {
  font-size: 2.4rem; font-weight: 700; letter-spacing: -0.03em; margin: 0 0 6px; color: #fff;
}
.page-sub { margin: 0; color: rgba(255,255,255,0.3); font-size: 0.9rem; }
.content { padding: 0 40px; }
.section { margin-top: 48px; }
.section-title {
  font-size: 0.8rem; font-weight: 600; letter-spacing: 0.12em; text-transform: uppercase;
  color: rgba(255,255,255,0.3); margin: 0 0 24px;
}
.timeline { display: flex; flex-direction: column; }
.event-row { display: flex; align-items: stretch; padding: 0 0 8px; }
.event-date-col {
  width: 130px; flex-shrink: 0; display: flex; flex-direction: column;
  align-items: flex-end; padding: 16px 20px 16px 0; gap: 3px;
}
.ev-date { font-size: 0.82rem; font-weight: 600; color: rgba(255,255,255,0.7); }
.ev-time { font-size: 0.75rem; color: rgba(255,255,255,0.3); white-space: nowrap; }
.event-spine { width: 1px; background: rgba(255,255,255,0.08); flex-shrink: 0; position: relative; }
.event-spine::before {
  content: ''; position: absolute; top: 18px; left: -3px;
  width: 7px; height: 7px; border-radius: 50%;
  background: rgba(124,92,252,0.6); border: 1px solid rgba(124,92,252,0.4);
}
.event-body { flex: 1; padding: 14px 0 14px 20px; }
.event-title { font-size: 0.95rem; font-weight: 600; color: rgba(255,255,255,0.85); margin: 0 0 6px; }
.event-desc { font-size: 0.85rem; color: rgba(255,255,255,0.4); line-height: 1.55; margin: 0; }
.celebration-chip {
  display: inline-block; margin-top: 8px; font-size: 0.72rem; font-weight: 600;
  letter-spacing: 0.06em; text-transform: uppercase;
  background: rgba(250,200,50,0.1); color: rgba(250,200,50,0.8);
  border: 1px solid rgba(250,200,50,0.25); padding: 2px 8px; border-radius: 4px;
}
.event-row.is-celebration .event-spine::before {
  background: rgba(250,200,50,0.7); border-color: rgba(250,200,50,0.4);
}
.guides-section { border-top: 1px solid rgba(255,255,255,0.06); padding-top: 40px; }
.guides-header { display: flex; align-items: center; justify-content: space-between; margin-bottom: 24px; }
.agent-status { display: flex; align-items: center; gap: 8px; font-size: 0.8rem; color: rgba(255,255,255,0.4); }
.status-running { color: rgba(180,160,255,0.9); }
.status-done { color: rgba(100,200,120,0.9); }
.pulse-dot {
  width: 8px; height: 8px; border-radius: 50%; background: rgba(180,160,255,0.9);
  animation: pulse 1.4s ease-in-out infinite;
}
@keyframes pulse {
  0%, 100% { opacity: 1; transform: scale(1); }
  50% { opacity: 0.5; transform: scale(0.7); }
}
.guide-progress-list { display: flex; flex-direction: column; gap: 2px; margin-bottom: 24px; }
.guide-progress-row {
  display: flex; align-items: center; gap: 12px; padding: 10px 14px;
  border-radius: 8px; border: 1px solid transparent; transition: all 0.2s;
}
.guide-progress-row.ready {
  cursor: pointer; border-color: rgba(255,255,255,0.06); background: rgba(255,255,255,0.02);
}
.guide-progress-row.ready:hover { background: rgba(255,255,255,0.05); border-color: rgba(124,92,252,0.2); }
.guide-row-indicator { width: 16px; display: flex; justify-content: center; }
.dot { width: 8px; height: 8px; border-radius: 50%; display: inline-block; }
.ready-dot { background: rgba(100,200,120,0.8); }
.gen-dot { background: rgba(180,160,255,0.9); animation: pulse 1s ease-in-out infinite; }
.pending-dot { background: rgba(255,255,255,0.15); }
.guide-row-name { flex: 1; font-size: 0.875rem; color: rgba(255,255,255,0.7); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.guide-row-badge { font-size: 0.72rem; font-weight: 600; letter-spacing: 0.05em; text-transform: uppercase; padding: 2px 8px; border-radius: 4px; }
.guide-row-badge.ready { background: rgba(100,200,120,0.1); color: rgba(100,200,120,0.9); }
.guide-row-badge.generating { background: rgba(180,160,255,0.1); color: rgba(180,160,255,0.9); }
.guide-row-badge.pending { background: rgba(255,255,255,0.05); color: rgba(255,255,255,0.3); }
.guide-row-badge.reranking { background: rgba(251,191,36,0.1); color: rgba(251,191,36,0.9); }
.guide-row-badge.cooling   { background: rgba(248,113,113,0.1); color: rgba(248,113,113,0.8); }
.dot.rerank-dot { background: rgba(251,191,36,0.9); animation: pulse 1s ease-in-out infinite; }
.dot.cool-dot   { background: rgba(248,113,113,0.8); animation: pulse 1.5s ease-in-out infinite; }
.guide-reader { background: rgba(255,255,255,0.03); border: 1px solid rgba(255,255,255,0.1); border-radius: 12px; overflow: hidden; }
.reader-header {
  display: flex; align-items: center; justify-content: space-between;
  padding: 14px 20px; border-bottom: 1px solid rgba(255,255,255,0.07);
  background: rgba(255,255,255,0.02);
}
.reader-title { font-size: 0.875rem; font-weight: 600; color: rgba(255,255,255,0.8); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; max-width: 80%; }
.close-btn { background: none; border: none; cursor: pointer; color: rgba(255,255,255,0.3); padding: 4px; transition: color 0.2s; }
.close-btn:hover { color: rgba(255,255,255,0.8); }
.reader-body { padding: 28px 32px; max-height: 600px; overflow-y: auto; }
.markdown-body { color: rgba(255,255,255,0.8); line-height: 1.75; font-size: 0.925rem; }
.markdown-body :deep(h1), .markdown-body :deep(h2), .markdown-body :deep(h3) { color: #fff; font-weight: 600; margin: 1.5em 0 0.6em; line-height: 1.3; }
.markdown-body :deep(h1) { font-size: 1.3rem; }
.markdown-body :deep(h2) { font-size: 1.1rem; }
.markdown-body :deep(h3) { font-size: 0.95rem; }
.markdown-body :deep(p) { margin: 0 0 1em; }
.markdown-body :deep(ul), .markdown-body :deep(ol) { padding-left: 1.4em; margin: 0.5em 0 1em; }
.markdown-body :deep(li) { margin-bottom: 0.35em; }
.markdown-body :deep(code) { background: rgba(255,255,255,0.07); border-radius: 4px; padding: 2px 6px; font-size: 0.85em; font-family: 'JetBrains Mono', monospace; color: rgba(180,160,255,0.9); }
.markdown-body :deep(pre) { background: rgba(0,0,0,0.4); border-radius: 8px; padding: 16px 20px; overflow-x: auto; margin: 1em 0; }
.markdown-body :deep(pre code) { background: none; padding: 0; }
.markdown-body :deep(blockquote) { border-left: 3px solid rgba(124,92,252,0.5); padding-left: 16px; margin: 1em 0; color: rgba(255,255,255,0.5); }
.markdown-body :deep(strong) { color: #fff; font-weight: 600; }
.warning-bar { margin-top: 16px; padding: 10px 14px; background: rgba(250,180,50,0.08); border: 1px solid rgba(250,180,50,0.2); border-radius: 8px; font-size: 0.85rem; color: rgba(250,180,50,0.85); display: flex; gap: 8px; align-items: flex-start; }
.state-block { display: flex; flex-direction: column; align-items: center; justify-content: center; padding: 60px 40px; text-align: center; gap: 14px; }
.state-label { color: rgba(255,255,255,0.3); margin: 0; font-size: 0.9rem; }
.error-block { color: rgba(255,100,80,0.8); }
.scan-bar { width: 160px; height: 2px; background: rgba(255,255,255,0.06); border-radius: 2px; overflow: hidden; position: relative; }
.scan-bar::after { content: ''; position: absolute; top: 0; left: -60%; width: 60%; height: 100%; background: linear-gradient(90deg, transparent, var(--primary, #7c5cfc), transparent); animation: scan 1.4s ease-in-out infinite; }
@keyframes scan { to { left: 100%; } }

/* ── NEW: Approval section styles ─────────────────────────────────────────── */
.approval-section {
  border-top: 1px solid rgba(255,255,255,0.06);
  padding-top: 40px;
}
.approval-header {
  display: flex; align-items: center; justify-content: space-between;
  margin-bottom: 28px;
}
.approval-phase-badge {
  font-size: 0.72rem; font-weight: 600; letter-spacing: 0.06em; text-transform: uppercase;
  padding: 3px 10px; border-radius: 4px;
  background: rgba(255,255,255,0.05); color: rgba(255,255,255,0.3);
  border: 1px solid rgba(255,255,255,0.1);
}
.approval-phase-badge.review    { background: rgba(124,92,252,0.1); color: rgba(180,160,255,0.9); border-color: rgba(124,92,252,0.25); }
.approval-phase-badge.done      { background: rgba(100,200,120,0.1); color: rgba(100,200,120,0.9); border-color: rgba(100,200,120,0.25); }
.approval-phase-badge.executing { background: rgba(180,160,255,0.1); color: rgba(180,160,255,0.9); border-color: rgba(124,92,252,0.25); }
.approval-phase-badge.rejected  { background: rgba(248,113,113,0.1); color: rgba(248,113,113,0.8); border-color: rgba(248,113,113,0.2); }

/* Idle */
.approval-idle { display: flex; flex-direction: column; gap: 16px; max-width: 540px; }
.approval-explainer { color: rgba(255,255,255,0.45); font-size: 0.9rem; line-height: 1.65; margin: 0; }

/* Buttons */
.btn-primary {
  display: inline-flex; align-items: center; gap: 8px;
  background: rgba(124,92,252,0.85); color: #fff; border: none;
  padding: 11px 20px; border-radius: 8px; font-size: 0.875rem; font-weight: 600;
  cursor: pointer; transition: background 0.2s; align-self: flex-start;
}
.btn-primary:hover { background: rgba(124,92,252,1); }
.btn-secondary {
  background: rgba(255,255,255,0.06); color: rgba(255,255,255,0.6); border: 1px solid rgba(255,255,255,0.1);
  padding: 9px 18px; border-radius: 8px; font-size: 0.875rem; cursor: pointer; transition: background 0.2s;
}
.btn-secondary:hover { background: rgba(255,255,255,0.1); }

/* Validating */
.approval-progress { display: flex; flex-direction: column; gap: 20px; }
.agent-progress-header { display: flex; align-items: center; gap: 12px; font-size: 0.9rem; color: rgba(180,160,255,0.9); }
.large-pulse { width: 10px; height: 10px; }
.agent-step-list { display: flex; flex-direction: column; gap: 10px; }
.agent-step { display: flex; align-items: center; gap: 10px; font-size: 0.875rem; color: rgba(255,255,255,0.5); }
.agent-step.step-done { color: rgba(255,255,255,0.75); }
.step-check { color: rgba(100,200,120,0.9); flex-shrink: 0; }
.step-spinner {
  width: 12px; height: 12px; border-radius: 50%; flex-shrink: 0;
  border: 1.5px solid rgba(180,160,255,0.3); border-top-color: rgba(180,160,255,0.9);
  animation: spin 0.8s linear infinite;
}
@keyframes spin { to { transform: rotate(360deg); } }

/* Review */
.approval-review { display: flex; flex-direction: column; gap: 24px; }
.changes-panel {
  background: rgba(255,255,255,0.02); border: 1px solid rgba(255,255,255,0.08);
  border-radius: 10px; padding: 18px 20px;
}
.changes-panel.no-changes { border-color: rgba(100,200,120,0.2); background: rgba(100,200,120,0.03); }
.changes-label { font-size: 0.78rem; font-weight: 600; letter-spacing: 0.08em; text-transform: uppercase; color: rgba(255,255,255,0.3); margin: 0 0 12px; }
.changes-list { margin: 0; padding-left: 1.2em; display: flex; flex-direction: column; gap: 6px; }
.changes-list li { font-size: 0.875rem; color: rgba(255,255,255,0.65); line-height: 1.5; }
.error-list li { color: rgba(248,113,113,0.85); }

/* Validated event preview */
.validated-events { background: rgba(255,255,255,0.02); border: 1px solid rgba(255,255,255,0.08); border-radius: 10px; padding: 18px 20px; }
.val-event-list { display: flex; flex-direction: column; gap: 6px; margin-top: 4px; }
.val-event-row {
  display: flex; align-items: center; gap: 12px;
  padding: 8px 10px; border-radius: 6px; background: rgba(255,255,255,0.02);
}
.val-ev-type-dot { width: 7px; height: 7px; border-radius: 50%; flex-shrink: 0; }
.val-ev-type-dot.exam         { background: rgba(248,113,113,0.8); }
.val-ev-type-dot.study_session { background: rgba(124,92,252,0.7); }
.val-ev-type-dot.celebration  { background: rgba(250,200,50,0.8); }
.val-ev-info { flex: 1; display: flex; flex-direction: column; gap: 2px; overflow: hidden; }
.val-ev-summary { font-size: 0.875rem; color: rgba(255,255,255,0.8); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.val-ev-time { font-size: 0.75rem; color: rgba(255,255,255,0.3); }
.val-ev-type-chip { font-size: 0.68rem; font-weight: 600; letter-spacing: 0.05em; text-transform: uppercase; padding: 2px 7px; border-radius: 4px; flex-shrink: 0; }
.val-ev-type-chip.exam          { background: rgba(248,113,113,0.1); color: rgba(248,113,113,0.8); }
.val-ev-type-chip.study_session { background: rgba(124,92,252,0.1); color: rgba(180,160,255,0.9); }
.val-ev-type-chip.celebration   { background: rgba(250,200,50,0.1); color: rgba(250,200,50,0.8); }

/* Review action buttons */
.review-actions { display: flex; align-items: center; gap: 12px; }
.btn-approve {
  display: inline-flex; align-items: center; gap: 8px;
  background: rgba(100,200,120,0.85); color: #0d1a10; border: none;
  padding: 11px 22px; border-radius: 8px; font-size: 0.875rem; font-weight: 700;
  cursor: pointer; transition: background 0.2s;
}
.btn-approve:hover:not(:disabled) { background: rgba(100,200,120,1); }
.btn-approve:disabled { opacity: 0.5; cursor: not-allowed; }
.btn-reject {
  background: rgba(255,255,255,0.05); color: rgba(255,255,255,0.5);
  border: 1px solid rgba(255,255,255,0.1); padding: 10px 18px;
  border-radius: 8px; font-size: 0.875rem; cursor: pointer; transition: all 0.2s;
}
.btn-reject:hover:not(:disabled) { background: rgba(248,113,113,0.1); color: rgba(248,113,113,0.8); border-color: rgba(248,113,113,0.25); }
.btn-reject:disabled { opacity: 0.4; cursor: not-allowed; }
.approval-expires { margin: 0; font-size: 0.78rem; color: rgba(255,255,255,0.25); }

/* Executing */
.approval-executing { display: flex; align-items: center; gap: 14px; font-size: 0.9rem; color: rgba(180,160,255,0.8); padding: 20px 0; }

/* Done */
.approval-done { display: flex; flex-direction: column; align-items: flex-start; gap: 16px; }
.done-icon { display: flex; }
.done-label { font-size: 1rem; font-weight: 600; color: rgba(100,200,120,0.9); margin: 0; }
.done-errors { background: rgba(248,113,113,0.05); border: 1px solid rgba(248,113,113,0.15); border-radius: 8px; padding: 14px 16px; }

/* Rejected */
.approval-rejected { display: flex; flex-direction: column; align-items: flex-start; gap: 16px; padding: 16px 0; }
</style>