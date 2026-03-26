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
  next(); 
})