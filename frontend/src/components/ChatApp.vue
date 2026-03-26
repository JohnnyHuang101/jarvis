<script setup>
  import { ref, nextTick, watch, onMounted } from 'vue';
  import stickerImage1 from '../assets/images/vro.jpg';
  import axios from 'axios'; // or use fetch

  const NUMBER_OF_STICKERS = 15;
  const STICKER_IMAGES = [stickerImage1];

  // --- State Management ---
  const messages = ref([
    {
      role: 'bot',
      content: "Hello! I'm ready to help with you to create the best set of study guides!"
    }
  ]);
  const input = ref("");
  const isLoading = ref(false);

  const initializing = ref("true");


  // --- Methods ---
  const scrollToBottom = async () => {
    await nextTick();
    const el = document.getElementById('messagesEnd');
    el?.scrollIntoView({ behavior: "smooth" });
  };

  // Watch for new messages to trigger auto-scroll
  watch(messages, scrollToBottom, { deep: true });

  const sendMessage = async () => {
    if (!input.value.trim() || isLoading.value) return;

    // 1. Add User Message
    messages.value.push({ role: 'user', content: input.value });
    
    const currentInput = input.value; 
    input.value = ""; 
    isLoading.value = true;

    try {
      // 2. Call Spring Boot API via Vite Proxy
      const response = await fetch('/api/ask', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json'
        },
        body: JSON.stringify({ question: currentInput }),
      });

      if (!response.ok) throw new Error('Network response was not ok');

      const data = await response.json();

      // 3. Add Bot Response
      messages.value.push({ role: 'bot', content: data.answer });
    } catch (error) {
      console.error("Error:", error);
      messages.value.push({ 
        role: 'bot', 
        content: "Error: Could not connect to the server." 
      });
    } finally {
      isLoading.value = false;
    }
  };

  // --- Sticker Logic ---
  const generateStickerData = () => {
    return Array.from({ length: NUMBER_OF_STICKERS }).map(() => ({
      imageUrl: STICKER_IMAGES[Math.floor(Math.random() * STICKER_IMAGES.length)],
      style: {
        left: `${Math.random() * 80 + 10}px`,
        animationDuration: `${Math.random() * 5 + 7}s`,
        animationDelay: `${Math.random() * 10}s`,
      }
    }));
  };

  const leftStickers = generateStickerData();
  const rightStickers = generateStickerData();
</script>

<template>
  <div class="page-wrapper">
    <div class="sticker-rain left">
      <div 
        v-for="(sticker, index) in leftStickers" 
        :key="'left-'+index" 
        class="sticker"
        :style="sticker.style"
      >
        <img :src="sticker.imageUrl" alt="Sticker" class="sticker-image" />
      </div>
    </div>

    <div class="sticker-rain right">
      <div 
        v-for="(sticker, index) in rightStickers" 
        :key="'right-'+index" 
        class="sticker"
        :style="sticker.style"
      >
        <img :src="sticker.imageUrl" alt="Sticker" class="sticker-image" />
      </div>
    </div>

    <div class="app-container">
      <header class="chat-header">
        <h1>Jarvis Study Assistant</h1>
      </header>

      <div class="chat-window">
        <div 
          v-for="(msg, index) in messages" 
          :key="index" 
          class="message" 
          :class="msg.role"
        >
          <div class="bubble">{{ msg.content }}</div>
        </div>

        <div v-if="isLoading" class="message bot">
          <div class="bubble loading">Generating Cheat Sheet...</div>
        </div>

        <div id="messagesEnd"></div>
      </div>
      
      <div class="input-area">
        <input 
          type="text" 
          v-model="input" 
          @keydown.enter="sendMessage" 
          placeholder="Enter a Topic..." 
          :disabled="isLoading" 
        />
        <button @click="sendMessage" :disabled="isLoading || initializing">
          Send
        </button>
      </div>


      <div v-if="initializing" class="modal-overlay">
        <div class="modal-content">
          <div class="spinner"></div>
          <p>Initializing your workspace...</p>
        </div>
      </div>

    </div>
  </div>
</template>



<style scoped>
.modal-overlay {
  position: fixed;
  top: 0;
  left: 0;
  width: 100%;
  height: 100%;
  background: rgba(0, 0, 0, 0.7); /* Dim the background */
  display: flex;
  justify-content: center;
  align-items: center;
  z-index: 9999; /* Stay on top of everything */
}

.modal-content {
  background: white;
  padding: 2rem;
  border-radius: 8px;
  text-align: center;
  color: #333;
}

/* Simple Spinner Animation */
.spinner {
  margin: 0 auto 1rem;
  width: 40px;
  height: 40px;
  border: 4px solid #f3f3f3;
  border-top: 4px solid #3498db; /* Blue color */
  border-radius: 50%;
  animation: spin 1s linear infinite;
}

@keyframes spin {
  0% { transform: rotate(0deg); }
  100% { transform: rotate(360deg); }
}
</style>
