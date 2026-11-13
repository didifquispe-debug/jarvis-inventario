// Proyecto DYSA - JARVIS Inventario. Estado del sistema, micrófono, reconocimiento de voz y animación del reactor.

function medidor(id, pct, texto, alto) {
  const v = Math.max(0, Math.min(100, pct || 0));
  $('m-' + id).style.width = v + '%';
  $('m-' + id).classList.toggle('alto', !!alto);
  $('t-' + id).textContent = texto ?? (Math.round(v) + '%');
}
async function refreshSystem() {
  const s = await traer('/api/system');
  if (!s) return;
  medidor('cpu', s.cpu, null, s.cpu > 85); medidor('ram', s.ram, null, s.ram > 85); medidor('disk', s.disk, null, s.disk > 90);
  $('i-os').textContent = s.os;
  $('i-ip').textContent = s.ip + ':' + s.port;
  const m = s.uptimeMin;
  $('i-up').textContent = m < 60 ? m + ' min' : Math.floor(m / 60) + ' h ' + (m % 60) + ' min';
  negocio.ruc = s.negocioRuc || ''; negocio.direccion = s.negocioDireccion || '';
  if (s.monedaSimbolo && (s.monedaSimbolo !== moneda.simbolo || s.monedaNombre !== moneda.nombre)) {
    moneda.simbolo = s.monedaSimbolo; moneda.nombre = s.monedaNombre || moneda.nombre;
    refrescarDatos();
  }
  if ((s.negocio || '') !== negocio.nombre) {
    negocio.nombre = s.negocio || '';
    $('negocio').textContent = negocio.nombre || 'Asistente de inventario';
    document.title = negocio.nombre ? negocio.nombre + ' · J.A.R.V.I.S.' : 'J.A.R.V.I.S.';
    pintarSaludo();
  }
}
if (navigator.getBattery) {
  navigator.getBattery().then(b => {
    const upd = () => medidor('bat', b.level * 100, Math.round(b.level * 100) + (b.charging ? '% ⚡' : '%'), b.level < .2 && !b.charging);
    upd(); b.onlevelchange = upd; b.onchargingchange = upd;
  });
} else { medidor('bat', 0, 'N/D'); }

let analyser = null, freq = new Uint8Array(64), micLevel = 0;
async function initMicMeter() {
  if (analyser || !navigator.mediaDevices?.getUserMedia) return;
  try {
    const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
    const ctx = new (window.AudioContext || window.webkitAudioContext)();
    analyser = ctx.createAnalyser(); analyser.fftSize = 128;
    ctx.createMediaStreamSource(stream).connect(analyser);
    freq = new Uint8Array(analyser.frequencyBinCount);
  } catch (e) {}
}

const SR = window.SpeechRecognition || window.webkitSpeechRecognition;
const voiceOK = !!SR && window.isSecureContext;
let rec = null, recMode = null, handsFree = false, awaitingCommand = false;
const WAKE = /\b(jarvis|yarvis|harvis|jarbis|yarbis|charvis)\b/i;
function startRec(mode) {
  if (!voiceOK || dictando) return;
  stopRec();
  initMicMeter();
  rec = new SR();
  rec.lang = 'es-PE';
  rec.continuous = mode === 'wake';
  rec.interimResults = true;
  recMode = mode;
  rec.onresult = e => {
    let fin = '', interim = '';
    for (let i = e.resultIndex; i < e.results.length; i++) {
      const r = e.results[i];
      (r.isFinal ? (fin += r[0].transcript) : (interim += r[0].transcript));
    }
    if (mode === 'single' || awaitingCommand || WAKE.test(interim + fin)) transcriptEl.textContent = interim || fin;
    if (!fin) return;
    if (mode === 'single' || awaitingCommand) { awaitingCommand = false; send(fin, false, true); return; }
    const m = fin.match(WAKE);
    if (!m) return;
    const rest = fin.slice(m.index + m[0].length).replace(/^[\s,.:;]+/, '').trim();
    if (rest.length > 1) send(rest, false, true);
    else {
      awaitingCommand = true;
      stopRec();
      speak('¿Sí, señor?', () => { startRec('wake'); awaitingCommand = true; setState('listening'); });
    }
  };
  rec.onerror = e => {
    if (e.error === 'not-allowed' || e.error === 'service-not-allowed') {
      handsFree = false; pintarManosLibres();
      addEntry('sys', 'Permiso de micrófono denegado.');
    }
  };
  rec.onend = () => {
    const wasMode = recMode; rec = null;
    if (handsFree && (state === 'idle' || state === 'listening')) {
      setTimeout(() => { if (!rec && handsFree && (state === 'idle' || state === 'listening')) startRec('wake'); }, 250);
    } else if (wasMode === 'single' && state === 'listening') {
      setState('idle'); transcriptEl.textContent = ''; $('micBtn').classList.remove('activo');
    }
  };
  try { rec.start(); } catch (e) {}
  if (mode === 'single') { setState('listening'); $('micBtn').classList.add('activo'); }
}
function stopRec() {
  $('micBtn').classList.remove('activo');
  if (!rec) return;
  rec.onend = rec.onresult = rec.onerror = null;
  try { rec.abort(); } catch (e) {}
  rec = null;
}
function resumeIdle() {
  setState('idle');
  const pregunta = ultimaPregunta;
  ultimaPregunta = false;
  if (micPc) {
    escuchaPc(pregunta ? 'sigue-y-espera' : 'sigue');
    if (pregunta) setState('listening');
    return;
  }
  if (handsFree) { if (pregunta) awaitingCommand = true; startRec('wake'); }
}
function listenOnce() {
  if (micPc) {
    callar();
    escuchaPc('una-vez');
    setState('listening'); $('micBtn').classList.add('activo');
    return;
  }
  if (!voiceOK) { noVoiceHint(); return; }
  callar();
  awaitingCommand = false;
  startRec('single');
}
function noVoiceHint() {
  addEntry('sys', window.isSecureContext
    ? 'Este navegador no reconoce voz. Use Chrome o Edge, o escriba la orden.'
    : 'El micrófono requiere HTTPS en este dispositivo (vea el README). Puede escribir la orden.');
  $('input').focus();
}
function pintarManosLibres() {
  $('hfBtn').classList.toggle('activo', handsFree);
  $('hfBtn').setAttribute('aria-checked', String(handsFree));
}
$('reactor').onclick = listenOnce;
$('reactorGrande').onclick = listenOnce;
$('micBtn').onclick = listenOnce;
$('hfBtn').onclick = () => {
  if (micPc) {
    handsFree = !handsFree;
    pintarManosLibres();
    escuchaPc(handsFree ? 'manos-libres' : 'sin-manos-libres');
    addEntry('sys', handsFree ? 'Manos libres activado (micrófono de la computadora, sin Internet). Diga “Jarvis” seguido de su orden.'
                              : 'Manos libres desactivado.');
    setState(handsFree && state === 'idle' ? 'idle' : state);
    return;
  }
  if (!voiceOK) { noVoiceHint(); return; }
  handsFree = !handsFree;
  pintarManosLibres();
  if (handsFree) { addEntry('sys', 'Manos libres activado. Diga “Jarvis” seguido de su orden.'); if (state === 'idle') startRec('wake'); setState(state); }
  else { stopRec(); awaitingCommand = false; setState('idle'); }
};

const ES_ESTA_PC = ['localhost', '127.0.0.1', '[::1]'].includes(location.hostname);
let micPc = false, micPcUltimo = 0, ultimaPregunta = false;
const esperar = ms => new Promise(ok => setTimeout(ok, ms));
async function escuchaPc(accion) {
  try { const r = await api('/api/voz/escucha', { method: 'POST', body: JSON.stringify({ accion }) }); return r.ok ? r.json() : null; }
  catch (e) { return null; }
}
async function iniciarMicPc() {
  if (!ES_ESTA_PC) return;
  for (let i = 0; i < 45; i++) {
    let e;
    try { const r = await api('/api/voz/escucha'); if (!r.ok) return; e = await r.json(); } catch (x) { return; }
    if (e.disponible) {
      micPc = true; micPcUltimo = e.ultimo;
      if (handsFree) { stopRec(); escuchaPc('manos-libres'); }
      sondearMicPc();
      return;
    }
    if (!e.cargando) return;
    await esperar(2000);
  }
}
async function sondearMicPc() {
  while (micPc) {
    try {
      const r = await api('/api/voz/escucha/eventos?desde=' + micPcUltimo);
      if (!r.ok) { await esperar(3000); continue; }
      for (const ev of await r.json()) { micPcUltimo = Math.max(micPcUltimo, ev.id); alEventoMicPc(ev); }
    } catch (e) { await esperar(3000); }
  }
}
function alEventoMicPc(ev) {
  if (ev.tipo === 'parcial') {
    transcriptEl.textContent = ev.texto;
    if (state === 'idle') setState('listening');
  } else if (ev.tipo === 'orden') {
    $('micBtn').classList.remove('activo');
    send(ev.texto, false, true);
  } else if (ev.tipo === 'activado') {
    setState('listening');
    speak('¿Sí, señor?', () => { escuchaPc('sigue-y-espera'); setState('listening'); });
  } else if (ev.tipo === 'silencio') {
    $('micBtn').classList.remove('activo');
    if (state === 'listening') { setState('idle'); transcriptEl.textContent = ''; }
  } else if (ev.tipo === 'error') {
    micPc = false;
    addEntry('sys', ev.texto + ' Uso el micrófono del navegador.');
    if (handsFree && voiceOK) startRec('wake');
  }
}

let acento = '60,194,227';
function leerColores() { acento = getComputedStyle(document.documentElement).getPropertyValue('--accent-rgb').trim() || acento; }
leerColores();
let level = 0.05;
function dibujarOrbe(cv, t, fondoOscuro) {
  const dpr = window.devicePixelRatio || 1, w = cv.clientWidth || 46;
  if (!w) return;
  if (cv.width !== Math.round(w * dpr)) { cv.width = cv.height = Math.round(w * dpr); }
  const ctx = cv.getContext('2d');
  ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
  ctx.clearRect(0, 0, w, w);
  const col = state === 'thinking' ? '232,166,74' : fondoOscuro ? '63,203,234' : acento;
  const spd = state === 'thinking' ? 4 : state === 'speaking' ? 1.8 : 1;
  const c = w / 2, R = w / 2 - 1.5, TAU = Math.PI * 2;
  const arco = (r, ancho, segs, rot, alfa) => {
    ctx.save(); ctx.translate(c, c); ctx.rotate(rot);
    ctx.strokeStyle = `rgba(${col},${alfa})`; ctx.lineWidth = ancho; ctx.lineCap = 'round';
    for (const [a, b] of segs) { ctx.beginPath(); ctx.arc(0, 0, r, a, b); ctx.stroke(); }
    ctx.restore();
  };
  arco(R * 0.92, Math.max(1.4, R * 0.07), [[0, 1.3], [1.7, 3.2], [3.6, 4.4], [4.8, 6.0]], t * 0.0006 * spd, 0.9);
  arco(R * 0.74, Math.max(1, R * 0.05), [[0.3, 1.6], [2.4, 3.7], [4.5, 5.8]], -t * 0.0009 * spd, 0.5);
  const N = 28;
  ctx.save(); ctx.translate(c, c);
  for (let i = 0; i < N; i++) {
    const a = i / N * TAU - t * 0.0003;
    const f = state === 'listening' && analyser ? freq[i % freq.length] / 255 : 0.5 + 0.5 * Math.sin(i * 1.7 + t / 140);
    const v = level * (0.3 + f), r0 = R * 0.5, r1 = r0 + R * 0.04 + v * R * 0.2;
    ctx.strokeStyle = `rgba(${col},${0.25 + Math.min(level, 1) * 0.7})`; ctx.lineWidth = Math.max(1, R * 0.045);
    ctx.beginPath(); ctx.moveTo(Math.cos(a) * r0, Math.sin(a) * r0); ctx.lineTo(Math.cos(a) * r1, Math.sin(a) * r1); ctx.stroke();
  }
  ctx.restore();
  const cr = R * (0.26 + level * 0.12);
  const g = ctx.createRadialGradient(c, c, 0, c, c, cr * 1.6);
  g.addColorStop(0, 'rgba(255,255,255,1)'); g.addColorStop(0.3, `rgba(${col},0.95)`); g.addColorStop(1, `rgba(${col},0)`);
  ctx.fillStyle = g; ctx.beginPath(); ctx.arc(c, c, cr * 1.6, 0, TAU); ctx.fill();
}
function dibujarReactor(cv, t) {
  const dpr = window.devicePixelRatio || 1, w = cv.clientWidth;
  if (!w) return;
  if (cv.width !== Math.round(w * dpr)) { cv.width = cv.height = Math.round(w * dpr); }
  const ctx = cv.getContext('2d');
  ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
  ctx.clearRect(0, 0, w, w);
  const col = state === 'thinking' ? '255,180,60' : '79,216,255';
  const spd = state === 'thinking' ? 5 : state === 'speaking' ? 1.8 : 1;
  const c = w / 2, R = w / 2 - 4, TAU = Math.PI * 2;
  const arcs = (r, width, segs, rot, alpha) => {
    ctx.save(); ctx.translate(c, c); ctx.rotate(rot);
    ctx.strokeStyle = `rgba(${col},${alpha})`; ctx.lineWidth = width;
    for (const [a, b] of segs) { ctx.beginPath(); ctx.arc(0, 0, r, a, b); ctx.stroke(); }
    ctx.restore();
  };
  ctx.save(); ctx.translate(c, c); ctx.rotate(t * 0.00005 * spd);
  for (let i = 0; i < 180; i++) {
    const a = i / 180 * TAU, big = i % 15 === 0, len = big ? R * 0.05 : R * 0.022;
    ctx.strokeStyle = `rgba(${col},${big ? 0.9 : 0.3})`; ctx.lineWidth = big ? 2 : 1;
    ctx.beginPath(); ctx.moveTo(Math.cos(a) * R, Math.sin(a) * R); ctx.lineTo(Math.cos(a) * (R - len), Math.sin(a) * (R - len)); ctx.stroke();
  }
  ctx.restore();
  arcs(R * 0.89, R * 0.02, [[0, 1.1], [1.4, 2.2], [2.5, 3.9], [4.2, 4.6], [4.9, 6.0]], t * 0.0003 * spd, 0.85);
  arcs(R * 0.83, 1, [[0, TAU]], 0, 0.25);
  arcs(R * 0.76, R * 0.07, [[0.2, 0.9], [2.3, 3.0], [4.4, 5.1]], -t * 0.0005 * spd, 0.28);
  ctx.save(); ctx.translate(c, c); ctx.rotate(t * 0.0008 * spd); ctx.setLineDash([3, 7]);
  ctx.strokeStyle = `rgba(${col},.55)`; ctx.lineWidth = 2; ctx.beginPath(); ctx.arc(0, 0, R * 0.66, 0, TAU); ctx.stroke(); ctx.restore();
  ctx.save(); ctx.translate(c, c);
  const N = 72;
  for (let i = 0; i < N; i++) {
    const a = i / N * TAU - t * 0.0002;
    const f = state === 'listening' && analyser ? freq[i % freq.length] / 255 : 0.5 + 0.5 * Math.sin(i * 1.7 + t / 140);
    const v = level * (0.35 + f), r0 = R * 0.52, r1 = r0 + R * 0.015 + v * R * 0.13;
    ctx.strokeStyle = `rgba(${col},${0.3 + Math.min(level, 1) * 0.7})`; ctx.lineWidth = Math.max(1.5, R * 0.012);
    ctx.beginPath(); ctx.moveTo(Math.cos(a) * r0, Math.sin(a) * r0); ctx.lineTo(Math.cos(a) * r1, Math.sin(a) * r1); ctx.stroke();
  }
  ctx.restore();
  arcs(R * 0.46, 2, [[0, TAU]], 0, 0.5);
  const seg = []; for (let k = 0; k < 10; k++) seg.push([k * TAU / 10 + 0.06, (k + 1) * TAU / 10 - 0.06]);
  arcs(R * 0.40, R * 0.06, seg, -t * 0.0009 * spd, 0.75);
  arcs(R * 0.32, 1.5, [[0, TAU]], 0, 0.6);
  const cr = R * (0.2 + level * 0.09);
  const g = ctx.createRadialGradient(c, c, 0, c, c, cr * 1.7);
  g.addColorStop(0, 'rgba(255,255,255,1)'); g.addColorStop(0.25, `rgba(${col},0.95)`);
  g.addColorStop(0.6, `rgba(${col},0.25)`); g.addColorStop(1, `rgba(${col},0)`);
  ctx.fillStyle = g; ctx.beginPath(); ctx.arc(c, c, cr * 1.7, 0, TAU); ctx.fill();
}

function frame(t) {
  if (analyser) {
    analyser.getByteFrequencyData(freq);
    let sum = 0; for (let i = 0; i < freq.length; i++) sum += freq[i];
    micLevel = sum / freq.length / 255;
  }
  let target = 0.08 + Math.sin(t / 900) * 0.03;
  if (state === 'listening') target = 0.15 + micLevel * 2.4;
  else if (state === 'speaking') target = 0.35 + 0.25 * Math.abs(Math.sin(t / 85)) + speakKick;
  else if (state === 'thinking') target = 0.25;
  speakKick *= 0.88;
  level += (Math.min(target, 1) - level) * 0.15;
  dibujarOrbe($('reactor'), t, true);
  if (vista === 'inicio') dibujarReactor($('reactorGrande'), t);
  if (getComputedStyle($('abrirAsistente')).display !== 'none') dibujarOrbe($('orbeFab'), t, true);
  requestAnimationFrame(frame);
}
requestAnimationFrame(frame);
