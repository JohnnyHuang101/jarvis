<template>
  <div class="login-container">
    <h1>Jarvis AI</h1>
    <p>Connect your Google Drive to start chatting with your notes.</p>
    <a href="http://localhost:8080/oauth2/authorization/google" class="login-button">
      Login with Google
    </a>
  </div>
</template>
<style scoped>
.login-button {
  display: inline-block;
  padding: 12px 24px;
  background-color: #4285F4;
  color: white;
  text-decoration: none;
  border-radius: 4px;
  font-weight: bold;
}

.login-container {
  color: white;
  display: flex;
  flex-direction: column;
  justify-content: center;   /* vertical centering */
  align-items: center;       /* horizontal centering */
  height: 100vh;             /* full screen height */
  text-align: center;        /* center text */
}
</style>