// Proyecto DYSA (D: Didier · Y: Yoel · S: Sumi · A: Apaza).
// Las funciones y variables con prefijo dysa son del comprobante y su impresión.
const $ = id => document.getElementById(id);
const stateEl = $('state'), logEl = $('log'), transcriptEl = $('transcript');
const esc = s => String(s ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
const moneda = { simbolo: 'S/', nombre: 'soles' };
const soles = n => moneda.simbolo + ' ' + Number(n || 0).toLocaleString('es-PE', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
const cant = n => Number(n || 0).toLocaleString('es-PE', { maximumFractionDigits: 3 });
const nroComp = n => String(n).padStart(6, '0');
const sinTildes = s => String(s || '').normalize('NFD').replace(/[̀-ͯ]/g, '').toLowerCase();
const icono = (id, extra = '') => `<svg aria-hidden="true" focusable="false" ${extra}><use href="#i-${id}"/></svg>`;
function guardar(k, v) { try { localStorage.setItem(k, v); } catch (e) {} }
function leer(k) { try { return localStorage.getItem(k); } catch (e) { return null; } }

function saveToken(t) { guardar('jarvisToken', t); }
function loadToken() { return leer('jarvisToken'); }
(function () {
  const m = location.hash.match(/^#t=(.*)$/);
  if (m) { saveToken(decodeURIComponent(m[1])); history.replaceState(null, '', location.pathname); }
})();
function getToken() {
  let t = loadToken();
  if (!t) { t = prompt('Clave de JARVIS (sale en la bandeja de la laptop y en jarvis.log al crearse, o la de jarvis.token):') || ''; saveToken(t); }
  return t;
}
const SESSION_ID = (function () {
  const nuevo = () => Array.from(crypto.getRandomValues(new Uint8Array(16)), b => b.toString(16).padStart(2, '0')).join('');
  try {
    let s = sessionStorage.getItem('jarvisSession');
    if (!s) { s = nuevo(); sessionStorage.setItem('jarvisSession', s); }
    return s;
  } catch (e) { return nuevo(); }
})();
$('gear').onclick = () => { saveToken(''); getToken(); refrescarTodo(); };

function avisar(texto, mal) {
  const a = document.createElement('div');
  a.className = 'toast' + (mal ? ' mal' : '');
  a.textContent = texto;
  $('avisos').appendChild(a);
  setTimeout(() => a.remove(), mal ? 6500 : 4200);
}

async function api(path, opts = {}) {
  const r = await fetch(path, { ...opts, headers: { 'Content-Type': 'application/json', 'X-Jarvis-Token': getToken(),
                                                    'X-Jarvis-Session': SESSION_ID, 'X-Jarvis-Usuario': leer('jarvisUsuario') || '',
                                                    'X-Jarvis-Tienda': leer('jarvisTienda') || '',
                                                    ...(opts.headers || {}) } });
  if (r.status === 401) {
    if (r.headers.get('X-Jarvis-Login')) pedirLogin(); else saveToken('');
  }
  return r;
}
async function invCall(path, method, body) {
  try {
    const r = await api(path, { method, body: body ? JSON.stringify(body) : undefined });
    const data = await r.json().catch(() => ({ ok: false, reply: 'Respuesta inesperada del servidor.' }));
    if (r.status === 401) data.reply = 'Clave incorrecta. Use “Cambiar clave” para escribirla de nuevo.';
    return { ok: r.ok && data.ok !== false, reply: data.reply || '', data };
  } catch (e) {
    return { ok: false, reply: 'Sin conexión con el núcleo de JARVIS.' };
  }
}
async function traer(path) {
  try { const r = await api(path); return r.ok ? await r.json() : null; } catch (e) { return null; }
}

const VISTAS = ['inicio', 'resultados', 'vender', 'inventario', 'clientes', 'comprobantes', 'compras', 'proveedores'];
const PERMISO_VISTA = { resultados: 'REPORTES', vender: 'VENDER', inventario: 'INVENTARIO', clientes: 'CLIENTES',
  comprobantes: 'COMPROBANTES', compras: 'COMPRAS', proveedores: 'COMPRAS' };
let conUsuarios = false, yo = null;
const puede = p => !conUsuarios || !!(yo && yo.permisos.includes(p));
const vistaPermitida = v => !PERMISO_VISTA[v] || puede(PERMISO_VISTA[v]);
let vista = 'inicio';
function irA(v) {
  if (!VISTAS.includes(v) || !vistaPermitida(v)) v = 'inicio';
  vista = v;
  VISTAS.forEach(x => { $('v-' + x).hidden = x !== v; });
  document.querySelectorAll('.nav a.item[data-vista]').forEach(a => {
    a.classList.toggle('activo', a.dataset.vista === v);
    if (a.dataset.vista === v) a.setAttribute('aria-current', 'page'); else a.removeAttribute('aria-current');
  });
  $('vista').scrollTop = 0;
  if (v === 'inicio') refreshResumen();
  if (v === 'resultados') refreshResultados();
  if (v === 'clientes') refreshClientes();
  if (v === 'comprobantes') refreshComprobantes();
  if (v === 'vender') { renderPos(); setTimeout(() => $('posBuscar').focus(), 30); }
  if (v === 'inventario') { renderInventory(); refreshValorizado(); }
  if (v === 'compras') refreshCompras();
  if (v === 'proveedores') refreshProveedores();
}
window.addEventListener('hashchange', () => irA(location.hash.slice(1)));
document.querySelectorAll('[data-accion="nuevo-producto"]').forEach(b => b.onclick = () => openProduct(null));

function abrirAsistente(abrir) {
  $('asistente').classList.toggle('abierto', abrir);
  $('velo').classList.toggle('visible', abrir);
  if (abrir) setTimeout(() => $('input').focus(), 250);
}
$('abrirAsistente').onclick = () => abrirAsistente(true);
$('cerrarAsistente').onclick = () => abrirAsistente(false);
$('velo').onclick = () => abrirAsistente(false);

let state = 'idle';
const LABELS = { idle: 'En espera', listening: 'Escuchando…', thinking: 'Pensando…', speaking: 'Hablando…', wake: 'Diga “Jarvis”…' };
function setState(s) {
  state = s;
  stateEl.textContent = (s === 'idle' && handsFree) ? LABELS.wake : LABELS[s];
  stateEl.className = s === 'listening' ? 'escuchando' : s === 'thinking' ? 'pensando' : '';
  $('estadoGrande').textContent = stateEl.textContent;
  $('estadoGrande').className = 'hero-estado' + (s === 'thinking' ? ' pensando' : '');
}

const hhmm = () => new Date().toLocaleTimeString('es-PE', { hour: '2-digit', minute: '2-digit', hour12: false });
const MENOS_MOVIMIENTO = !!window.matchMedia?.('(prefers-reduced-motion: reduce)').matches;
function anunciar(texto) {
  const a = $('anuncio');
  a.textContent = '';
  setTimeout(() => { a.textContent = texto; }, 60);
}
function addEntry(who, text, typewriter) {
  const e = document.createElement('div');
  e.className = 'msg ' + (who === 'me' ? 'yo' : who === 'bot' ? 'jarvis' : 'sistema');
  e.innerHTML = '<span class="txt"></span>' + (who === 'sys' ? '' : `<span class="hora">${hhmm()}</span>`);
  logEl.appendChild(e);
  const box = e.querySelector('.txt');
  if (who !== 'me') anunciar(text);
  if (!typewriter || MENOS_MOVIMIENTO) { box.textContent = text; logEl.scrollTop = logEl.scrollHeight; return e; }
  let i = 0;
  const step = () => {
    i = Math.min(text.length, i + 3);
    box.textContent = text.slice(0, i);
    logEl.scrollTop = logEl.scrollHeight;
    if (i < text.length) setTimeout(step, 12);
  };
  step();
  return e;
}

let voice = null, speakKick = 0;
function pickVoice() {
  const es = speechSynthesis.getVoices().filter(v => v.lang.toLowerCase().startsWith('es'));
  const locales = es.filter(v => v.localService);
  const vs = locales.length ? locales : es;
  const score = v => (/natural|online/i.test(v.name) ? 4 : 0)
                   + (/alex|jorge|alvaro|álvaro|tomas|tomás|pablo|raul|raúl|diego|gonzalo|male|hombre/i.test(v.name) ? 3 : 0)
                   + (/pe|419|mx|us/i.test(v.lang) ? 1 : 0);
  voice = vs.sort((a, b) => score(b) - score(a))[0] || null;
}
if ('speechSynthesis' in window) { pickVoice(); speechSynthesis.onvoiceschanged = pickVoice; }
const usuarioActivo = () => !navigator.userActivation || navigator.userActivation.hasBeenActive;
const puedeHablar = () => hablaPc || ('speechSynthesis' in window && usuarioActivo());
let hablaPc = null, fraseVoz = 0;
function speak(text, then) {
  if (!puedeHablar() || !vozActiva) { then ? then() : resumeIdle(); return; }
  if (micPc) escuchaPc('pausa');
  const mia = ++fraseVoz;
  const fin = () => { if (mia === fraseVoz) { then ? then() : resumeIdle(); } };
  if (hablaPc) {
    window.speechSynthesis?.cancel();
    setState('speaking');
    const pulso = setInterval(() => { speakKick = 0.2 + Math.random() * 0.25; }, 140);
    api('/api/voz/hablar', { method: 'POST', body: JSON.stringify({ texto: text }) })
      .then(r => { clearInterval(pulso); if (!r.ok) throw new Error(); fin(); })
      .catch(() => {
        clearInterval(pulso);
        if (mia !== fraseVoz) return;
        hablaPc = null;
        addEntry('sys', 'La voz de la computadora no respondió; uso la del navegador.');
        hablarNavegador(text, fin);
      });
    return;
  }
  hablarNavegador(text, fin);
}
function hablarNavegador(text, fin) {
  if (!('speechSynthesis' in window)) { fin(); return; }
  speechSynthesis.cancel();
  const u = new SpeechSynthesisUtterance(text.replace(/[•\n]+/g, '. '));
  u.lang = voice ? voice.lang : 'es-ES';
  if (voice) u.voice = voice;
  u.rate = 1.04; u.pitch = 0.85;
  u.onstart = () => setState('speaking');
  u.onboundary = () => { speakKick = 0.35; };
  u.onend = u.onerror = fin;
  speechSynthesis.speak(u);
}
function callar() {
  fraseVoz++;
  if ('speechSynthesis' in window) speechSynthesis.cancel();
  if (hablaPc) api('/api/voz/callar', { method: 'POST' }).catch(() => {});
}
async function iniciarHablaPc() {
  if (!ES_ESTA_PC) return;
  try {
    const r = await api('/api/voz/habla');
    if (!r.ok) return;
    const e = await r.json();
    if (e.modo === 'piper' || e.modo === 'windows') hablaPc = e.modo;
  } catch (x) {}
}

async function send(text, silentUser, porVoz) {
  text = (text || '').trim();
  if (!text) return;
  if (ordenDePantalla(text, porVoz, silentUser)) return;
  stopRec();
  transcriptEl.textContent = '';
  if (!silentUser) addEntry('me', text);
  setState('thinking');
  try {
    const r = await api('/api/command', { method: 'POST', body: JSON.stringify({ text, voz: !!porVoz }) });
    const data = await r.json();
    const e = addEntry('bot', data.reply, true);
    ultimaPregunta = /\?\s*$/.test(data.reply || '');
    speak(data.reply);
    if (data.recibo) { dysaMostrarRecibo(data.recibo); dysaEnlaceRecibo(e, data.recibo); if (impresion.alCobrar) dysaImprimirRecibo(); }
    renderCart(data.carrito);
    if (/estado del sistema|diagnostico|diagnóstico/i.test(text)) { $('equipo').open = true; refreshSystem(); }
    refrescarDatos();
  } catch (e) {
    addEntry('sys', 'Sin conexión con el núcleo de JARVIS.');
    resumeIdle();
  }
}
$('input').addEventListener('keydown', e => { if (e.key === 'Enter') { send($('input').value); $('input').value = ''; } });
$('sendBtn').onclick = () => { send($('input').value); $('input').value = ''; };
document.addEventListener('click', e => {
  const b = e.target.closest('[data-cmd]');
  if (b) send(b.dataset.cmd);
});
