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
  
    const currentStage = ref(0);
    const initializing = ref(true);

    onMounted(async () => {
    try {
        // 1. Ask the backend: Is this a new user or a returning user?
        const response = await axios.get('http://localhost:8080/api/user/load', {
        withCredentials: true 
        });

        if (response.data.status === "READY") {
        // --- RETURNING USER ---
        console.log("Welcome back. Systems online.");
        initializing.value = false; // Hide modal instantly
        
        } else if (response.data.status === "INITIALIZING") {
        // --- NEW USER ONBOARDING ---
        console.log("New user detected. Commencing Jarvis Initialization...");
        currentStage.value = 1; // Start the battery UI
        
        // Start polling the /status endpoint every 2 seconds to update the battery
        startPolling(); 
        }

    } catch (error) {
        if (error.response && error.response.status === 401) {
        window.location.href = "http://localhost:8080/oauth2/authorization/google";
        }
    }
    });
  </script>
  
  <style scoped>
  /* Jarvis UI Styling */
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
  
  /* Battery CSS */
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
  
  /* Optional: Make the active segment pulse while working */
  .segment.pulse {
    animation: pulse-glow 1.5s infinite alternate;
  }
  
  @keyframes pulse-glow {
    0% { opacity: 0.5; box-shadow: 0 0 5px #00d2ff; }
    100% { opacity: 1; box-shadow: 0 0 20px #00d2ff; }
  }
  
  /* Typography */
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
  
  /* Vue Transition Classes */
  .fade-enter-active, .fade-leave-active {
    transition: opacity 1s;
  }
  .fade-enter-from, .fade-leave-to {
    opacity: 0;
  }
  </style>