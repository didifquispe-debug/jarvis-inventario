// Proyecto DYSA - JARVIS Inventario. Aviso de versión nueva, recibo e impresión del comprobante, carrito del asistente.

// DYSA: último comprobante cobrado o abierto; es el que imprime "imprime la boleta".
async function revisarVersion() {
  const v = await traer('/api/version');
  const a = $('nuevaVersion');
  a.hidden = !(v && v.nueva && puede('ADMINISTRAR'));
  if (a.hidden) return;
  a.href = v.url;
  $('nuevaVersionTxt').textContent = 'Versión nueva: ' + v.nueva;
  a.title = 'Tiene la ' + v.actual + '. Descargue el instalador nuevo desde GitHub; sus datos se conservan.';
}
let dysaUltimoRecibo = null;
let impresion = { ancho: 80, alCobrar: false, directa: false };
async function cargarImpresion() { const d = await traer('/api/impresion'); if (d) impresion = d; }
let negocio = { nombre: '', ruc: '', direccion: '' };
const itemRows = items => items.map(v => `<tr>
    <td class="num">${cant(v.cantidad)}</td>
    <td>${esc(v.producto)}<br><small style="opacity:.6">${esc(v.unidad)}<span class="pu58"> · ${soles(v.precioUnitario)} c/u</span></small></td>
    <td class="n">${soles(v.precioUnitario)}</td>
    <td class="n">${soles(v.monto)}</td>
  </tr>`).join('');
const codigoDe = c => c.codigo || ('N° ' + nroComp(c.numero));
const tipoCorto = t => t === 'NOTA_VENTA' ? 'Nota de venta' : 'Boleta';

function dysaHtmlRecibo(r) {
  const c = r.comprobante, n = r.negocio || negocio;
  const fecha = (c.fecha || '').split('-').reverse().join('/');
  const conIgv = Number(c.tasaIgv) > 0;
  const cliente = c.cliente
    ? `${esc(c.cliente.nombre)} · ${esc(c.cliente.tipoDocumento)} ${esc(c.cliente.documento)}` : 'CLIENTES VARIOS';
  return `
    <div class="rc-head">
      <b>${esc(n.nombre || 'Mi Negocio')}</b>
      ${n.ruc ? `<span>RUC ${esc(n.ruc)}</span>` : ''}
      ${n.direccion ? `<span>${esc(n.direccion)}</span>` : ''}
    </div>
    <div class="rc-doc"><b>${esc((c.tipoDescripcion || 'Comprobante').toUpperCase())}</b><span>${esc(codigoDe(c))}</span>
      ${c.anulado ? '<div class="rc-anulado">ANULADO</div>' : ''}</div>
    <div class="rc-meta"><span>${esc(fecha)} ${esc(c.hora)}</span><span>SOLES · CONTADO</span></div>
    <div class="rc-cliente">Cliente: ${cliente}</div>
    <table class="rc-table">
      <thead><tr><th>CANT.</th><th>DESCRIPCIÓN</th><th class="n">P. UNIT.</th><th class="n">IMPORTE</th></tr></thead>
      <tbody>${itemRows(c.items)}</tbody>
    </table>
    <div class="rc-sub"><span>${conIgv ? 'OP. GRAVADA' : 'OP. EXONERADA'}</span><span>${soles(c.base)}</span></div>
    <div class="rc-sub"><span>IGV (${Math.round(Number(c.tasaIgv || 0) * 100)}%)</span><span>${soles(c.igv)}</span></div>
    <div class="rc-total"><span>TOTAL</span><b>${soles(c.total)}</b></div>
    <div class="rc-letras">SON: ${esc(c.totalEnLetras || '')}</div>
    <div class="rc-foot">${esc(c.leyendaIgv || '')}<br>¡Gracias por su compra!</div>`;
}
function dysaMostrarRecibo(r) {
  dysaUltimoRecibo = r;
  $('receiptBody').innerHTML = dysaHtmlRecibo(r);
  $('receipt').classList.add('show');
}
async function verComprobante(numero) {
  const c = await traer('/api/inventory/comprobantes/' + numero);
  if (c) dysaMostrarRecibo({ comprobante: c, negocio }); else avisar('No pude abrir ese comprobante.', true);
}
function dysaEnlaceRecibo(entrada, r) {
  if (!entrada) return;
  const b = document.createElement('button');
  b.type = 'button'; b.className = 'boton ver';
  b.textContent = 'Ver ' + tipoCorto(r.comprobante.tipo).toLowerCase() + ' ' + codigoDe(r.comprobante);
  b.onclick = () => dysaMostrarRecibo(r);
  const imp = document.createElement('button');
  imp.type = 'button'; imp.className = 'boton ver';
  imp.textContent = 'Imprimir';
  imp.setAttribute('aria-label', 'Imprimir ' + tipoCorto(r.comprobante.tipo).toLowerCase() + ' ' + codigoDe(r.comprobante));
  imp.onclick = () => { dysaUltimoRecibo = r; dysaImprimirRecibo(); };
  entrada.append(b, ' ', imp);
}
function dysaImprimirRecibo() {
  if (!dysaUltimoRecibo) return false;
  $('marcoImpresion')?.remove();
  const f = document.createElement('iframe');
  f.id = 'marcoImpresion'; f.tabIndex = -1; f.setAttribute('aria-hidden', 'true');
  f.style.cssText = 'position:fixed;right:0;bottom:0;width:0;height:0;border:0';
  document.body.appendChild(f);
  const d = f.contentWindow.document;
  d.open();
  d.write(`<!doctype html><html lang="es"><head><meta charset="utf-8"><title>${esc(codigoDe(dysaUltimoRecibo.comprobante))}</title>
    <style>
      @page { size: ${impresion.ancho}mm auto; margin: ${impresion.ancho === 58 ? 2 : 4}mm; }
      body { font: ${impresion.ancho === 58 ? 11 : 13}px/1.35 "Courier New", monospace; color: #000; background: #fff;
        width: ${impresion.ancho === 58 ? 52 : 72}mm; margin: 0 auto; }
      .pu58 { display: none; }
      ${impresion.ancho === 58 ? 'th:nth-child(3), td:nth-child(3) { display: none; } .pu58 { display: inline; } th { font-size: 9px; } .rc-head b, .rc-total { font-size: 13px !important; } .rc-letras { font-size: 9px; }' : ''}
      .rc-head { text-align: center; margin-bottom: 8px; } .rc-head b { display: block; font-size: 15px; } .rc-head span { display: block; }
      .rc-meta { display: flex; justify-content: space-between; border-top: 1px dashed #000; border-bottom: 1px dashed #000; padding: 4px 0; }
      table { width: 100%; border-collapse: collapse; margin: 6px 0; } th { text-align: left; font-size: 11px; border-bottom: 1px solid #000; }
      td { vertical-align: top; padding: 3px 0; } .n { text-align: right; white-space: nowrap; padding-left: 6px; }
      .rc-total { display: flex; justify-content: space-between; border-top: 1px solid #000; padding: 6px 0; font-weight: bold; font-size: 15px; }
      .rc-foot { text-align: center; margin-top: 8px; }
      .rc-doc { text-align: center; border: 1px solid #000; padding: 4px; margin-bottom: 6px; } .rc-doc b, .rc-doc span { display: block; }
      .rc-cliente { padding: 4px 0; border-bottom: 1px dashed #000; } .rc-sub { display: flex; justify-content: space-between; }
      .rc-letras { font-size: 11px; padding-top: 4px; } .rc-anulado { font-weight: bold; letter-spacing: 4px; }
    </style></head><body>${dysaHtmlRecibo(dysaUltimoRecibo)}</body></html>`);
  d.close();
  setTimeout(() => { try { f.contentWindow.focus(); f.contentWindow.print(); } catch (e) {} }, 300);
  return true;
}
async function dysaAbrirPdfRecibo() {
  if (!dysaUltimoRecibo) return;
  try {
    const r = await api('/api/inventory/comprobantes/' + dysaUltimoRecibo.comprobante.numero + '/pdf');
    if (!r.ok) { avisar('No pude generar el PDF del comprobante.', true); return; }
    const url = URL.createObjectURL(await r.blob());
    if (!window.open(url, '_blank')) location.href = url;
    setTimeout(() => URL.revokeObjectURL(url), 60000);
  } catch (e) {
    avisar('Sin conexión con el núcleo de JARVIS.', true);
  }
}
$('receiptClose').onclick = () => $('receipt').classList.remove('show');
$('receiptPrint').onclick = dysaImprimirRecibo;
$('receiptPdf').onclick = dysaAbrirPdfRecibo;

function renderCart(c) {
  const box = $('cart');
  if (!c) { box.hidden = true; return; }
  box.hidden = false;
  $('cartBody').innerHTML = c.items.length
    ? c.items.map(v => `<li><span>${cant(v.cantidad)} × ${esc(v.producto)}</span><span class="num">${soles(v.monto)}</span></li>`).join('')
    : '<li><span>Dicte los productos: “2 poleras M”, “1 zapatilla 40”…</span></li>';
  $('cartTotal').textContent = soles(c.total);
  $('cartTipo').value = c.tipo === 'NOTA_VENTA' ? 'nota de venta' : 'boleta';
  if (document.activeElement !== $('cartDni')) $('cartDni').value = c.cliente ? c.cliente.documento : '';
  $('cartCliente').textContent = c.cliente ? 'Cliente: ' + c.cliente.nombre : '';
}
async function opcionesCarrito(cambios) {
  const res = await invCall('/api/inventory/venta/opciones', 'PUT', cambios);
  if (!res.ok) addEntry('sys', res.reply);
  refreshCart();
}
$('cartTipo').addEventListener('change', () => opcionesCarrito({ tipo: $('cartTipo').value }));
$('cartDni').addEventListener('keydown', e => {
  if (e.key !== 'Enter') return;
  e.preventDefault();
  opcionesCarrito({ cliente: $('cartDni').value.trim() });
});
async function refreshCart() {
  try {
    const r = await api('/api/inventory/venta');
    if (r.ok) { const t = await r.text(); renderCart(t ? JSON.parse(t) : null); }
  } catch (e) {}
}
