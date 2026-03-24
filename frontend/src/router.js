import { createRouter, createWebHistory } from 'vue-router'
import Login from './components/Login.vue'
import ChatApp from './components/ChatApp.vue'

const routes = [
  // Option A: Make the root path show the Login component
  { path: '/', component: Login }, 

  { path: '/chat', component: ChatApp }
]

export const router = createRouter({
  history: createWebHistory(),
  routes
})

router.beforeEach((to, from, next) => {
  // Simple check for the session cookie
  const isAuthenticated = document.cookie.includes('JSESSIONID'); 
  
  // If they try to hit /chat without being logged in
  if (to.path === '/chat' && !isAuthenticated) {
    next('/'); // Send them back to the root (which now shows Login)
  } else {
    next(); // Carry on
  }
})