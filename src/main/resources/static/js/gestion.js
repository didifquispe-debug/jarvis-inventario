// Proyecto DYSA - JARVIS Inventario. Clientes, comprobantes, compras, proveedores, kardex, descargas y ventana de confirmar.

let clientes = [], editandoCliente = null, clienteParaVenta = false;
async function refreshClientes() {
  const q = $('cliBuscar').value.trim();
  const d = await traer('/api/clientes?limite=200&q=' + encodeURIComponent(q));
  if (!d) return;
  clientes = d;
  const box = $('cliLista');
  if (!clientes.length) {
    box.innerHTML = q ? '<div class="vacio"><b>Sin resultados</b>Ningún cliente coincide con la búsqueda.</div>'
      : `<div class="vacio"><b>Aún no tiene clientes</b>Regístrelos para emitir boletas con nombre y DNI.
         <div class="acciones"><button class="boton primario" type="button" data-cli="nuevo">${icono('mas')}Nuevo cliente</button></div></div>`;
    return;
  }
  box.innerHTML = `<table class="tabla adaptable"><thead><tr><th>Documento</th><th>Nombre</th><th>Teléfono</th><th>Correo</th><th></th></tr></thead><tbody>`
    + clientes.map((c, i) => `<tr data-i="${i}">
      <td data-t="Documento" class="num"><span class="etiqueta">${esc(c.tipoDocumento)}</span> ${esc(c.documento)}</td>
      <td data-t="Nombre"><b>${esc(c.nombre)}</b></td>
      <td data-t="Teléfono" class="num">${esc(c.telefono) || '<span style="color:var(--faint)">—</span>'}</td>
      <td data-t="Correo">${esc(c.email) || '<span style="color:var(--faint)">—</span>'}</td>
      <td class="acciones"><button class="icono" type="button" data-cli="editar" title="Editar" aria-label="Editar ${esc(c.nombre)}">${icono('lapiz')}</button>
        <button class="icono peligro" type="button" data-cli="borrar" title="Eliminar" aria-label="Eliminar ${esc(c.nombre)}">${icono('basura')}</button></td></tr>`).join('')
    + '</tbody></table>';
}
let buscarClienteEspera = null;
$('cliBuscar').addEventListener('input', () => { clearTimeout(buscarClienteEspera); buscarClienteEspera = setTimeout(refreshClientes, 220); });
$('cliNuevo').onclick = () => abrirCliente(null);
$('cliLista').addEventListener('click', async e => {
  const b = e.target.closest('[data-cli]'); if (!b) return;
  if (b.dataset.cli === 'nuevo') { abrirCliente(null); return; }
  const c = clientes[b.closest('tr').dataset.i]; if (!c) return;
  if (b.dataset.cli === 'editar') { abrirCliente(c); return; }
  if (await confirmar('Eliminar cliente', `¿Eliminar a ${c.nombre} (${c.tipoDocumento} ${c.documento})?`, 'Eliminar')) {
    const res = await invCall('/api/clientes/' + encodeURIComponent(c.documento), 'DELETE');
    avisar(res.reply, !res.ok);
    refreshClientes();
  }
});
function abrirCliente(c, documento) {
  editandoCliente = c;
  clienteParaVenta = !c && !!documento;
  $('cliTitulo').textContent = c ? 'Editar cliente' : 'Nuevo cliente';
  $('cDoc').value = c ? c.documento : documento || '';
  $('cNombre').value = c ? c.nombre : '';
  $('cTel').value = c ? c.telefono : '';
  $('cEmail').value = c ? c.email : '';
  $('cDir').value = c ? c.direccion : '';
  $('cliErr').textContent = '';
  $('cliModal').classList.add('show');
  setTimeout(() => (documento ? $('cNombre') : $('cDoc')).focus(), 50);
}
$('cliCancelar').onclick = () => $('cliModal').classList.remove('show');
$('cliForm').addEventListener('submit', async e => {
  e.preventDefault();
  const datos = { documento: $('cDoc').value.trim(), nombre: $('cNombre').value.trim(), telefono: $('cTel').value.trim(),
                  email: $('cEmail').value.trim(), direccion: $('cDir').value.trim() };
  if (!/^(\d{8}|\d{11})$/.test(datos.documento)) { $('cliErr').textContent = 'El documento es un DNI de 8 dígitos o un RUC de 11.'; return; }
  if (!datos.nombre) { $('cliErr').textContent = 'Escriba el nombre.'; return; }
  $('cliGuardar').disabled = true;
  const res = editandoCliente
    ? await invCall('/api/clientes/' + encodeURIComponent(editandoCliente.documento), 'PATCH', datos)
    : await invCall('/api/clientes', 'POST', datos);
  $('cliGuardar').disabled = false;
  if (!res.ok) { $('cliErr').textContent = res.reply; return; }
  $('cliModal').classList.remove('show');
  avisar(res.reply);
  if (clienteParaVenta) { posCliente = { documento: datos.documento, nombre: datos.nombre }; guardarPos(); renderPos(); }
  refreshClientes();
});

let comprobantes = [];
async function refreshComprobantes() {
  const d = await traer('/api/inventory/comprobantes?' + new URLSearchParams({ limite: 1000, desde: $('compDesde').value, hasta: $('compHasta').value }));
  if (!d) return;
  comprobantes = d;
  renderComprobantes();
}
function renderComprobantes() {
  const q = sinTildes($('compBuscar').value.trim()), tipo = $('compTipo').value;
  const lista = comprobantes.filter(c => (!tipo || c.tipo === tipo)
    && (!q || sinTildes(c.codigo).includes(q) || sinTildes(c.cliente).includes(q)));
  $('compLista').innerHTML = lista.length
    ? `<table class="tabla adaptable"><thead><tr><th>Comprobante</th><th>Cliente</th><th>Fecha</th><th class="der">Total</th></tr></thead>
       <tbody>${lista.map(filaComprobante).join('')}</tbody></table>`
    : `<div class="vacio"><b>${comprobantes.length ? 'Sin resultados' : 'Aún no hay comprobantes'}</b>
       ${comprobantes.length ? 'Ningún comprobante coincide con la búsqueda.' : 'Se crean al cobrar una venta.'}</div>`;
}
$('compBuscar').addEventListener('input', renderComprobantes);
$('compTipo').addEventListener('change', renderComprobantes);
$('compDesde').addEventListener('change', refreshComprobantes);
$('compHasta').addEventListener('change', refreshComprobantes);
$('compExcel').onclick = () => descargar('/api/reportes/ventas.xlsx?' + new URLSearchParams({ desde: $('compDesde').value, hasta: $('compHasta').value }), 'ventas.xlsx');
$('anularUltima').onclick = async () => {
  const ultimo = comprobantes[0];
  if (!ultimo) { avisar('No hay ventas para anular.', true); return; }
  if (ultimo.anulado) { avisar(`La última venta (${codigoDe(ultimo)}) ya está anulada.`, true); return; }
  if (!await confirmar('Anular la última venta', `¿Anular ${tipoCorto(ultimo.tipo).toLowerCase()} ${codigoDe(ultimo)} por ${soles(ultimo.total)}? Los productos vuelven al stock.`, 'Anular')) return;
  const res = await invCall('/api/inventory/ventas/ultima/anular', 'POST');
  avisar(res.reply, !res.ok);
  refrescarDatos();
};

const fechaCorta = f => (f || '').split('-').reverse().join('/');
let compras = [];
async function refreshCompras() {
  const q = new URLSearchParams({ q: $('comprasBuscar').value.trim(), desde: $('comprasDesde').value, hasta: $('comprasHasta').value, limite: 300 });
  const d = await traer('/api/compras?' + q);
  if (!d) return;
  compras = d.compras;
  const t = d.totales;
  $('comprasCifras').innerHTML = `<div class="tarjeta"><span>Compras</span><b>${t.compras}</b></div>
    <div class="tarjeta"><span>Op. gravada</span><b>${soles(t.base)}</b></div>
    <div class="tarjeta"><span>IGV (crédito fiscal)</span><b>${soles(t.igv)}</b></div>
    <div class="tarjeta"><span>Total</span><b>${soles(t.total)}</b></div>`;
  $('comprasLista').innerHTML = compras.length
    ? `<table class="tabla adaptable"><thead><tr><th>Compra</th><th>Proveedor</th><th>Documento</th><th>Fecha</th><th class="der">Total</th></tr></thead><tbody>`
      + compras.map(c => `<tr class="clic" data-compra="${esc(c.codigo)}" tabindex="0" title="Enter: ver la compra">
        <td data-t="Compra"><b class="num">${esc(c.codigo)}</b>${c.anulada ? ' <span class="etiqueta mal">Anulada</span>' : ''}</td>
        <td data-t="Proveedor">${esc(c.proveedor)}<small style="display:block;color:var(--faint)">RUC ${esc(c.proveedorRuc)}</small></td>
        <td data-t="Documento">${esc(c.tipoDocumentoDescripcion)} <span class="num">${esc(c.numeroDocumento)}</span></td>
        <td data-t="Fecha" class="num">${fechaCorta(c.fecha)}</td>
        <td data-t="Total" class="der num"${c.anulada ? ' style="text-decoration:line-through;color:var(--faint)"' : ''}>${soles(c.total)}</td></tr>`).join('')
      + '</tbody></table>'
    : `<div class="vacio"><b>${$('comprasBuscar').value || $('comprasDesde').value || $('comprasHasta').value ? 'Sin resultados' : 'Aún no hay compras'}</b>
       Registre la mercadería que llega de sus proveedores: suma el stock y calcula el costo.
       <div class="acciones"><button class="boton primario" type="button" onclick="abrirCompra()">${icono('mas')}Nueva compra</button></div></div>`;
}
let buscarCompraEspera = null;
$('comprasBuscar').addEventListener('input', () => { clearTimeout(buscarCompraEspera); buscarCompraEspera = setTimeout(refreshCompras, 250); });
$('comprasDesde').addEventListener('change', refreshCompras);
$('comprasHasta').addEventListener('change', refreshCompras);

let coLineas = [];
function abrirCompra() {
  coLineas = [];
  ['coRuc', 'coRazon', 'coNumero', 'coProducto', 'coCantidad', 'coCosto', 'coObs'].forEach(id => { $(id).value = ''; });
  $('coTipo').value = 'factura'; $('coIgv').value = 'incluido';
  const hoy = new Date(Date.now() - new Date().getTimezoneOffset() * 60000).toISOString().slice(0, 10);
  $('coFecha').value = hoy; $('coFecha').max = hoy;
  $('coRazon').disabled = false; $('coRazonAyuda').textContent = 'si el RUC es nuevo';
  $('coErr').textContent = '';
  renderCompraLineas();
  $('compraModal').classList.add('show');
  setTimeout(() => $('coRuc').focus(), 50);
}
$('compraNueva').onclick = abrirCompra;
$('comprasExcel').onclick = () => descargar('/api/reportes/compras.xlsx?' + new URLSearchParams({ q: $('comprasBuscar').value.trim(),
  desde: $('comprasDesde').value, hasta: $('comprasHasta').value }), 'compras.xlsx');
$('coCancelar').onclick = () => $('compraModal').classList.remove('show');
async function buscarProveedorCompra() {
  const ruc = $('coRuc').value.trim();
  if (!/^\d{11}$/.test(ruc)) return;
  try {
    const r = await api('/api/proveedores/' + ruc);
    if (r.ok) { const p = await r.json(); $('coRazon').value = p.razonSocial; $('coRazon').disabled = true; $('coRazonAyuda').textContent = 'proveedor registrado'; return; }
  } catch (e) {}
  $('coRazon').disabled = false; $('coRazonAyuda').textContent = 'RUC nuevo: escríbala';
  $('coRazon').focus();
}
$('coBuscarRuc').onclick = buscarProveedorCompra;
$('coRuc').addEventListener('change', buscarProveedorCompra);
$('coRuc').addEventListener('keydown', e => { if (e.key === 'Enter') { e.preventDefault(); buscarProveedorCompra(); } });
function agregarLineaCompra() {
  const nombre = $('coProducto').value.trim(), cantidad = numOrNull($('coCantidad').value), costo = numOrNull($('coCosto').value);
  const p = invItems.find(x => x.nombre.toLowerCase() === nombre.toLowerCase());
  const err = !p ? 'Elija un producto de la lista (nombre exacto).' : !(cantidad > 0) ? 'Escriba la cantidad.' : !(costo > 0) ? 'Escriba el costo unitario.' : '';
  $('coErr').textContent = err;
  if (err) return;
  coLineas.push({ producto: p.nombre, cantidad, costo, unidad: p.unidad });
  $('coProducto').value = ''; $('coCantidad').value = ''; $('coCosto').value = '';
  renderCompraLineas();
  $('coProducto').focus();
}
$('coAgregar').onclick = agregarLineaCompra;
$('coCosto').addEventListener('keydown', e => { if (e.key === 'Enter') { e.preventDefault(); agregarLineaCompra(); } });
$('coProducto').addEventListener('change', () => {
  const p = invItems.find(x => x.nombre === $('coProducto').value);
  if (p && p.costo != null && !$('coCosto').value) $('coCosto').value = p.costo;
});
$('coIgv').addEventListener('change', renderCompraLineas);
function renderCompraLineas() {
  const importes = coLineas.map(l => redondear(l.costo * l.cantidad));
  $('coLineas').innerHTML = coLineas.length
    ? `<table class="tabla compacta"><thead><tr><th>Producto</th><th class="der">Cantidad</th><th class="der">Costo</th><th class="der">Importe</th><th></th></tr></thead><tbody>`
      + coLineas.map((l, i) => `<tr><td>${esc(l.producto)}</td><td class="der num">${cant(l.cantidad)} ${esc(l.unidad)}</td>
        <td class="der num">${soles(l.costo)}</td><td class="der num">${soles(importes[i])}</td>
        <td class="acciones"><button class="icono peligro" type="button" data-co-quitar="${i}" aria-label="Quitar ${esc(l.producto)}">${icono('x')}</button></td></tr>`).join('')
      + '</tbody></table>'
    : '<div class="lineas-vacio">Agregue los productos del documento con su cantidad y costo.</div>';
  const modo = { incluido: 'INCLUIDO', agregado: 'AGREGADO', exonerado: 'EXONERADO' }[$('coIgv').value];
  const t = totalesIgv(importes, modo);
  $('coTotales').innerHTML = `<div><span>Op. gravada</span><span>${soles(t.base)}</span></div><div><span>IGV</span><span>${soles(t.igv)}</span></div>
    <div class="total"><span>Total</span><span>${soles(t.total)}</span></div>`;
}
$('coLineas').addEventListener('click', e => {
  const b = e.target.closest('[data-co-quitar]'); if (!b) return;
  coLineas.splice(Number(b.dataset.coQuitar), 1); renderCompraLineas();
});
$('compraForm').addEventListener('submit', async e => {
  e.preventDefault();
  if ($('coProducto').value.trim() && $('coCantidad').value) agregarLineaCompra();
  const datos = { ruc: $('coRuc').value.trim(), razonSocial: $('coRazon').value.trim(), tipoDocumento: $('coTipo').value,
    numeroDocumento: $('coNumero').value.trim(), fechaDocumento: $('coFecha').value, igv: $('coIgv').value,
    observacion: $('coObs').value.trim(), items: coLineas.map(l => ({ producto: l.producto, cantidad: l.cantidad, costo: l.costo })) };
  const err = !/^(10|15|17|20)\d{9}$/.test(datos.ruc) ? 'El RUC tiene 11 dígitos y empieza con 10, 15, 17 o 20.'
    : !datos.numeroDocumento ? 'Escriba la serie y número del documento.' : !datos.items.length ? 'Agregue al menos un producto.' : '';
  if (err) { $('coErr').textContent = err; return; }
  $('coGuardar').disabled = true;
  const res = await invCall('/api/compras', 'POST', datos);
  $('coGuardar').disabled = false;
  if (!res.ok) { $('coErr').textContent = res.reply; return; }
  $('compraModal').classList.remove('show');
  avisar(res.reply);
  refreshInventory(); refreshCompras();
});

let compraVista = null;
async function verCompra(codigo) {
  const c = await traer('/api/compras/' + encodeURIComponent(codigo));
  if (!c) { avisar('No pude abrir esa compra.', true); return; }
  compraVista = c;
  $('compraVerCuerpo').innerHTML = `<h3>Compra ${esc(c.codigo)} ${c.anulada ? '<span class="etiqueta mal">Anulada</span>' : ''}</h3>
    <p>${esc(c.tipoDocumentoDescripcion)} <b class="num">${esc(c.numeroDocumento)}</b> del ${fechaCorta(c.fechaDocumento)} ·
      ${esc(c.proveedor)} (RUC ${esc(c.proveedorRuc)})<br>Registrada el ${fechaCorta(c.fecha)} a las ${esc(c.hora)}${c.observacion ? ' · ' + esc(c.observacion) : ''}</p>
    <div class="tabla-caja"><table class="tabla compacta"><thead><tr><th>#</th><th>Producto</th><th class="der">Cantidad</th><th class="der">Costo</th><th class="der">Importe</th></tr></thead>
    <tbody>${c.detalles.map(d => `<tr><td>${d.item}</td><td>${esc(d.producto)}</td><td class="der num">${cant(d.cantidad)} ${esc(d.unidad)}</td>
      <td class="der num">${esc(moneda.simbolo)} ${Number(d.costo).toLocaleString('es-PE', { minimumFractionDigits: 2, maximumFractionDigits: 4 })}</td>
      <td class="der num">${soles(d.importe)}</td></tr>`).join('')}</tbody></table></div>
    <div class="totales" style="padding:12px 0 0"><div><span>${c.modoIgv === 'EXONERADO' ? 'Op. exonerada' : 'Op. gravada'}</span><span>${soles(c.base)}</span></div>
      <div><span>IGV</span><span>${soles(c.igv)}</span></div><div class="total"><span>Total</span><span>${soles(c.total)}</span></div></div>`;
  $('cvAnular').hidden = c.anulada;
  $('compraVer').classList.add('show');
}
document.addEventListener('click', e => {
  const fila = e.target.closest('[data-compra]');
  if (fila) verCompra(fila.dataset.compra);
});
$('cvCerrar').onclick = () => $('compraVer').classList.remove('show');
$('cvAnular').onclick = async () => {
  const c = compraVista; if (!c) return;
  $('compraVer').classList.remove('show');
  if (!await confirmar('Anular compra', `¿Anular la compra ${c.codigo} (${c.tipoDocumentoDescripcion.toLowerCase()} ${c.numeroDocumento})? Se retira del stock lo que ingresó con ella.`, 'Anular')) return;
  const res = await invCall('/api/compras/' + encodeURIComponent(c.codigo) + '/anular', 'POST');
  avisar(res.reply, !res.ok);
  refreshInventory(); refreshCompras();
};

let proveedores = [], editandoProveedor = null;
async function refreshProveedores() {
  const q = $('provBuscar').value.trim();
  const d = await traer('/api/proveedores?limite=300&q=' + encodeURIComponent(q));
  if (!d) return;
  proveedores = d;
  $('provLista').innerHTML = proveedores.length
    ? `<table class="tabla adaptable"><thead><tr><th>RUC</th><th>Razón social</th><th>Teléfono</th><th>Dirección</th><th></th></tr></thead><tbody>`
      + proveedores.map((p, i) => `<tr data-i="${i}"><td data-t="RUC" class="num">${esc(p.ruc)}</td><td data-t="Razón social"><b>${esc(p.razonSocial)}</b></td>
        <td data-t="Teléfono" class="num">${esc(p.telefono) || '<span style="color:var(--faint)">—</span>'}</td>
        <td data-t="Dirección">${esc(p.direccion) || '<span style="color:var(--faint)">—</span>'}</td>
        <td class="acciones"><button class="icono" type="button" data-prov="editar" aria-label="Editar ${esc(p.razonSocial)}">${icono('lapiz')}</button>
          <button class="icono peligro" type="button" data-prov="borrar" aria-label="Eliminar ${esc(p.razonSocial)}">${icono('basura')}</button></td></tr>`).join('')
      + '</tbody></table>'
    : `<div class="vacio"><b>${q ? 'Sin resultados' : 'Aún no hay proveedores'}</b>${q ? 'Ningún proveedor coincide.' : 'Se registran solos con la primera compra.'}</div>`;
}
let buscarProvEspera = null;
$('provBuscar').addEventListener('input', () => { clearTimeout(buscarProvEspera); buscarProvEspera = setTimeout(refreshProveedores, 220); });
function abrirProveedor(p) {
  editandoProveedor = p;
  $('provTitulo').textContent = p ? 'Editar proveedor' : 'Nuevo proveedor';
  $('pRuc').value = p ? p.ruc : ''; $('pRazon').value = p ? p.razonSocial : '';
  $('pTel').value = p ? p.telefono : ''; $('pDir').value = p ? p.direccion : '';
  $('provErr').textContent = '';
  $('provModal').classList.add('show');
  setTimeout(() => $('pRuc').focus(), 50);
}
$('provNuevo').onclick = () => abrirProveedor(null);
$('provCancelar').onclick = () => $('provModal').classList.remove('show');
$('provLista').addEventListener('click', async e => {
  const b = e.target.closest('[data-prov]'); if (!b) return;
  const p = proveedores[b.closest('tr').dataset.i]; if (!p) return;
  if (b.dataset.prov === 'editar') { abrirProveedor(p); return; }
  if (await confirmar('Eliminar proveedor', `¿Eliminar a ${p.razonSocial} (RUC ${p.ruc})?`, 'Eliminar')) {
    const res = await invCall('/api/proveedores/' + encodeURIComponent(p.ruc), 'DELETE');
    avisar(res.reply, !res.ok);
    refreshProveedores();
  }
});
$('provForm').addEventListener('submit', async e => {
  e.preventDefault();
  const datos = { ruc: $('pRuc').value.trim(), razonSocial: $('pRazon').value.trim(), telefono: $('pTel').value.trim(), direccion: $('pDir').value.trim() };
  if (!/^(10|15|17|20)\d{9}$/.test(datos.ruc)) { $('provErr').textContent = 'El RUC tiene 11 dígitos y empieza con 10, 15, 17 o 20.'; return; }
  if (!datos.razonSocial) { $('provErr').textContent = 'Escriba la razón social.'; return; }
  $('provGuardar').disabled = true;
  const res = editandoProveedor
    ? await invCall('/api/proveedores/' + encodeURIComponent(editandoProveedor.ruc), 'PATCH', datos)
    : await invCall('/api/proveedores', 'POST', datos);
  $('provGuardar').disabled = false;
  if (!res.ok) { $('provErr').textContent = res.reply; return; }
  $('provModal').classList.remove('show');
  avisar(res.reply);
  refreshProveedores();
});

const MOVIMIENTO = { ENTRADA: 'Entrada', VENTA: 'Venta', ANULACION: 'Anulación de venta', CORRECCION_VENTA: 'Corrección de venta',
  AJUSTE: 'Ajuste', COMPRA: 'Compra', ANULACION_COMPRA: 'Anulación de compra', ELIMINAR: 'Baja del producto' };
let kardexDe = null;
async function verKardex(nombre) {
  const k = await traer('/api/inventory/kardex?' + new URLSearchParams({ nombre, limite: 500 }));
  if (!k) { avisar('No pude abrir el kardex.', true); return; }
  kardexDe = k.producto;
  $('kxTitulo').textContent = 'Kardex · ' + k.producto;
  $('kxSub').textContent = `Stock actual: ${cant(k.stock)} ${k.unidad}` + (k.costo != null ? ` · costo promedio ${soles(k.costo)} · valor ${soles(Math.max(0, Number(k.stock)) * Number(k.costo))}` : ' · sin costo registrado');
  const filas = k.filas.slice().reverse();
  $('kxTabla').innerHTML = filas.length
    ? `<table class="tabla compacta"><thead><tr><th>Fecha</th><th>Movimiento</th><th class="der">Entrada</th><th class="der">Salida</th><th class="der">Saldo</th><th class="der">Costo prom.</th></tr></thead><tbody>`
      + filas.map(f => `<tr title="${esc(f.detalle)}"><td class="num">${fechaCorta((f.fecha || '').slice(0, 10))} ${esc((f.fecha || '').slice(11, 16))}</td>
        <td>${esc(MOVIMIENTO[f.tipo] || f.tipo)}<small style="display:block;color:var(--faint)">${esc(f.detalle)}</small></td>
        <td class="der num" style="color:var(--ok)">${Number(f.entrada) ? '+' + cant(f.entrada) : ''}</td>
        <td class="der num" style="color:var(--danger)">${Number(f.salida) ? '−' + cant(f.salida) : ''}</td>
        <td class="der num"><b>${f.saldo != null ? cant(f.saldo) : '—'}</b></td>
        <td class="der num">${f.costo != null ? soles(f.costo) : ''}</td></tr>`).join('') + '</tbody></table>'
    : '<div class="vacio"><b>Sin movimientos</b>Este producto aún no tiene entradas ni salidas registradas.</div>';
  $('kardexModal').classList.add('show');
}
$('kxCerrar').onclick = () => $('kardexModal').classList.remove('show');
$('kxExcel').onclick = () => kardexDe && descargar('/api/reportes/kardex.xlsx?' + new URLSearchParams({ nombre: kardexDe }), 'kardex.xlsx');
$('invExcel').onclick = () => descargar('/api/reportes/inventario.xlsx', 'inventario.xlsx');

async function descargar(url, nombre) {
  try {
    const r = await api(url);
    if (!r.ok) { const d = await r.json().catch(() => ({})); avisar(d.reply || 'No pude generar el archivo.', true); return; }
    const m = (r.headers.get('Content-Disposition') || '').match(/filename\*=UTF-8''([^;]+)/);
    const a = document.createElement('a');
    a.href = URL.createObjectURL(await r.blob());
    a.download = m ? decodeURIComponent(m[1]) : nombre;
    document.body.appendChild(a); a.click(); a.remove();
    setTimeout(() => URL.revokeObjectURL(a.href), 60000);
    avisar('Descargado: ' + a.download);
  } catch (e) {
    avisar('Sin conexión con el núcleo de JARVIS.', true);
  }
}

let confirmarResolver = null;
function confirmar(titulo, texto, boton) {
  $('confTitulo').textContent = titulo;
  $('confTexto').textContent = texto;
  $('confSi').textContent = boton || 'Confirmar';
  $('confirmModal').classList.add('show');
  setTimeout(() => $('confNo').focus(), 50);
  return new Promise(res => { confirmarResolver = res; });
}
function cerrarConfirmar(si) { $('confirmModal').classList.remove('show'); if (confirmarResolver) { confirmarResolver(si); confirmarResolver = null; } }
$('confSi').onclick = () => cerrarConfirmar(true);
$('confNo').onclick = () => cerrarConfirmar(false);
document.addEventListener('keydown', e => {
  if (e.key !== 'Escape') return;
  if ($('prodModal').classList.contains('show')) closeProduct();
  if ($('loteModal').classList.contains('show')) closeLote();
  if ($('confirmModal').classList.contains('show')) cerrarConfirmar(false);
  $('cliModal').classList.remove('show');
  $('receipt').classList.remove('show');
  ['compraModal', 'compraVer', 'provModal', 'kardexModal', 'atajosModal', 'usuariosModal', 'negocioModal', 'respaldosModal', 'tiendasModal'].forEach(id => $(id).classList.remove('show'));
  if ($('asistente').classList.contains('abierto')) abrirAsistente(false);
});
