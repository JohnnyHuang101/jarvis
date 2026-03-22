<script setup>
// Import the chat component you just built
import ChatApp from './components/ChatApp.vue';
</script>

<template>
  <ChatApp />
</template>

<style>
/* Global Styles go here! 
  Notice there is NO "scoped" attribute on this <style> tag.
  These rules will apply to the entire website.
*/
body {
  margin: 0;
  padding: 0;
  font-family: 'Inter', sans-serif; /* Or whatever font you prefer */
  background-color: #1e1e1e; /* Assuming Jarvis has a dark mode vibe! */
}

/* You can also put a global CSS reset here */
* {
  box-sizing: border-box;
}
</style>