import { createApp } from 'vue'
import App from './App.vue'
import { router } from './router' // <--- Import the router you exported
import './style.css'

const app = createApp(App)

app.use(router) // <--- THIS IS THE MAGIC LINE YOU MIGHT BE MISSING
app.mount('#app')