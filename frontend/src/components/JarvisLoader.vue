<template>
  <div class="modal-overlay" v-if="!isReady">
    <div class="modal-content">
      
      <h2 class="title">JARVIS INITIALIZATION</h2>

      <div class="battery-wrapper">
        <div class="battery-body">
          <div class="segment" :class="{ filled: currentStage >= 1 }"></div>
          <div class="segment" :class="{ filled: currentStage >= 2 }"></div>
          <div class="segment" :class="{ filled: currentStage >= 3, pulse: currentStage === 2 }"></div>
        </div>
        <div class="battery-tip"></div>
      </div>

      <div class="status-text">
        <p class="primary-status">{{ statusMessage }}</p>
        
        <transition name="fade">
          <p v-if="currentStage === 2" class="secondary-status warning">
            Vectorizing documents requires heavy processing. 
            <br>Feel free to close this window and log back in later.
          </p>
        </transition>
      </div>

    </div>
  </div>
</template>
<script setup>
import { ref, onMounted } from 'vue';
import axios from 'axios';
import { useRouter } from 'vue-router';

const router = useRouter();
const currentStage = ref(0);
const isReady = ref(false); 
const statusMessage = ref("Establishing secure connection...");

let pollInterval = null;

onMounted(async () => {
  try {
    // 1. THE FAST CHECK: Are we completely set up? (Takes 10ms)
    const checkResponse = await axios.get('http://localhost:8080/api/user/check', {
      withCredentials: true 
    });

    if (checkResponse.data === true) {
      // --- RETURNING USER (Fully Ready) ---
      console.log("Welcome back. Systems online.");

      statusMessage.value = "Welcome back. Systems online.";

      setTimeout(() => {
          isReady.value = true;

          router.push('/chat');
        }, 3000);
      
    } else {
      // --- NEW USER (Or interrupted setup) ---
      console.log("New user detected. Commencing Jarvis Initialization...");
      
      // INSTANTLY update UI so the user sees action
      currentStage.value = 1; 
      statusMessage.value = "Workspace allocated. Handshaking with Google Drive...";
      
      // 2. THE TRIGGER: Fire this in the background (Notice there is no 'await' here!)
      // This tells the backend to start the heavy pipeline while the frontend moves on.
      axios.get('http://localhost:8080/api/user/load', { withCredentials: true })
           .catch(err => console.error("Pipeline trigger failed:", err));

      // 3. THE TRACKER: Start polling the status immediately
      startPolling(); 
    }

  } catch (error) {
    if (error.response && error.response.status === 401) {
      window.location.href = "http://localhost:8080/oauth2/authorization/google";
    } else {
      statusMessage.value = "Connection failed. Please refresh.";
    }
  }
});

const startPolling = () => {
  pollInterval = setInterval(async () => {
    try {
      const res = await axios.get('http://localhost:8080/api/user/status', { withCredentials: true });
      const backendStage = res.data; 

      if (backendStage === 1) {
        currentStage.value = 1;
        statusMessage.value = "Workspace allocated. Pulling Google Drive files...";
      } 
      else if (backendStage === 2) {
        currentStage.value = 2;
        statusMessage.value = "Files acquired. Vectorizing knowledge base...";
      } 
      else if (backendStage === 3) {
        currentStage.value = 3;
        statusMessage.value = "Systems online. Welcome back, Sir.";
        clearInterval(pollInterval);
        
        setTimeout(() => {
          isReady.value = true;
          router.push('/chat');
        }, 3000);

        
      }
    } catch (error) {
      console.error("Failed to fetch status", error);
    }
  }, 2000); 
};
</script>

<style scoped>
/* (Keep all your existing CSS exactly as it is) */
.modal-overlay {
  position: fixed;
  top: 0; left: 0; width: 100vw; height: 100vh;
  background: rgba(5, 10, 15, 0.95);
  display: flex;
  justify-content: center;
  align-items: center;
  z-index: 1000;
  font-family: 'Courier New', Courier, monospace;
  color: #00d2ff;
}

.modal-content {
  text-align: center;
  width: 400px;
}

.title {
  letter-spacing: 4px;
  font-size: 1.2rem;
  margin-bottom: 2rem;
  text-shadow: 0 0 10px rgba(0, 210, 255, 0.5);
}

.battery-wrapper {
  display: flex;
  align-items: center;
  justify-content: center;
  margin-bottom: 2rem;
}

.battery-body {
  width: 200px;
  height: 60px;
  border: 3px solid #00d2ff;
  border-radius: 8px;
  display: flex;
  padding: 4px;
  gap: 4px;
  box-shadow: 0 0 15px rgba(0, 210, 255, 0.2);
}

.battery-tip {
  width: 10px;
  height: 25px;
  background: #00d2ff;
  border-radius: 0 4px 4px 0;
  margin-left: 4px;
}

.segment {
  flex: 1;
  background: transparent;
  border-radius: 4px;
  transition: background 0.5s ease, box-shadow 0.5s ease;
}

.segment.filled {
  background: #00d2ff;
  box-shadow: 0 0 10px #00d2ff;
}

.segment.pulse {
  animation: pulse-glow 1.5s infinite alternate;
}

@keyframes pulse-glow {
  0% { opacity: 0.5; box-shadow: 0 0 5px #00d2ff; }
  100% { opacity: 1; box-shadow: 0 0 20px #00d2ff; }
}

.primary-status {
  font-size: 1.1rem;
  margin-bottom: 1rem;
}

.secondary-status {
  font-size: 0.85rem;
  color: #a0a0a0;
  line-height: 1.4;
}

.warning {
  color: #ffaa00;
  text-shadow: 0 0 5px rgba(255, 170, 0, 0.3);
}

.fade-enter-active, .fade-leave-active {
  transition: opacity 1s;
}
.fade-enter-from, .fade-leave-to {
  opacity: 0;
}
</style>