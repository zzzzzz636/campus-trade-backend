import { $, state, toast } from './core.js';
import { api } from './api.js';

let hooks = {
  navigate: () => { }, closeDetail: () => { }, loadCategories: async () => { },
  loadProducts: async () => { }, resetProductDraft: () => { }
};
const codeCountdownTimers = new Map();

export function configureAuth(options) {
  hooks = { ...hooks, ...options };
}

export function setAuthMode(mode) {
  const register = mode === 'register';
  const reset = mode === 'reset';
  $('login-form').hidden = register || reset;
  $('register-form').hidden = !register;
  $('reset-form').hidden = !reset;
  $('show-login').classList.toggle('active', !register && !reset);
  $('show-register').classList.toggle('active', register);
  $('auth-title').textContent = reset ? '重置密码' : register ? '加入校园好物圈' : '欢迎回来';
  $('auth-subtitle').textContent = reset ? '通过注册邮箱验证码设置新密码' : register ? '创建账号，开始分享和发现闲置' : '登录后浏览校园里的闲置好物';
}

export function logout(showMessage = true) {
  state.token = '';
  state.user = null;
  state.selected = null;
  state.editingProduct = null;
  sessionStorage.removeItem('campusTradeToken');
  hooks.closeDetail();
  hooks.resetProductDraft();
  $('app-view').hidden = true;
  $('auth-view').hidden = false;
  setAuthMode('login');
  if (showMessage) toast('已退出登录');
}

export async function finishLogin(token) {
  state.token = token;
  state.user = await api('/api/auth/me');
  sessionStorage.setItem('campusTradeToken', token);
  $('auth-view').hidden = true;
  $('app-view').hidden = false;
  const initial = (state.user.nickname || '同').trim().slice(0, 1);
  $('header-avatar').textContent = initial;
  $('profile-avatar').textContent = initial;
  $('profile-name').textContent = state.user.nickname;
  $('profile-email').textContent = state.user.email;
  hooks.navigate('home');
  try {
    await hooks.loadCategories();
    await hooks.loadProducts();
  } catch (error) {
    toast(`页面数据加载失败：${error.message}`, true);
  }
}

export function bindAuthEvents() {
  $('show-login').addEventListener('click', () => setAuthMode('login'));
  $('show-register').addEventListener('click', () => setAuthMode('register'));
  $('show-reset').addEventListener('click', () => setAuthMode('reset'));
  $('reset-back').addEventListener('click', () => setAuthMode('login'));
  $('send-reset-code').addEventListener('click', () => sendVerificationCode(true));
  $('reset-form').addEventListener('submit', resetPassword);
  $('logout').addEventListener('click', () => logout());
  $('send-code').addEventListener('click', () => sendVerificationCode(false));
  $('login-form').addEventListener('submit', login);
  $('register-form').addEventListener('submit', register);
}

export async function restoreSession() {
  const token = sessionStorage.getItem('campusTradeToken');
  if (!token) return;
  try { await finishLogin(token); }
  catch (error) {
    logout(false);
    toast(`请重新登录：${error.message}`, true);
  }
}

async function sendVerificationCode(reset = false) {
  const form = $(reset ? 'reset-form' : 'register-form');
  const emailInput = form.elements.email;
  if (!emailInput.reportValidity()) return;
  const button = $(reset ? 'send-reset-code' : 'send-code');
  button.disabled = true;
  try {
    const result = await api(reset ? '/api/auth/password-reset-codes' : '/api/auth/verification-codes', {
      method: 'POST', body: JSON.stringify({ email: emailInput.value.trim() })
    });
    let remaining = 60;
    clearInterval(codeCountdownTimers.get(button.id));
    button.textContent = `${remaining} 秒后重试`;
    const timer = setInterval(() => {
      remaining--;
      if (remaining <= 0) {
        clearInterval(timer);
        codeCountdownTimers.delete(button.id);
        button.textContent = '重新获取';
        button.disabled = false;
      } else button.textContent = `${remaining} 秒后重试`;
    }, 1000);
    codeCountdownTimers.set(button.id, timer);
    toast(result.deliveryMode === 'SMTP' ? '验证码邮件已发送，请检查邮箱' : '本地验证码已生成，请查看后端控制台');
  } catch (error) {
    button.disabled = false;
    toast(`验证码发送失败：${error.message}`, true);
  }
}

async function resetPassword(event) {
  event.preventDefault();
  const form = event.currentTarget;
  const button = form.querySelector('button[type="submit"]');
  const data = new FormData(form);
  button.disabled = true;
  try {
    await api('/api/auth/password-reset', { method: 'POST', body: JSON.stringify({
      email: String(data.get('email')), code: String(data.get('code')),
      newPassword: String(data.get('newPassword'))
    }) });
    form.reset(); setAuthMode('login'); toast('密码已重置，请用新密码登录');
  } catch (error) { toast(`重置失败：${error.message}`, true); }
  finally { button.disabled = false; }
}

async function login(event) {
  event.preventDefault();
  const formElement = event.currentTarget;
  const form = new FormData(event.currentTarget);
  const button = event.currentTarget.querySelector('button[type="submit"]');
  button.disabled = true;
  try {
    const result = await api('/api/auth/login', {
      method: 'POST',
      body: JSON.stringify({ email: String(form.get('email')), password: String(form.get('password')) })
    });
    await finishLogin(result.accessToken);
    formElement.reset();
    toast('登录成功，欢迎来到商品广场');
  } catch (error) {
    toast(`登录失败：${error.message}`, true);
  } finally { button.disabled = false; }

}

async function register(event) {
  event.preventDefault();
  const formElement = event.currentTarget;
  const form = new FormData(event.currentTarget);
  const email = String(form.get('email'));
  const password = String(form.get('password'));
  const button = event.currentTarget.querySelector('button[type="submit"]');
  button.disabled = true;
  try {
    await api('/api/auth/register', { method: 'POST', body: JSON.stringify({
      nickname: String(form.get('nickname')), email, password, code: String(form.get('code'))
    }) });
    const result = await api('/api/auth/login', {
      method: 'POST', body: JSON.stringify({ email, password })
    });
    await finishLogin(result.accessToken);
    formElement.reset();
    toast('注册成功，欢迎来到商品广场');
  } catch (error) {
    toast(`注册失败：${error.message}`, true);
  } finally { button.disabled = false; }
}
