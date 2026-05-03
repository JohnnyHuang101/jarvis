<template>
  <div class="schedule-layout">
    <header class="header">
      <button @click="goBack" class="back-btn">← Back to Dashboard</button>
      <h2>Study Protocol: {{ courseId }}</h2>
    </header>

    <main class="content">
      <div v-if="isLoading" class="loading-state">
        <div class="spinner"></div>
        <p>Agent is analyzing the syllabus and building your schedule...</p>
      </div>

      <div v-else-if="error" class="error-state">
        <p>{{ error }}</p>
      </div>

      <div v-else class="timeline">
        <div v-for="(segment, index) in schedule" :key="index" class="segment-card">
          <div class="segment-header">
            <h3>{{ segment.examName }}</h3>
            <span class="date-badge">{{ segment.date }}</span>
          </div>
          
          <div class="units-list">
            <h4>Topics to Cover:</h4>
            <ul>
              <li v-for="unit in segment.unitsCovered" :key="unit">{{ unit }}</li>
            </ul>
          </div>
          
          <p v-if="segment.metaInformation" class="meta-info">
            {{ segment.metaInformation }}
          </p>

          <button 
            @click="generatePlan(segment)" 
            class="generate-btn"
            :disabled="segment.loading"
          >
            {{ segment.loading ? "Generating..." : "Generate Study Plan" }}
          </button>
        </div>
      </div>
    </main>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue';
import { useRoute, useRouter } from 'vue-router';

const route = useRoute();
const router = useRouter();

// Extract the courseId from the URL (/course/CS101 -> courseId = 'CS101')
const courseId = route.params.courseId;

const schedule = ref([]);
const isLoading = ref(true);
const error = ref(null);

const goBack = () => {
  router.push('/home'); // Or wherever your dashboard route is
};

const generateSchedule = async () => {
  try {
    // Note the URL matches the @PostMapping("/{courseId}/generate") we set up
    const response = await fetch(`http://localhost:8080/api/users/courses/${courseId}/study_guide/generate`, {
      method: 'POST',
      credentials: 'include' // CRITICAL: Sends the Google auth cookie so backend knows the userId
    });

    if (response.status === 401) {
      window.location.href = 'http://localhost:8080/oauth2/authorization/google';
      return;
    }

    if (!response.ok) throw new Error('Failed to generate study plan');

    schedule.value = await response.json();
  } catch (err) {
    console.error(err);
    error.value = "Failed to load schedule. Ensure the syllabus was processed correctly.";
  } finally {
    isLoading.value = false;
  }
};


const generatePlan = async (segment) => {
  try {
    const res = await fetch(
      `http://localhost:8080/api/users/courses/${courseId}/study_guide/study-plan`,
      {
        method: "POST",
        headers: {
          "Content-Type": "application/json"
        },
        credentials: "include", 
        body: JSON.stringify(segment)
      }
    );

    if (res.status === 401) {
      window.location.href = 'http://localhost:8080/oauth2/authorization/google';
      return;
    }

    if (!res.ok) throw new Error("Failed to generate plan");

    const data = await res.json();

    console.log("Study plan:", data);

    // ✅ use router directly (not this.$router)
    router.push({
      name: "StudyPlanView",
      params: {
        courseId: courseId,
        examName: segment.examName
      }
    });

  } catch (err) {
    console.error(err);
    alert("Error generating study plan");
  } finally {
    segment.loading = false;
  }
};

onMounted(() => {
  generateSchedule();
});
</script>

<style scoped>
.schedule-layout {
  padding: 40px;
  background-color: var(--bg-dark);
  color: var(--text);
  min-height: 100vh;
}

.header {
  margin-bottom: 40px;
}

.back-btn {
  background: none;
  border: none;
  color: #aaa;
  cursor: pointer;
  margin-bottom: 10px;
  font-size: 1rem;
}

.back-btn:hover {
  color: var(--primary);
}

.timeline {
  display: flex;
  flex-direction: column;
  gap: 20px;
}

.segment-card {
  background-color: var(--chat-bg);
  border: 1px solid #333;
  border-radius: 8px;
  padding: 20px;
}

.segment-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  border-bottom: 1px solid #444;
  padding-bottom: 10px;
  margin-bottom: 15px;
}

.date-badge {
  background-color: var(--primary);
  color: white;
  padding: 4px 12px;
  border-radius: 20px;
  font-size: 0.9rem;
  font-weight: bold;
}

.units-list ul {
  list-style-type: disc;
  margin-left: 20px;
  color: #ddd;
}

.meta-info {
  margin-top: 15px;
  padding: 10px;
  background-color: rgba(255, 255, 255, 0.05);
  border-radius: 6px;
  font-size: 0.9rem;
  color: #bbb;
}

.spinner {
  width: 40px;
  height: 40px;
  border: 4px solid rgba(170, 59, 255, 0.3);
  border-top-color: var(--primary);
  border-radius: 50%;
  animation: spin 1s linear infinite;
  margin-bottom: 20px;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}
</style>