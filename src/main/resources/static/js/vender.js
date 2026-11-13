// Proyecto DYSA - JARVIS Inventario. Pantalla Vender: búsqueda, lector de código de barras, cliente y cobro.

let posLineas = [], posTipo = 'boleta', posCliente = null;
(function () {
  try { const g = JSON.parse(leer('jarvisPos') || 'null'); if (g) { posLineas = g.lineas || []; posTipo = g.tipo || 'boleta'; posCliente = g.cliente || null; } } catch (e) {}
})();
const guardarPos = () => guardar('jarvisPos', JSON.stringify({ lineas: posLineas, tipo: posTipo, cliente: posCliente }));
const redondear = n => Math.round((n + Number.EPSILON) * 100) / 100;
function totalesIgv(importes, modo) {
  const suma = redondear(importes.reduce((s, x) => s + x, 0));
  if (modo === 'EXONERADO') return { base: suma, igv: 0, total: suma };
  if (modo === 'AGREGADO') { const igv = redondear(suma * 0.18); return { base: suma, igv, total: redondear(suma + igv) }; }
  const base = redondear(suma / 1.18);
  return { base, igv: redondear(suma - base), total: suma };
}
function productoPos(nombre) { return invItems.find(p => p.nombre === nombre); }
function buscarProductos(q) {
  const t = sinTildes(q.trim()), cod = q.trim().toLowerCase();
  if (!t) return invItems.slice(0, 24);
  return invItems.filter(p => sinTildes(p.nombre).includes(t) || (p.alias || []).some(a => a.includes(t))
    || sinTildes(p.categoria).includes(t) || [p.codigo, p.codigoBarras].some(c => c && c.toLowerCase() === cod)).slice(0, 40);
}
function agregarPos(p, cantidad = 1) {
  if (!p) return;
  const l = posLineas.find(x => x.nombre === p.nombre);
  const nueva = (l ? Number(l.cantidad) : 0) + cantidad;
  if (nueva > Number(p.stock)) { avisar(`Solo quedan ${cant(p.stock)} ${p.unidad} de ${p.nombre}.`, true); return; }
  if (l) l.cantidad = nueva; else posLineas.push({ nombre: p.nombre, cantidad });
  guardarPos(); renderPos();
}
function renderResultados() {
  const lista = buscarProductos($('posBuscar').value);
  $('posResultados').innerHTML = lista.length ? lista.map(p => `<button type="button" class="prod-boton${Number(p.stock) <= 0 ? ' agotado' : ''}" data-nombre="${esc(p.nombre)}">
      <b>${esc(p.nombre)}</b><span><strong>${soles(p.precio)}</strong><span>${Number(p.stock) <= 0 ? 'Sin stock' : cant(p.stock) + ' ' + esc(p.unidad)}</span></span></button>`).join('')
    : `<div class="vacio" style="grid-column:1/-1"><b>${invItems.length ? 'Sin coincidencias' : 'Aún no hay productos'}</b>
       ${invItems.length ? 'Pruebe con otra palabra o el código.' : 'Agréguelos en Inventario.'}</div>`;
}
function renderPos() {
  renderResultados();
  document.querySelectorAll('.segmentado button').forEach(b => b.classList.toggle('activo', b.dataset.tipo === posTipo));
  $('posDni').value = posCliente ? posCliente.documento : $('posDni').value;
  const ci = $('posCliente');
  ci.className = posCliente ? 'ok' : '';
  ci.textContent = posCliente ? 'Cliente: ' + posCliente.nombre : 'Sin cliente: clientes varios.';
  const importes = [];
  $('posLineas').innerHTML = posLineas.length ? posLineas.map((l, i) => {
    const p = productoPos(l.nombre);
    const precio = p ? Number(p.precio) : 0, importe = redondear(precio * Number(l.cantidad));
    importes.push(importe);
    return `<li data-i="${i}"><div><b>${esc(l.nombre)}</b><small>${soles(precio)} · ${p ? esc(p.unidad) : 'ya no existe'}</small></div>
      <input class="campo num" type="number" min="0" step="any" value="${l.cantidad}" aria-label="Cantidad de ${esc(l.nombre)}">
      <span class="importe">${soles(importe)}</span>
      <button class="icono peligro" type="button" data-quitar="${i}" aria-label="Quitar ${esc(l.nombre)}">${icono('x')}</button></li>`;
  }).join('') : '<li class="lineas-vacio">Toque un producto o escanee su código para agregarlo.</li>';
  const modo = resumen ? resumen.igv : 'INCLUIDO', t = totalesIgv(importes, modo);
  $('posTotales').innerHTML = modo === 'EXONERADO'
    ? `<div><span>Exonerado de IGV</span><span></span></div><div class="total"><span>Total</span><span>${soles(t.total)}</span></div>`
    : `<div><span>Op. gravada</span><span>${soles(t.base)}</span></div><div><span>IGV 18%${modo === 'INCLUIDO' ? ' (incluido)' : ''}</span><span>${soles(t.igv)}</span></div>
       <div class="total"><span>Total</span><span>${soles(t.total)}</span></div>`;
  $('posCobrar').disabled = !posLineas.length;
}
$('posBuscar').addEventListener('input', renderResultados);
$('posBuscar').addEventListener('keydown', e => {
  if (e.key !== 'Enter') return;
  e.preventDefault();
  const q = $('posBuscar').value.trim(); if (!q) return;
  const exacto = invItems.find(p => [p.codigo, p.codigoBarras].some(c => c && c.toLowerCase() === q.toLowerCase()));
  const lista = buscarProductos(q);
  const p = exacto || (lista.length === 1 ? lista[0] : null);
  if (p) { agregarPos(p); $('posBuscar').value = ''; renderResultados(); pitido(true); }
  else if (!lista.length && pareceCodigo(q)) codigoDesconocido(q);
  else avisar(lista.length ? 'Hay varios parecidos: toque el que corresponde.' : 'No encontré ese producto.', !lista.length);
});

// Lector de código de barras: escribe muy rápido y termina con Enter. En Vender se acepta aunque el cursor no esté en el buscador.
const pareceCodigo = q => /^[0-9A-Za-z-]{4,40}$/.test(q) && /\d/.test(q);
function productoPorCodigo(c) {
  const x = c.toLowerCase();
  return invItems.find(p => [p.codigo, p.codigoBarras].some(k => k && k.toLowerCase() === x));
}
let sonido = null;
function pitido(bien) {
  try {
    sonido = sonido || new (window.AudioContext || window.webkitAudioContext)();
    const o = sonido.createOscillator(), g = sonido.createGain();
    o.frequency.value = bien ? 1500 : 320; o.type = 'square'; g.gain.value = .04;
    o.connect(g); g.connect(sonido.destination); o.start(); o.stop(sonido.currentTime + (bien ? .07 : .22));
  } catch (e) {}
}
async function codigoDesconocido(codigo) {
  pitido(false);
  if (!puede('INVENTARIO')) { avisar(`El código ${codigo} no es de ningún producto. Pida que lo registren en Inventario.`, true); return; }
  if (!await confirmar('Código nuevo', `El código ${codigo} no es de ningún producto. ¿Registrar un producto nuevo con este código?`, 'Registrar')) return;
  $('posBuscar').value = ''; renderResultados();
  openProduct(null);
  $('fBarras').value = codigo;
}
function escaneado(codigo) {
  $('receipt').classList.remove('show');
  const p = productoPorCodigo(codigo);
  if (p) {
    if (Number(p.stock) <= 0) { pitido(false); avisar(`${p.nombre} no tiene stock.`, true); return; }
    agregarPos(p); pitido(true);
  } else codigoDesconocido(codigo);
}
const lector = { texto: '', ultimo: 0 };
document.addEventListener('keydown', e => {
  if (vista !== 'vender' || e.ctrlKey || e.altKey || e.metaKey || document.querySelector('.modal.show:not(#receipt)')) return;
  const t = e.target;
  if (t && (t.tagName === 'INPUT' || t.tagName === 'TEXTAREA' || t.tagName === 'SELECT' || t.isContentEditable)) return;
  const ahora = performance.now();
  if (ahora - lector.ultimo > 60) lector.texto = '';
  lector.ultimo = ahora;
  if (e.key === 'Enter') {
    const c = lector.texto; lector.texto = '';
    if (c.length >= 4) { e.preventDefault(); escaneado(c); }
    return;
  }
  if (e.key.length === 1) lector.texto += e.key;
}, true);
$('posResultados').addEventListener('click', e => {
  const b = e.target.closest('[data-nombre]'); if (!b || b.classList.contains('agotado')) return;
  agregarPos(productoPos(b.dataset.nombre));
});
$('posLineas').addEventListener('change', e => {
  const li = e.target.closest('li[data-i]'); if (!li || e.target.tagName !== 'INPUT') return;
  const l = posLineas[li.dataset.i], p = productoPos(l.nombre), v = Number(String(e.target.value).replace(',', '.'));
  if (!(v > 0)) posLineas.splice(li.dataset.i, 1);
  else if (p && v > Number(p.stock)) { avisar(`Solo quedan ${cant(p.stock)} ${p.unidad} de ${p.nombre}.`, true); }
  else l.cantidad = v;
  guardarPos(); renderPos();
});
$('posLineas').addEventListener('click', e => {
  const b = e.target.closest('[data-quitar]'); if (!b) return;
  posLineas.splice(Number(b.dataset.quitar), 1); guardarPos(); renderPos();
});
document.querySelectorAll('.segmentado button').forEach(b => b.onclick = () => { posTipo = b.dataset.tipo; guardarPos(); renderPos(); });
async function buscarClientePos() {
  const doc = $('posDni').value.trim();
  if (!doc) { posCliente = null; guardarPos(); renderPos(); return; }
  try {
    const r = await api('/api/clientes/' + encodeURIComponent(doc));
    if (r.ok) { const c = await r.json(); posCliente = { documento: c.documento, nombre: c.nombre }; guardarPos(); renderPos(); return; }
  } catch (e) {}
  posCliente = null; guardarPos(); renderPos();
  const ci = $('posCliente');
  ci.className = 'mal';
  ci.innerHTML = `No hay un cliente con ese documento. <a href="#" id="posRegistrar">Registrarlo</a>`;
  $('posRegistrar').onclick = ev => { ev.preventDefault(); abrirCliente(null, doc); };
}
$('posBuscarCliente').onclick = buscarClientePos;
$('posDni').addEventListener('keydown', e => { if (e.key === 'Enter') { e.preventDefault(); buscarClientePos(); } });
$('posVaciar').onclick = () => { posLineas = []; posCliente = null; $('posDni').value = ''; guardarPos(); renderPos(); };
$('posCobrar').onclick = async () => {
  if (!posLineas.length) return;
  $('posCobrar').disabled = true;
  const res = await invCall('/api/inventory/ventas', 'POST', { items: posLineas, tipo: posTipo, cliente: posCliente ? posCliente.documento : null });
  $('posCobrar').disabled = false;
  if (!res.ok) { avisar(res.reply, true); return; }
  if (res.data.recibo) { dysaMostrarRecibo(res.data.recibo); if (impresion.alCobrar) dysaImprimirRecibo(); }
  avisar(res.reply);
  posLineas = []; posCliente = null; $('posDni').value = ''; guardarPos();
  await refreshInventory(); renderPos();
};
