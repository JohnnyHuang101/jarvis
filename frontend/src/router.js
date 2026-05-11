import { createRouter, createWebHistory } from 'vue-router'
import Login from './components/Login.vue'
import ChatApp from './components/ChatApp.vue'
import JarvisLoader from './components/JarvisLoader.vue'
import Home from './components/Home.vue'
import CourseSchedule from './components/CourseSchedule.vue'; // The new page
import StudyPlanView from './components/StudyPlanView.vue'; // The new page


const routes = [
  // Option A: Make the root path show the Login component
  { path: '/', component: Login },

  { path: '/chat', component: ChatApp },
  { path: '/loading', component: JarvisLoader },
  { path: '/home', component: Home },
  {
    path: '/course/:courseId',
    name: 'CourseSchedule',
    component: CourseSchedule
  },
  {
    path: '/course/:courseId/plan/:examName',
    name: 'StudyPlanView',
    component: StudyPlanView,
    props: true // This allows the route params to be passed as props if needed
  }
]

export const router = createRouter({
  history: createWebHistory(),
  routes
})


router.beforeEach((to, from, next) => {
  next();
})