import { createApp } from 'vue'
import ElementPlus from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import 'element-plus/dist/index.css'

import App from './App.vue'
import router from './router'
import pinia from './stores'

/**
 * 应用装配：Pinia（状态）→ Router（路由）→ Element Plus（UI）
 * 顺序说明：阶段1 的导航守卫要读取 user store，Pinia 必须先于 Router 安装。
 * Element Plus 采用全量引入（阶段0 骨架从简）；后续如需体积优化，
 * 可改为 unplugin-auto-import + unplugin-vue-components 按需引入。
 */
const app = createApp(App)

app.use(pinia)
app.use(router)
app.use(ElementPlus, { locale: zhCn })

app.mount('#app')
