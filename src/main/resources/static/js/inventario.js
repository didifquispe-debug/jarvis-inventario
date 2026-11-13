// Proyecto DYSA - JARVIS Inventario. Inventario: lista, ficha de producto, carga masiva y dictado de productos.

let invItems = [];
async function refreshInventory() {
  const d = await traer('/api/inventory');
  if (!d) return;
  invItems = d;
  renderInventory();
  if (vista === 'inventario') refreshValorizado();
  $('productosLista').innerHTML = invItems.map(p => `<option value="${esc(p.nombre)}">`).join('');
  if (vista === 'vender') renderResultados();
  if (vista === 'inicio') refreshResumen();
}
async function refreshValorizado() {
  const v = await traer('/api/inventory/valorizado');
  if (!v) return;
  $('invCifras').innerHTML = !v.productos ? '' : `<div class="tarjeta"><span>Valor al costo</span><b>${soles(v.valorCosto)}</b></div>
    <div class="tarjeta"><span>Valor a precio de venta</span><b>${soles(v.valorVenta)}</b></div>
    <div class="tarjeta"><span>Margen</span><b>${soles(v.margen)}</b></div>
    ${v.sinCosto ? `<div class="tarjeta"><span>Sin costo registrado</span><b>${v.sinCosto} ${v.sinCosto === 1 ? 'producto' : 'productos'}</b></div>` : ''}`;
}

function renderInventory() {
  const box = $('inv-list'), q = sinTildes($('invSearch').value.trim()), qCod = $('invSearch').value.trim().toLowerCase();
  renderCategorias();
  const bajos = invItems.filter(p => Number(p.stock) <= Number(p.stockMin)).length;
  $('invCuenta').textContent = invItems.length
    ? `${invItems.length} ${invItems.length === 1 ? 'producto' : 'productos'}${bajos ? ` · ${bajos} por reponer` : ''}` : 'Sin productos todavía';
  if (!invItems.length) {
    box.innerHTML = `<div class="vacio"><b>Aún no tiene productos</b>Agregue el primero, cargue una lista desde Excel o dígaselo a JARVIS.
      <div class="acciones"><button class="boton primario" type="button" data-act="nuevo">${icono('mas')}Agregar producto</button>
      <button class="boton" type="button" data-act="lote">${icono('subir')}Cargar lista</button></div></div>`;
    return;
  }
  const cat = $('invCat').value, est = $('invEstado').value;
  const lista = invItems.map((p, i) => ({ p, i }))
    .filter(x => !cat || x.p.categoria === cat)
    .filter(x => !est || (est === 'sin' ? Number(x.p.stock) <= 0 : Number(x.p.stock) > 0 && Number(x.p.stock) <= Number(x.p.stockMin)))
    .filter(x => !q || sinTildes(x.p.nombre).includes(q) || (x.p.alias || []).some(a => a.includes(q))
      || sinTildes(x.p.categoria).includes(q) || [x.p.codigo, x.p.codigoBarras].some(c => c && c.toLowerCase() === qCod));
  if (!lista.length) { box.innerHTML = '<div class="vacio"><b>Sin resultados</b>Ningún producto coincide con la búsqueda.</div>'; return; }
  box.innerHTML = `<table class="tabla adaptable"><thead><tr><th>Producto</th><th>Stock</th><th class="der">Precio</th><th class="der">Costo</th><th class="der">Valor al costo</th><th></th></tr></thead><tbody>`
    + lista.map(({ p, i }) => {
      const sin = Number(p.stock) <= 0, bajo = !sin && Number(p.stock) <= Number(p.stockMin);
      const meta = [p.categoria, p.codigo, p.codigoBarras].filter(Boolean).map(esc).join(' · ');
      return `<tr data-i="${i}" class="${sin ? 'sin' : bajo ? 'bajo' : ''}">
        <td data-t="" class="nombre-prod"><b>${esc(p.nombre)}</b>${meta ? `<small>${meta}</small>` : ''}</td>
        <td data-t="Stock"><span class="stock-ctrl"><button type="button" data-act="menos" title="Restar 1 (sin venta)" aria-label="Restar 1 a ${esc(p.nombre)}">−</button>
          <span>${cant(p.stock)} ${esc(p.unidad)}</span><button type="button" data-act="mas" title="Sumar 1" aria-label="Sumar 1 a ${esc(p.nombre)}">+</button></span></td>
        <td data-t="Precio" class="der num">${soles(p.precio)}</td>
        <td data-t="Costo" class="der num">${p.costo != null ? soles(p.costo) : '<span style="color:var(--faint)">—</span>'}</td>
        <td data-t="Valor al costo" class="der num">${p.costo != null ? soles(Math.max(0, Number(p.stock)) * Number(p.costo)) : '<span style="color:var(--faint)">—</span>'}</td>
        <td class="acciones"><button class="icono" type="button" data-act="kardex" title="Kardex" aria-label="Kardex de ${esc(p.nombre)}">${icono('kardex')}</button>
          <button class="icono" type="button" data-act="editar" title="Editar" aria-label="Editar ${esc(p.nombre)}">${icono('lapiz')}</button>
          <button class="icono peligro" type="button" data-act="borrar" title="Eliminar" aria-label="Eliminar ${esc(p.nombre)}">${icono('basura')}</button></td></tr>`;
    }).join('') + '</tbody></table>';
}
function renderCategorias() {
  const cats = [...new Set(invItems.map(p => p.categoria).filter(Boolean))].sort((a, b) => a.localeCompare(b, 'es'));
  const sel = $('invCat'), actual = sel.value;
  sel.hidden = !cats.length;
  sel.innerHTML = '<option value="">Todas las categorías</option>'
    + cats.map(c => `<option${c === actual ? ' selected' : ''}>${esc(c)}</option>`).join('');
  $('categorias').innerHTML = cats.map(c => `<option value="${esc(c)}">`).join('');
}
$('inv-list').addEventListener('click', async e => {
  const b = e.target.closest('[data-act]'); if (!b) return;
  if (b.dataset.act === 'nuevo') { openProduct(null); return; }
  if (b.dataset.act === 'lote') { openLote(); return; }
  const p = invItems[b.closest('tr').dataset.i]; if (!p) return;
  if (b.dataset.act === 'editar') { openProduct(p); return; }
  if (b.dataset.act === 'kardex') { verKardex(p.nombre); return; }
  if (b.dataset.act === 'borrar') {
    if (await confirmar('Eliminar producto', `¿Eliminar “${p.nombre}” del inventario? Quedan ${cant(p.stock)} ${p.unidad}. Sus ventas pasadas se conservan.`, 'Eliminar')) {
      const res = await invCall('/api/inventory/productos?nombre=' + encodeURIComponent(p.nombre), 'DELETE');
      avisar(res.reply, !res.ok);
      refreshInventory();
    }
    return;
  }
  b.disabled = true;
  const res = await invCall('/api/inventory/productos/ajuste', 'POST', { nombre: p.nombre, cantidad: b.dataset.act === 'mas' ? 1 : -1 });
  if (!res.ok) avisar(res.reply, true);
  b.disabled = false;
  refreshInventory();
});
$('invAdd').onclick = () => openProduct(null);
$('invSearch').addEventListener('input', renderInventory);
$('invCat').addEventListener('change', renderInventory);
$('invEstado').addEventListener('change', renderInventory);

let editando = null;
const numOrNull = v => v.trim() === '' ? null : Number(v.replace(',', '.'));
const aliasPropios = p => (p.alias || []).filter(a => a !== sinTildes(p.nombre).replace(/\s+/g, ' ').trim());
function openProduct(p) {
  editando = p;
  $('prodTitle').textContent = p ? 'Editar producto' : 'Nuevo producto';
  $('fStockLbl').textContent = p ? 'Stock actual' : 'Stock inicial';
  $('fNombre').value = p ? p.nombre : '';
  $('fPrecio').value = p ? p.precio : '';
  $('fUnidad').value = p ? p.unidad : 'unidades';
  $('fStock').value = p ? p.stock : '';
  $('fMin').value = p ? p.stockMin : '';
  $('fAlias').value = p ? aliasPropios(p).join(', ') : '';
  $('fCosto').value = p && p.costo != null ? p.costo : '';
  $('fCat').value = p ? p.categoria || '' : $('invCat').value;
  $('fCodigo').value = p ? p.codigo || '' : '';
  $('fBarras').value = p ? p.codigoBarras || '' : '';
  mostrarMargen();
  $('prodErr').textContent = '';
  $('prodModal').classList.add('show');
  setTimeout(() => $('fNombre').focus(), 50);
}
function closeProduct() { $('prodModal').classList.remove('show'); editando = null; }
function mostrarMargen() {
  const precio = numOrNull($('fPrecio').value), costo = numOrNull($('fCosto').value), out = $('fMargen');
  out.classList.remove('mal');
  if (precio === null || costo === null || isNaN(precio) || isNaN(costo)) { out.textContent = ''; return; }
  const g = precio - costo;
  if (g < 0) { out.classList.add('mal'); out.textContent = `Vende por debajo del costo: pierde ${soles(-g)} en cada uno.`; return; }
  out.textContent = `Ganancia: ${soles(g)} en cada uno` + (precio > 0 ? ` (${Math.round(g / precio * 100)}% del precio).` : '.');
}
$('fPrecio').addEventListener('input', mostrarMargen);
$('fCosto').addEventListener('input', mostrarMargen);
$('fBarras').addEventListener('keydown', e => { if (e.key === 'Enter') { e.preventDefault(); $('prodSave').focus(); } });
$('prodCancel').onclick = closeProduct;
$('prodForm').addEventListener('submit', async e => {
  e.preventDefault();
  const nombre = $('fNombre').value.trim(), unidad = $('fUnidad').value.trim();
  const precio = numOrNull($('fPrecio').value), stock = numOrNull($('fStock').value), stockMin = numOrNull($('fMin').value);
  const alias = $('fAlias').value.split(',').map(a => a.trim()).filter(Boolean);
  const costo = numOrNull($('fCosto').value), categoria = $('fCat').value.trim();
  const codigo = $('fCodigo').value.trim(), codigoBarras = $('fBarras').value.trim();
  const err = !nombre ? 'Escriba el nombre del producto.'
    : precio === null || isNaN(precio) ? 'Escriba el precio (puede ser 0).'
    : precio < 0 ? 'El precio no puede ser negativo.'
    : !unidad ? 'Escriba la unidad (unidades, pares, metros…).'
    : (stock !== null && (isNaN(stock) || stock < 0)) ? 'El stock no puede ser negativo.'
    : (stockMin !== null && (isNaN(stockMin) || stockMin < 0)) ? 'El stock mínimo no puede ser negativo.'
    : (costo !== null && (isNaN(costo) || costo < 0)) ? 'El costo no puede ser negativo.' : '';
  if (err) { $('prodErr').textContent = err; return; }
  let cambios = null;
  if (editando) {
    const p = editando, c = { nombre: p.nombre };
    if (nombre !== p.nombre) c.nuevoNombre = nombre;
    if (precio !== Number(p.precio)) c.precio = precio;
    if (stock !== null && stock !== Number(p.stock)) c.stock = stock;
    if (stockMin !== null && stockMin !== Number(p.stockMin)) c.stockMin = stockMin;
    if (unidad.toLowerCase() !== p.unidad) c.unidad = unidad;
    if (alias.join(',') !== aliasPropios(p).join(',')) c.alias = alias;
    if (categoria !== (p.categoria || '')) c.categoria = categoria;
    if (codigo !== (p.codigo || '')) c.codigo = codigo;
    if (codigoBarras !== (p.codigoBarras || '')) c.codigoBarras = codigoBarras;
    if (costo === null && p.costo != null) c.quitarCosto = true;
    else if (costo !== null && costo !== Number(p.costo ?? NaN)) c.costo = costo;
    if (Object.keys(c).length === 1) { closeProduct(); return; }
    cambios = c;
  }
  $('prodSave').disabled = true;
  const res = editando
    ? await invCall('/api/inventory/productos', 'PATCH', cambios)
    : await invCall('/api/inventory/productos', 'POST', { nombre, precio, stock, stockMin, unidad, alias, costo, categoria, codigo, codigoBarras });
  $('prodSave').disabled = false;
  if (!res.ok) { $('prodErr').textContent = res.reply; return; }
  closeProduct();
  avisar(res.reply);
  refreshInventory();
});

function openLote() {
  $('loteRes').innerHTML = ''; $('loteErr').textContent = '';
  $('loteModal').classList.add('show');
  setTimeout(() => $('loteTxt').focus(), 50);
}
function closeLote() { pararDictado(); $('loteModal').classList.remove('show'); }
$('invLote').onclick = openLote;
$('loteCancel').onclick = closeLote;
$('loteFile').addEventListener('change', async e => {
  const f = e.target.files[0]; e.target.value = '';
  if (!f) return;
  if (f.size > 2 * 1024 * 1024) { $('loteErr').textContent = 'El archivo es muy grande (máximo 2 MB).'; return; }
  const buf = await f.arrayBuffer();
  let txt = new TextDecoder('utf-8').decode(buf);
  if (txt.includes('�')) txt = new TextDecoder('windows-1252').decode(buf);
  $('loteTxt').value = txt;
  $('loteErr').textContent = '';
  $('loteRes').innerHTML = `<p>Archivo “${esc(f.name)}” listo. Revíselo y toque Cargar.</p>`;
});
function loteResumen(d) {
  const item = (x, cls) => `<li class="${cls}"><b>Línea ${x.linea}:</b> ${esc(x.motivo)}</li>`;
  const n = (k, uno, varios) => `${k} ${k === 1 ? uno : varios}`;
  return `<div class="lote-cifras"><span class="etiqueta ok">${n(d.agregados, 'agregado', 'agregados')}</span>
      <span class="etiqueta aviso">${n(d.omitidos, 'omitido', 'omitidos')}</span>
      <span class="etiqueta mal">${n(d.errores.length, 'con error', 'con errores')}</span></div>`
    + (d.errores.length ? `<p>Corrija estas líneas y vuelva a tocar Cargar (los ya agregados se omiten solos):</p>
      <ul class="lote-lista">${d.errores.map(x => item(x, 'err')).join('')}</ul>` : '')
    + (d.omitidos ? `<p>No se registraron:</p><ul class="lote-lista">${d.omitidosDetalle.map(x => item(x, 'omit')).join('')}</ul>` : '');
}
$('loteForm').addEventListener('submit', async e => {
  e.preventDefault();
  const txt = $('loteTxt').value;
  pararDictado();
  if (!txt.trim()) { $('loteErr').textContent = 'Pegue su lista: una línea por producto.'; return; }
  $('loteSend').disabled = true; $('loteErr').textContent = ''; $('loteRes').innerHTML = '<p>Cargando…</p>';
  let d;
  try {
    const r = await api('/api/inventory/productos/lote', { method: 'POST', body: txt,
                                                         headers: { 'Content-Type': 'text/plain; charset=utf-8' } });
    d = await r.json().catch(() => ({ ok: false, reply: 'Respuesta inesperada del servidor.' }));
    if (r.status === 401) d = { ok: false, reply: 'Clave incorrecta. Use “Cambiar clave” para escribirla de nuevo.' };
    else if (!r.ok) d.ok = false;
  } catch (err) {
    d = { ok: false, reply: 'Sin conexión con el núcleo de JARVIS.' };
  }
  $('loteSend').disabled = false;
  if (!d.ok) { $('loteRes').innerHTML = ''; $('loteErr').textContent = d.reply; return; }
  $('loteRes').innerHTML = loteResumen(d);
  if (!d.errores.length) $('loteTxt').value = '';
  avisar(d.reply);
  refreshInventory();
});

const NUM_DICHO = { un: 1, uno: 1, una: 1, dos: 2, tres: 3, cuatro: 4, cinco: 5, seis: 6, siete: 7, ocho: 8, nueve: 9, diez: 10,
  once: 11, doce: 12, trece: 13, catorce: 14, quince: 15, veinte: 20, treinta: 30, cuarenta: 40, cincuenta: 50, cien: 100 };
const UNIDAD_DICHA = { unidad: 'unidades', unidades: 'unidades', par: 'pares', pares: 'pares', metro: 'metros', metros: 'metros',
  kilo: 'kilos', kilos: 'kilos', kg: 'kilos', litro: 'litros', litros: 'litros', caja: 'cajas', cajas: 'cajas',
  paquete: 'paquetes', paquetes: 'paquetes', docena: 'docenas', docenas: 'docenas', bolsa: 'bolsas', bolsas: 'bolsas',
  rollo: 'rollos', rollos: 'rollos', galon: 'galones', galones: 'galones', saco: 'sacos', sacos: 'sacos',
  pieza: 'piezas', piezas: 'piezas', gramo: 'gramos', gramos: 'gramos' };
const RELLENO_NUMERO = /\b(soles?|precio|stock|minimo|min)\b|s\/\s*/g;
function numeroDicho(t) {
  const s = sinTildes(t).trim();
  if (/^\d+([.,]\d+)?$/.test(s)) return s;
  return NUM_DICHO[s] != null ? String(NUM_DICHO[s]) : null;
}
const capital = t => t ? t.charAt(0).toUpperCase() + t.slice(1) : t;
function armarLinea(c) {
  const out = c.map(x => String(x ?? '').trim());
  while (out.length > 1 && !out[out.length - 1]) out.pop();
  return out.join('; ');
}
function lineaDictada(frase) {
  const t = String(frase || '').trim().replace(/(\d+)\s+con\s+(\d{1,2})\b/gi, '$1,$2');
  if (!t) return '';
  const sep = /\s*(?:\bpunto y coma\b|\bcoma\b|;|,(?=\s))\s*/i;
  if (sep.test(t)) {
    const c = t.split(sep);
    [1, 2, 4].forEach(i => {
      if (!c[i]) return;
      const limpio = sinTildes(c[i]).replace(RELLENO_NUMERO, ' ').trim();
      c[i] = numeroDicho(limpio) ?? c[i].trim();
    });
    if (c[3]) c[3] = UNIDAD_DICHA[sinTildes(c[3]).trim()] || c[3].trim();
    c[0] = capital(c[0].trim());
    return armarLinea(c);
  }
  const ts = t.split(/\s+/), w = ts.map(sinTildes);
  let ip = -1, finNombre = -1;
  for (let i = 1; i < ts.length; i++) {
    if ((w[i] === 'a' || w[i] === 'precio') && i + 1 < ts.length && numeroDicho(ts[i + 1]) != null) { ip = i + 1; finNombre = i; break; }
    if ((w[i] === 'soles' || w[i] === 'sol') && numeroDicho(ts[i - 1]) != null) { ip = i - 1; finNombre = i - 1; break; }
  }
  if (ip < 0) { ip = ts.findIndex((x, i) => i > 0 && /^\d/.test(x)); finNombre = ip; }
  if (ip < 0) return capital(t);
  let stock = '', unidad = '', min = '', esMin = false;
  for (const x of ts.slice(ip + 1)) {
    const s = sinTildes(x), n = numeroDicho(x);
    if (s === 'minimo' || s === 'min') { esMin = true; continue; }
    if (n != null) {
      if (esMin || stock) { if (!min) min = n; } else stock = n;
      esMin = false; continue;
    }
    if (!unidad && UNIDAD_DICHA[s]) unidad = UNIDAD_DICHA[s];
  }
  return armarLinea([capital(ts.slice(0, finNombre).join(' ')), numeroDicho(ts[ip]), stock, unidad, min]);
}
function agregarDictado(frase) {
  const linea = lineaDictada(frase);
  if (!linea) return;
  const t = $('loteTxt');
  t.value += (t.value && !t.value.endsWith('\n') ? '\n' : '') + linea + '\n';
  t.scrollTop = t.scrollHeight;
}
let dictado = null, dictando = false, dictadoLang = null;
const AVISO_DICTADO = local => local
  ? 'Dictado en este equipo. Diga un producto por frase…'
  : 'El dictado usa el servicio de voz del navegador (necesita Internet). Diga un producto por frase…';
async function idiomaLocal() {
  if (!SR || typeof SR.available !== 'function') return null;
  for (const lang of ['es-PE', 'es-419', 'es-US', 'es-ES']) {
    try { if (await SR.available({ langs: [lang], processLocally: true }) === 'available') return lang; } catch (e) {}
  }
  return null;
}
function botonDictado(activo) {
  $('loteMic').classList.toggle('primario', activo);
  $('loteMic').querySelector('span').textContent = activo ? 'Parar' : 'Dictar';
}
async function iniciarDictado() {
  if (!voiceOK) {
    $('loteErr').textContent = window.isSecureContext
      ? 'Este navegador no reconoce voz: use Chrome o Edge, o escriba la lista.'
      : 'El micrófono requiere HTTPS en este dispositivo (vea el README). Puede escribir o pegar la lista.';
    return;
  }
  stopRec();
  callar();
  dictando = true;
  $('loteErr').textContent = '';
  botonDictado(true);
  dictadoLang = await idiomaLocal();
  if (!dictando) return;
  $('loteVoz').textContent = AVISO_DICTADO(dictadoLang);
  escucharDictado();
}
function escucharDictado() {
  if (!dictando) return;
  const r = new SR();
  dictado = r;
  r.lang = dictadoLang || 'es-PE';
  r.continuous = true;
  r.interimResults = true;
  if (dictadoLang) r.processLocally = true;
  r.onresult = e => {
    let parcial = '';
    for (let i = e.resultIndex; i < e.results.length; i++) {
      const res = e.results[i];
      if (res.isFinal) agregarDictado(res[0].transcript); else parcial += res[0].transcript;
    }
    if (dictando) $('loteVoz').textContent = parcial ? '“' + parcial + '”' : AVISO_DICTADO(dictadoLang);
  };
  r.onerror = e => {
    if (e.error === 'language-not-supported' && dictadoLang) { dictadoLang = null; return; }
    if (e.error === 'not-allowed' || e.error === 'service-not-allowed') $('loteErr').textContent = 'Permiso de micrófono denegado.';
    else if (e.error === 'network') $('loteErr').textContent = 'Sin Internet, este navegador no puede dictar. Escriba o pegue la lista.';
    else return;
    pararDictado();
  };
  r.onend = () => { if (dictando && dictado === r) setTimeout(escucharDictado, 200); };
  try { r.start(); } catch (e) {}
}
function pararDictado() {
  if (!dictando) return;
  dictando = false;
  const r = dictado; dictado = null;
  if (r) { r.onend = null; try { r.stop(); } catch (e) {} }
  botonDictado(false);
  $('loteVoz').textContent = '';
  if (handsFree) setTimeout(() => { if (!rec && handsFree) startRec('wake'); }, 300);
}
$('loteMic').onclick = () => dictando ? pararDictado() : iniciarDictado();
