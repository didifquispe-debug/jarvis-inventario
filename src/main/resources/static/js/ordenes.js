// Proyecto DYSA - JARVIS Inventario. Atajos de teclado y órdenes de pantalla por voz o texto (ir a, imprimir, dictar en ventanas).

function refrescarDatos() {
  refreshInventory(); refreshCart();
  if (vista === 'comprobantes') refreshComprobantes();
  if (vista === 'clientes') refreshClientes();
  if (vista === 'inicio') refreshResumen();
  if (vista === 'compras') refreshCompras();
}
function refrescarTodo() { refreshSystem(); refrescarDatos(); }
const visible = el => !!el && !!(el.offsetWidth || el.offsetHeight || el.getClientRects().length);
const ENFOCABLE = 'button:not([disabled]), [href], input:not([disabled]):not([type=hidden]), select:not([disabled]), '
                + 'textarea:not([disabled]), [tabindex]:not([tabindex="-1"])';
const modalAbierto = () => [...document.querySelectorAll('.modal.show')].pop() || null;

document.querySelectorAll('svg:not([aria-hidden])').forEach(v => { v.setAttribute('aria-hidden', 'true'); v.setAttribute('focusable', 'false'); });

document.querySelectorAll('.modal').forEach(m => new MutationObserver(() => {
  const abierta = m.classList.contains('show');
  if (abierta === (m.dataset.abierta === '1')) return;
  m.dataset.abierta = abierta ? '1' : '';
  if (abierta) {
    m._antes = document.activeElement;
    setTimeout(() => { if (!m.contains(document.activeElement)) [...m.querySelectorAll(ENFOCABLE)].find(visible)?.focus(); }, 60);
  } else if (!modalAbierto() && m._antes && document.contains(m._antes) && visible(m._antes)) {
    m._antes.focus();
  }
}).observe(m, { attributes: true, attributeFilter: ['class'] }));
document.addEventListener('keydown', e => {
  if (e.key === 'Tab') {
    const m = modalAbierto();
    if (!m) return;
    const f = [...m.querySelectorAll(ENFOCABLE)].filter(visible);
    if (!f.length) return;
    const i = f.indexOf(document.activeElement);
    if (e.shiftKey && i <= 0) { e.preventDefault(); f[f.length - 1].focus(); }
    else if (!e.shiftKey && (i === -1 || i === f.length - 1)) { e.preventDefault(); f[0].focus(); }
  } else if ((e.key === 'Enter' || e.key === ' ') && e.target.matches?.('tr.clic, canvas[role="button"]')) {
    e.preventDefault();
    e.target.click();
  }
});

new MutationObserver(() => $('micBtn').setAttribute('aria-pressed', String($('micBtn').classList.contains('activo'))))
  .observe($('micBtn'), { attributes: true, attributeFilter: ['class'] });

function enfocar(id) {
  if (id === 'input') {
    if (!visible($('input'))) abrirAsistente(true);
    $('input').focus();
    return;
  }
  const h = $('v-' + vista)?.querySelector('h1, h2');
  if (h) { h.tabIndex = -1; h.focus(); }
}
document.querySelectorAll('[data-saltar]').forEach(b => b.onclick = () => enfocar(b.dataset.saltar));
function irAyEnfocar(v) {
  if (location.hash.slice(1) === v) irA(v); else location.hash = v;
  setTimeout(() => { if (!$('v-' + v).contains(document.activeElement)) enfocar('vista'); }, 80);
}

let vozActiva = leer('jarvis.voz') !== 'no';
function pintarVoz() { $('vozBtn').classList.toggle('activo', vozActiva); $('vozBtn').setAttribute('aria-checked', String(vozActiva)); }
$('vozBtn').onclick = () => {
  vozActiva = !vozActiva;
  guardar('jarvis.voz', vozActiva ? 'si' : 'no');
  pintarVoz();
  if (!vozActiva) { callar(); resumeIdle(); }
  addEntry('sys', vozActiva ? 'Voz de JARVIS encendida.' : 'Voz de JARVIS apagada: las respuestas se leen en pantalla y en su lector de pantalla.');
};
pintarVoz();

function abrirAtajos() { $('atajosModal').classList.add('show'); }
$('atajosBtn').onclick = abrirAtajos;
$('atajosCerrar').onclick = () => $('atajosModal').classList.remove('show');

const VISTAS_ATAJO = ['inicio', 'vender', 'inventario', 'clientes', 'comprobantes', 'compras', 'proveedores'];
document.addEventListener('keydown', e => {
  if (!e.altKey || e.ctrlKey || e.metaKey) return;
  const k = e.code;
  let hecho = true;
  if (/^Digit[1-7]$/.test(k)) irAyEnfocar(VISTAS_ATAJO[Number(k.slice(5)) - 1]);
  else if (k === 'KeyH') listenOnce();
  else if (k === 'KeyM') $('hfBtn').click();
  else if (k === 'KeyO') enfocar('input');
  else if (k === 'KeyC') { callar(); resumeIdle(); }
  else if (k === 'KeyV') $('vozBtn').click();
  else if (k === 'KeyA') abrirAtajos();
  else hecho = false;
  if (hecho) e.preventDefault();
});

const normalDicho = t => String(t).toLowerCase().normalize('NFD').replace(/[̀-ͯ]/g, '')
  .replace(/[¿?¡!.,;:]/g, ' ').replace(/\s+/g, ' ').trim();
const SECCIONES = { inicio: 'inicio', vender: 'vender', ventas: 'vender', inventario: 'inventario', productos: 'inventario',
  clientes: 'clientes', comprobantes: 'comprobantes', boletas: 'comprobantes', compras: 'compras', proveedores: 'proveedores',
  resultados: 'resultados', ganancias: 'resultados', reportes: 'resultados', panel: 'resultados' };
const FORMULARIOS = [
  [/^(nuevo producto|agregar producto|crear producto|registrar (un )?producto)$/, () => openProduct(null), 'Formulario de producto nuevo'],
  [/^(registrar|registra|agregar|agrega|crear) (un )?cliente$/, () => abrirCliente(null), 'Formulario de cliente nuevo'],
  [/^(nueva compra|registrar (una )?compra|registra (una )?compra)$/, () => abrirCompra(), 'Formulario de compra nueva'],
  [/^(nuevo proveedor|registrar (un )?proveedor|agregar proveedor)$/, () => abrirProveedor(null), 'Formulario de proveedor nuevo'],
  [/^(cargar lista|cargar (una )?lista de productos)$/, () => openLote(), 'Cargar lista de productos'],
  [/^(atajos|atajos de teclado|ayuda de teclado)$/, () => abrirAtajos(), 'Atajos de teclado y voz'],
];
function ordenDePantalla(text, porVoz, silentUser) {
  const t = normalDicho(text).replace(/^(oye |hey )?jarvis /, '');
  const m = modalAbierto();
  const imp = dysaOrdenDeImprimir(t);
  if (imp && (!m || m.id === 'receipt')) { dysaImprimirPorOrden(text, imp, silentUser); return true; }
  if (m && porVoz) return dictarEnVentana(m, text, t, silentUser);
  let hecho = null;
  const sec = t.match(/^(?:ir a|ve a|vamos a|abre|abrir|abreme|muestra|muestrame|mostrar|ver|pantalla)(?: la)?(?: pantalla)?(?: de)?(?: los| las| el)? (\w+)$/);
  if (sec && SECCIONES[sec[1]]) {
    const v = SECCIONES[sec[1]];
    if (!vistaPermitida(v)) hecho = 'Su usuario no tiene acceso a la pantalla ' + document.querySelector(`.nav a[data-vista="${v}"]`).textContent.trim() + '.';
    else {
      irAyEnfocar(v);
      hecho = 'Pantalla ' + document.querySelector(`.nav a[data-vista="${v}"]`).textContent.trim() + '.';
    }
  } else {
    for (const [re, abrir, nombre] of FORMULARIOS) if (re.test(t)) { abrir(); hecho = nombre + '. Dicte el primer campo.'; break; }
  }
  if (!hecho) return false;
  responderPantalla(text, hecho, silentUser, !!modalAbierto());
  return true;
}
// DYSA: comando de voz y texto "imprime la boleta" / "imprime el comprobante 5", sin Internet.
const DYSA_PALABRAS_IMPRIMIR =new Set(['la', 'el', 'lo', 'eso', 'esa', 'ese', 'esta', 'este', 'mi', 'ultima', 'ultimo', 'de', 'nuevo', 'otra', 'vez',
  'por', 'favor', 'boleta', 'ticket', 'recibo', 'comprobante', 'nota', 'venta', 'numero', 'nro', 'n', 'jarvis', 'senor', 'y', 'mil']);
function dysaOrdenDeImprimir(t) {
  const m = t.replace(/-/g, ' ').match(/^(?:por favor )?(?:(imprime|imprimir|imprimeme|imprima|imprimelo|imprimela|reimprime|vuelve a imprimir)|(?:saca|sacame|saque))((?: \S+)*)$/);
  if (!m) return null;
  const resto = m[2].trim().split(' ').filter(Boolean);
  if (!m[1] && !resto.some(w => /^(boleta|ticket|recibo|comprobante|nota)$/.test(w))) return null;
  if (!resto.every(w => DYSA_PALABRAS_IMPRIMIR.has(w) || Object.hasOwn(NUMEROS, w) || /^\d+$/.test(w) || /^(b|nv)\d+$/.test(w))) return null;
  const desde = Math.max(...resto.map((w, i) => /^(boleta|ticket|recibo|comprobante|venta|numero|nro|n)$/.test(w) ? i : -1));
  const cifras = resto.slice(desde + 1).filter(w => w === 'y' || w === 'mil' || Object.hasOwn(NUMEROS, w) || /^\d+$/.test(w));
  const numero = cifras.length ? enteroDe(cifras) : null;
  const tipo = resto.includes('boleta') || resto.some(w => /^b\d+$/.test(w)) ? 'BOLETA'
             : resto.includes('nota') || resto.some(w => /^nv\d+$/.test(w)) ? 'NOTA_VENTA' : null;
  return { numero: numero || null, tipo };
}
async function dysaComprobanteParaImprimir({ numero, tipo }) {
  const filas = await traer('/api/inventory/comprobantes?limite=1000') || [];
  const iguales = filas.filter(c => Number((c.codigo || '').split('-')[1]) === numero && (!tipo || c.tipo === tipo));
  if (iguales.length > 1) return { aviso: `Hay varios comprobantes con el número ${numero}, señor. Diga “imprime la boleta ${numero}” o “imprime la nota de venta ${numero}”.` };
  const c = await traer('/api/inventory/comprobantes/' + (iguales.length ? iguales[0].numero : numero));
  return c ? { recibo: { comprobante: c, negocio } } : { aviso: `No encontré el comprobante ${numero}, señor.` };
}
async function dysaImprimirPorOrden(text, orden, silentUser) {
  if (!silentUser) addEntry('me', text);
  stopRec();
  transcriptEl.textContent = '';
  let r = dysaUltimoRecibo;
  if (orden.numero) {
    setState('thinking');
    const res = await dysaComprobanteParaImprimir(orden);
    if (res.aviso) { responderPantalla(text, res.aviso, true, false); return; }
    r = res.recibo;
    dysaMostrarRecibo(r);
  }
  if (!r) { responderPantalla(text, 'No hay un comprobante reciente para imprimir, señor.', true, false); return; }
  dysaUltimoRecibo = r;
  const c = r.comprobante;
  responderPantalla(text, 'Imprimiendo la ' + tipoCorto(c.tipo).toLowerCase() + (orden.numero ? ' ' + codigoDe(c) : '') + '.', true, false);
  dysaImprimirRecibo();
}
function responderPantalla(text, respuesta, silentUser, esperarRespuesta) {
  if (!silentUser) addEntry('me', text);
  addEntry('bot', respuesta);
  ultimaPregunta = esperarRespuesta;
  speak(respuesta);
}
const camposDe = m => [...m.querySelectorAll('input:not([type=hidden]):not([disabled]), select:not([disabled]), textarea:not([disabled])')].filter(visible);
function nombreCampo(el) {
  if (!el) return 'Campo';
  const n = el.getAttribute('aria-label') || el.labels?.[0]?.firstChild?.textContent || el.placeholder || el.title || 'Campo';
  return n.trim().replace(/[:\s]+$/, '');
}
function dictarEnVentana(m, text, t, silentUser) {
  const campos = camposDe(m);
  let i = campos.indexOf(document.activeElement);
  const principal = m.querySelector('button[type=submit], .modal-btns .boton.primario, .modal-btns .boton.peligro');
  const cerrarBtn = [...m.querySelectorAll('.modal-btns button')].find(b => /cancelar|cerrar/i.test(b.textContent));
  let r = null;
  if (/^(siguiente|siguiente campo|proximo|proximo campo)$/.test(t) && campos.length) {
    i = Math.min(campos.length - 1, i + 1); campos[i].focus(); r = nombreCampo(campos[i]) + ': ' + (campos[i].value || 'vacío');
  } else if (/^(anterior|campo anterior|atras)$/.test(t) && campos.length) {
    i = Math.max(0, i - 1); campos[i].focus(); r = nombreCampo(campos[i]) + ': ' + (campos[i].value || 'vacío');
  } else if (/^(guardar|guarda|aceptar|confirmar|confirmo|si|imprimir|imprime)$/.test(t) && principal) {
    r = principal.textContent.trim() + '.'; principal.click();
  } else if (/^(cancelar|cancela|cerrar|cierra|salir|no)$/.test(t)) {
    if (cerrarBtn) cerrarBtn.click(); else document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }));
    r = 'Ventana cerrada.';
  } else if (/^(leer|lee|que dice|donde estoy)$/.test(t)) {
    r = i >= 0 ? nombreCampo(campos[i]) + ': ' + (campos[i].value || 'vacío')
               : (m.querySelector('h3')?.textContent || m.getAttribute('aria-label') || 'Ventana') + '.';
  } else if (/^(borrar|borra|limpiar|limpia)( campo)?$/.test(t) && i >= 0) {
    escribirEn(campos[i], ''); r = nombreCampo(campos[i]) + ': vacío';
  } else if (campos.length) {
    const el = i >= 0 ? campos[i] : campos[0];
    el.focus();
    escribirEn(el, text.replace(/^(oye |hey )?jarvis[,.]?\s*/i, '').trim());
    r = nombreCampo(el) + ': ' + (el.value || 'no entendí el valor');
  }
  if (r == null) return false;
  responderPantalla(text, r, silentUser, !!modalAbierto());
  return true;
}
const NUMEROS = { cero: 0, un: 1, uno: 1, una: 1, dos: 2, tres: 3, cuatro: 4, cinco: 5, seis: 6, siete: 7, ocho: 8, nueve: 9, diez: 10,
  once: 11, doce: 12, trece: 13, catorce: 14, quince: 15, dieciseis: 16, diecisiete: 17, dieciocho: 18, diecinueve: 19, veinte: 20,
  veintiun: 21, veintiuno: 21, veintiuna: 21, veintidos: 22, veintitres: 23, veinticuatro: 24, veinticinco: 25, veintiseis: 26,
  veintisiete: 27, veintiocho: 28, veintinueve: 29, treinta: 30, cuarenta: 40, cincuenta: 50, sesenta: 60, setenta: 70, ochenta: 80,
  noventa: 90, cien: 100, ciento: 100, doscientos: 200, doscientas: 200, trescientos: 300, trescientas: 300, cuatrocientos: 400,
  quinientos: 500, seiscientos: 600, setecientos: 700, ochocientos: 800, novecientos: 900 };
function enteroDe(palabras) {
  let total = 0, actual = 0, alguno = false;
  for (const w of palabras) {
    if (w === 'y') continue;
    if (w === 'mil') { total += (actual || 1) * 1000; actual = 0; alguno = true; continue; }
    if (/^\d+$/.test(w)) { actual += Number(w); alguno = true; continue; }
    if (!(w in NUMEROS)) return null;
    actual += NUMEROS[w]; alguno = true;
  }
  return alguno ? total + actual : null;
}
function numeroDe(texto) {
  const t = normalDicho(String(texto).replace(/(\d),(\d)/g, '$1.$2').replace(/(\d)\.(\d)/g, '$1p$2'))
    .replace(/\b(soles?|sol|s\/|unidades?|pares?|metros?|kilos?)\b/g, ' ').replace(/\s+/g, ' ').trim().replace(/(\d)p(\d)/g, '$1.$2');
  if (/^\d+(\.\d+)?$/.test(t)) return t;
  const [ent, dec] = t.split(/ (?:con|punto|coma) /);
  const e = enteroDe(ent.split(' ').filter(Boolean));
  if (e == null) return null;
  if (!dec) return String(e);
  const d = enteroDe(dec.split(' ').filter(Boolean));
  return d == null ? null : e + '.' + String(d).padStart(2, '0');
}
function digitosDe(texto) {
  const ws = normalDicho(texto).split(' ').filter(Boolean);
  let s = '';
  for (const w of ws) { const n = /^\d+$/.test(w) ? w : NUMEROS[w]; if (n == null) return null; s += String(n); }
  return s;
}
function escribirEn(el, texto) {
  if (el.tagName === 'SELECT') {
    const t = normalDicho(texto);
    const o = [...el.options].find(o => t && (normalDicho(o.text).includes(t) || t.includes(normalDicho(o.text))));
    if (o) el.value = o.value;
  } else if (el.type === 'checkbox') {
    el.checked = !/^(no|desmarcar|quitar)/.test(normalDicho(texto));
  } else if (texto && el.inputMode === 'numeric') {
    const d = digitosDe(texto); if (d != null) el.value = d;
  } else if (texto && (el.type === 'number' || el.inputMode === 'decimal')) {
    const n = numeroDe(texto); if (n != null) el.value = n;
  } else {
    el.value = texto;
  }
  el.dispatchEvent(new Event('input', { bubbles: true }));
  el.dispatchEvent(new Event('change', { bubbles: true }));
}
