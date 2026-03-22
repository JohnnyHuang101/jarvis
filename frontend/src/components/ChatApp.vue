<script setup>
import { ref, nextTick, watch } from 'vue';
import stickerImage1 from '../assets/images/vro.jpg'; 

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
        <button @click="sendMessage" :disabled="isLoading">
          Send
        </button>
      </div>
    </div>
  </div>
</template>

<style scoped>
/* Paste your App.css content here */
</style> 