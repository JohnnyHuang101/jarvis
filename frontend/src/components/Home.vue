<template>
  <div class="dashboard-layout">
    
    <nav class="sidebar">
      <div class="brand">
        <h1>Jarvis</h1>
        <p class="subtitle">Study Protocol</p>
      </div>
      
      <button @click="showUploadModal = true" class="btn-primary upload-trigger">
        + Add Syllabus
      </button>
    </nav>

    <main class="dashboard-content">
      <header class="courses-header">
        <h2>Your Courses</h2>
        <p>Processed syllabi will appear here.</p>
      </header>

      <div class="courses-grid">
            <div v-for="course in courses" :key="course.fileName" class="course-card">
                <div class="card-icon">📚</div>
                    <h3>{{ course.courseCode }}: {{ course.courseName }}</h3>
                <p class="subtitle">{{ course.term }}</p>
            <button class="view-btn" @click="goToSchedule(course.courseCode)">View Schedule</button>
        </div>
      </div>
    </main>

    <div v-if="showUploadModal" class="modal-overlay" @click.self="closeModal">
      <div class="modal-content">
        <h2>Upload Syllabus</h2>
        <p class="modal-desc">Select a PDF or Word document to extract schedule data.</p>

        <input
          type="file"
          ref="fileInput"
          @change="handleFileChange"
          accept=".pdf,.doc,.docx"
          style="display: none"
        />

        <div v-if="!selectedFile" class="upload-actions">
           <button @click="triggerFileInput" class="btn-primary browse-btn">
             Browse Files
           </button>
        </div>

        <div v-else class="file-info">
          <p class="file-name"><strong>File:</strong> {{ selectedFile.name }}</p>
          
          <button @click="uploadFile" class="btn-primary submit-btn" :disabled="isUploading">
            <span v-if="isUploading" class="spinner-small"></span>
            {{ isUploading ? 'Processing...' : 'Confirm & Extract' }}
          </button>
        </div>

        <div v-if="uploadMessage" :class="['message', uploadStatus]">
          {{ uploadMessage }}
        </div>
      </div>
    </div>

  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue';
import { useRouter } from 'vue-router';

// Modal State
const showUploadModal = ref(false);

// File Upload State
const fileInput = ref(null);
const selectedFile = ref(null);
const isUploading = ref(false);
const uploadMessage = ref('');
const uploadStatus = ref(''); 
const courses = ref([]);

const triggerFileInput = () => {
  fileInput.value.click();
};

const handleFileChange = (event) => {
  const file = event.target.files[0];
  if (file) {
    selectedFile.value = file;
    uploadMessage.value = ''; 
  }
};

const closeModal = () => {
  if (!isUploading.value) {
    showUploadModal.value = false;
    selectedFile.value = null;
    uploadMessage.value = '';
  }
};

const uploadFile = async () => {
  if (!selectedFile.value) return;

  isUploading.value = true;
  uploadMessage.value = '';

  const formData = new FormData();
  formData.append('file', selectedFile.value);

  try {
    const response = await fetch('http://localhost:8080/api/syllabus/upload', {
      method: 'POST',
      body: formData,
      credentials: 'include'
    });

    if (response.status === 401) {
      console.log("Not logged in. Redirecting to Google...");
      window.location.href = 'http://localhost:8080/oauth2/authorization/google';
      return;
    }

    if (!response.ok) throw new Error('Network response was not ok');

    const result = await response.text(); 
    uploadStatus.value = 'success';
    uploadMessage.value = result; 
    
    // Optional: Close modal automatically after 2 seconds on success
    setTimeout(() => {
        closeModal();
    }, 2000);

  } catch (error) {
    uploadStatus.value = 'error';
    uploadMessage.value = 'Upload failed. Check server connection.';
    console.error('Error:', error);
  } finally {
    isUploading.value = false;
  }
};


const loadCourses = async () => {
  try {
    const response = await fetch('http://localhost:8080/api/syllabus/courses', {
      method: 'GET',
      credentials: 'include' // CRITICAL: This sends your Google login cookie!
    });

    if (response.status === 401) {
       console.log("Session expired, need to log in again.");
       window.location.href = 'http://localhost:8080/oauth2/authorization/google';
       return;
    }

    if (response.ok) {
      // 3. Populate the reactive array with the JSON from the backend
      courses.value = await response.json();
    } else {
      console.error("Failed to fetch courses. Status:", response.status);
    }
  } catch (error) {
    console.error("Network error while loading courses:", error);
  }
};

onMounted(() => {
  loadCourses();
});

const router = useRouter();

const goToSchedule = (courseCode) => {
  router.push(`/course/${courseCode}`);
};

</script>

<style scoped>
/* Dashboard Base */
.dashboard-layout {
  display: flex;
  flex-direction: column;
  height: 100vh;
  background-color: var(--bg-dark);
  color: var(--text);
}

/* Sidebar / Header Navigation */
.sidebar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 20px 40px;
  background-color: var(--chat-bg);
  border-bottom: 1px solid #333;
}

.brand h1 {
  margin: 0;
  color: var(--primary);
  font-size: 1.8rem;
}

.subtitle {
  margin: 0;
  font-size: 0.9rem;
  color: #888;
}

.upload-trigger {
  box-shadow: 0 4px 15px rgba(170, 59, 255, 0.3); /* Purple glow */
}

/* Main Content Area */
.dashboard-content {
  padding: 40px;
  flex-grow: 1;
}

.courses-header h2 {
  margin-top: 0;
  font-size: 2rem;
  font-weight: 600;
}

.courses-header p {
  color: #aaa;
  margin-bottom: 30px;
}

/* Grid for future data */
.courses-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(300px, 1fr));
  gap: 20px;
}

.course-card-placeholder {
  background-color: var(--chat-bg);
  border: 1px dashed #444;
  border-radius: 12px;
  padding: 40px;
  text-align: center;
  color: #666;
}

/* Modal Specific Additions */
.modal-desc {
  color: #aaa;
  margin-bottom: 20px;
}

.browse-btn {
  width: 100%;
  background-color: #333;
}
.browse-btn:hover {
  background-color: #444;
}

.file-name {
  background-color: #111;
  padding: 10px;
  border-radius: 6px;
  word-break: break-all;
}

.submit-btn {
  width: 100%;
  margin-top: 15px;
}

/* Messages */
.message {
  margin-top: 20px;
  padding: 12px;
  border-radius: 6px;
  font-size: 0.9rem;
}
.message.success {
  background-color: rgba(40, 167, 69, 0.2);
  color: #4ade80;
  border: 1px solid rgba(40, 167, 69, 0.3);
}
.message.error {
  background-color: rgba(220, 53, 69, 0.2);
  color: #f87171;
  border: 1px solid rgba(220, 53, 69, 0.3);
}
</style>