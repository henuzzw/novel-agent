<script setup lang="ts">
import { computed, getCurrentInstance, onUnmounted, ref, watch } from 'vue'
import { useQuery, useQueryClient } from '@tanstack/vue-query'
import { LogIn, LogOut, RefreshCw, X, Check } from 'lucide-vue-next'
import { beginChatGptLogin, cancelChatGptLogin, chooseChatGptTransport, completeChatGptLogin,
  getChatGptConnection, logoutChatGpt, type ChatGptTransport } from '@/api/chatGptConnection'

const emit = defineEmits<{ transport: [value: ChatGptTransport] }>()
const client = useQueryClient()
const managementKey = ref('')
const unlocked = ref(false)
const queryKey = ['chatgpt-connection', getCurrentInstance()?.uid]
const connection = useQuery({ queryKey, queryFn: () => getChatGptConnection(managementKey.value), enabled: unlocked, retry: false,
  refetchInterval: query => ['PENDING', 'CONNECTING'].includes(query.state.data?.auth.loginStatus ?? '') ? 2000 : false })
const busy = ref(false)
const error = ref('')
const notice = ref('')
const callbackUrl = ref('')
onUnmounted(() => { managementKey.value = ''; callbackUrl.value = ''; client.removeQueries({ queryKey }) })
const data = computed(() => unlocked.value && !connection.isError.value ? connection.data.value : undefined)
const pending = computed(() => ['PENDING', 'CONNECTING'].includes(data.value?.auth.loginStatus ?? ''))
watch(() => data.value?.choice.transport, value => { if (value) emit('transport', value) }, { immediate: true })
watch(() => data.value?.auth.loginStatus, async (value, old) => {
  if (value === 'SUCCEEDED' && old !== value) { callbackUrl.value = ''; await client.invalidateQueries({ queryKey: ['global-chatgpt-models'] }) }
})

async function action(task: () => Promise<void>) {
  busy.value = true; error.value = ''; notice.value = ''
  try { await task(); await connection.refetch() }
  catch (failure) { error.value = failure instanceof Error ? failure.message : '连接操作失败' }
  finally { busy.value = false }
}
function login() {
  // Open during the user gesture, then redirect after the server starts its loopback listener.
  const popup = window.open('about:blank', '_blank')
  if (popup) popup.opener = null
  void action(async () => {
    try {
      const value = await beginChatGptLogin(managementKey.value)
      if (popup) popup.location.href = value.authorizationUrl
      else { await cancelChatGptLogin(managementKey.value); throw new Error('登录窗口被浏览器拦截，请允许弹出窗口后重试') }
    } catch (failure) { popup?.close(); throw failure }
  })
}
function choose(transport: ChatGptTransport) {
  if (!data.value || transport === data.value.choice.transport) return
  void action(async () => {
    await chooseChatGptTransport(managementKey.value, transport, data.value!.choice.version)
    await client.invalidateQueries({ queryKey: ['global-chatgpt-models'] })
  })
}
function complete() {
  if (!data.value?.auth.attemptId) return
  void action(async () => { await completeChatGptLogin(managementKey.value, data.value!.auth.attemptId!, callbackUrl.value.trim()); callbackUrl.value = '' })
}
</script>
<template>
  <div class="chatgpt-connection">
    <header><strong>ChatGPT 接入</strong><button type="button" class="icon-button" title="刷新连接状态" aria-label="刷新连接状态" :disabled="busy" @click="connection.refetch()"><RefreshCw :size="16" /></button></header>
    <form v-if="!data" @submit.prevent="unlocked = true; connection.refetch()"><label for="chatgpt-admin-key">连接管理口令</label><input id="chatgpt-admin-key" v-model="managementKey" type="password" autocomplete="off" maxlength="1024"><button type="submit" class="button" :disabled="!managementKey">解锁</button></form>
    <p v-if="connection.isFetching.value" role="status">正在读取连接状态…</p>
    <p v-if="connection.isError.value" role="alert" class="form-error">{{ connection.error.value?.message }}</p>
    <template v-if="data">
      <fieldset :disabled="busy || pending"><legend>请求入口</legend>
        <label><input type="radio" name="chatgpt-transport" :checked="data.choice.transport === 'APP_SERVER'" @click.prevent="choose('APP_SERVER')">Codex App Server</label>
        <label><input type="radio" name="chatgpt-transport" :checked="data.choice.transport === 'SIWC_HTTP'" :disabled="!data.auth.connected || !data.auth.canGenerate" @click.prevent="choose('SIWC_HTTP')">ChatGPT OAuth 直连（Beta）</label>
      </fieldset>
      <div class="connection-actions">
        <span class="connection-status"><Check v-if="data.auth.connected" :size="15" />{{ data.auth.connected ? (data.auth.email ?? 'ChatGPT 已连接') : '直连账号未连接' }}</span>
        <button type="button" class="button oauth-login" :disabled="busy || pending" @click="login"><LogIn :size="16" />Continue with ChatGPT</button>
        <button v-if="data.auth.connected" type="button" class="icon-button" title="断开 ChatGPT 直连账号" aria-label="断开 ChatGPT 直连账号" :disabled="busy || pending" @click="action(async () => { notice = (await logoutChatGpt(managementKey)).message })"><LogOut :size="16" /></button>
      </div>
      <p v-if="data.auth.connected && !data.auth.canGenerate" role="alert" class="form-error">已登录，但未授权模型用量。请重新授权。</p>
      <div v-if="pending" class="login-pending">
        <span role="status">{{ data.auth.loginStatus === 'CONNECTING' ? '正在校验授权…' : '等待 ChatGPT 授权…' }}</span>
        <button type="button" class="icon-button" title="取消本次登录" aria-label="取消本次登录" :disabled="busy" @click="action(async () => { await cancelChatGptLogin(managementKey) })"><X :size="16" /></button>
        <form @submit.prevent="complete"><label for="chatgpt-callback">登录回调地址（远程访问）</label><input id="chatgpt-callback" v-model="callbackUrl" type="text" autocomplete="off" spellcheck="false" placeholder="http://127.0.0.1:…/auth/callback?…"><button type="submit" class="button" :disabled="busy || !callbackUrl.trim() || data.auth.loginStatus !== 'PENDING'">完成登录</button></form>
      </div>
      <p v-if="data.auth.error" role="alert" class="form-error">{{ data.auth.error }}</p>
    </template>
    <p v-if="error" class="form-error" role="alert">{{ error }}</p><p v-if="notice" role="status">{{ notice }}</p>
  </div>
</template>
<style scoped>
.chatgpt-connection { width: 100%; border-top: 1px solid #d8dce0; margin-top: 16px; padding-top: 12px; }
header, .connection-actions, .login-pending { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; }
header { justify-content: space-between; }
fieldset { display: flex; gap: 16px; flex-wrap: wrap; padding: 0; border: 0; margin: 12px 0; min-width: 0; }
legend { font-size: 13px; margin-bottom: 8px; }
fieldset label { display: flex; align-items: center; gap: 6px; font-size: 13px; }
.connection-status { display: inline-flex; align-items: center; gap: 6px; overflow-wrap: anywhere; }
.oauth-login { background: #202020; color: #fff; border-color: #202020; }
.login-pending { margin-top: 12px; }
form { display: flex; align-items: end; gap: 8px; flex-wrap: wrap; width: 100%; }
form label { font-size: 13px; flex-basis: 100%; }
form input { flex: 1; min-width: 0; min-height: 36px; }
p { font-size: 13px; overflow-wrap: anywhere; }
@media (max-width: 640px) { form input { flex-basis: 100%; } .connection-status { width: 100%; } }
</style>
