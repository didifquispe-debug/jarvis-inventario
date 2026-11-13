// Proyecto DYSA - JARVIS Inventario. Inicio y Resultados: resumen del día, panel de ventas y gráfico.

let resumen = null;
function pintarSaludo() {
  const h = new Date().getHours();
  $('saludo').textContent = (h < 12 ? 'Buenos días' : h < 19 ? 'Buenas tardes' : 'Buenas noches') + (negocio.nombre ? ', ' + negocio.nombre : '');
  const f = new Date().toLocaleDateString('es-PE', { weekday: 'long', day: 'numeric', month: 'long', year: 'numeric' });
  $('fechaHoy').textContent = f.charAt(0).toUpperCase() + f.slice(1);
}
async function refreshResumen() {
  pintarSaludo();
  const [r, ultimas] = await Promise.all([traer('/api/inventory/resumen'),
    puede('COMPROBANTES') ? traer('/api/inventory/comprobantes?limite=6') : null]);
  if (r) {
    resumen = r;
    $('k-total').textContent = soles(r.totalHoy);
    $('k-ventas').textContent = (r.ventasHoy === 1 ? '1 venta' : r.ventasHoy + ' ventas')
      + (r.anuladasHoy ? ` · ${r.anuladasHoy} anulada${r.anuladasHoy === 1 ? '' : 's'}` : '');
    $('k-productos').textContent = r.productos.toLocaleString('es-PE');
    $('k-stock').textContent = r.stockBajo || r.sinStock ? `${r.stockBajo} con stock bajo · ${r.sinStock} sin stock` : 'Stock en orden';
    $('k-valor').textContent = soles(r.valorVenta);
    $('k-costo').textContent = Number(r.valorCosto) > 0 ? `${soles(r.valorCosto)} al costo` : 'a precio de venta';
    $('k-clientes').textContent = r.clientes.toLocaleString('es-PE');
    $('k-igv').textContent = r.igv === 'EXONERADO' ? 'Ventas exoneradas de IGV' : `IGV de hoy: ${soles(r.igvHoy)}`;
    renderPos();
  }
  if (ultimas) {
    $('ultimas').innerHTML = ultimas.length ? `<table class="tabla adaptable"><thead><tr><th>Comprobante</th><th>Cliente</th><th>Fecha</th><th class="der">Total</th></tr></thead>
      <tbody>${ultimas.map(filaComprobante).join('')}</tbody></table>`
      : '<div class="vacio"><b>Aún no hay ventas</b>Registre la primera en “Vender” o dígaselo a JARVIS.</div>';
  }
  const bajos = invItems.filter(p => Number(p.stock) <= Number(p.stockMin)).sort((a, b) => Number(a.stock) - Number(b.stock));
  $('reponerCuenta').textContent = bajos.length ? bajos.length + (bajos.length === 1 ? ' producto' : ' productos') : '';
  $('reponer').innerHTML = bajos.length ? bajos.slice(0, 8).map(p => `<li>
      <div><b>${esc(p.nombre)}</b><small>Mínimo ${cant(p.stockMin)} ${esc(p.unidad)}</small></div>
      <span class="etiqueta ${Number(p.stock) <= 0 ? 'mal' : 'aviso'}">${Number(p.stock) <= 0 ? 'Sin stock' : cant(p.stock) + ' ' + esc(p.unidad)}</span>
    </li>`).join('')
    : '<li class="vacio" style="display:block"><b>Todo en orden</b>Ningún producto está por debajo de su mínimo.</li>';
}
let panel = null;
const diaMes = f => { const [, m, d] = (f || '').split('-'); return d ? `${d}/${m}` : ''; };
async function refreshResultados() {
  const p = await traer('/api/reportes/panel?dias=' + $('resDias').value);
  if (!p) return;
  panel = p;
  const r = p.rango, n = p.porDia.length;
  const ganancia = per => `<span class="gan${Number(per.ganancia) < 0 ? ' neg' : ''}">Ganancia ${soles(per.ganancia)}${per.lineasSinCosto ? ' *' : ''}</span>`;
  const tarjeta = (titulo, per, destacado) => `<div class="tarjeta kpi${destacado ? ' destacado' : ''}"><span>${titulo}</span>
      <strong>${soles(per.total)}</strong>
      <small>${per.ventas === 1 ? '1 venta' : per.ventas + ' ventas'}${per.anuladas ? ` · ${per.anuladas} anulada${per.anuladas === 1 ? '' : 's'}` : ''}</small>
      ${ganancia(per)}</div>`;
  const ticket = r.ventas ? Number(r.total) / r.ventas : 0;
  const margen = Number(r.base) > 0 ? Math.round(Number(r.ganancia) * 100 / Number(r.base)) : null;
  $('resKpis').innerHTML = tarjeta('Hoy', p.deHoy, true) + tarjeta('Esta semana', p.semana) + tarjeta('Este mes', p.mes)
    + `<div class="tarjeta kpi"><span>Ticket promedio</span><strong>${soles(ticket)}</strong><small>últimos ${n} días</small>
        <span class="gan">${margen === null ? 'Margen —' : 'Margen ' + margen + ' %'}</span></div>`
    + ([p.deHoy, p.semana, p.mes, r].some(x => x.lineasSinCosto)
      ? '<p class="nota-kpi">* Se vendieron productos sin costo registrado y su ganancia no se cuenta. Agregue el costo en la ficha del producto o registre su compra.</p>' : '');
  $('resRango').textContent = `${diaMes(r.desde)} al ${diaMes(r.hasta)} · ${soles(r.total)}`;
  $('resTopRango').textContent = `últimos ${n} días`;
  pintarGrafico(p.porDia);
  $('resTablaDias').innerHTML = `<table class="tabla"><thead><tr><th>Día</th><th class="der">Ventas</th><th class="der">Total</th><th class="der">Ganancia</th></tr></thead>
    <tbody>${p.porDia.slice().reverse().map(d => `<tr><td class="num">${fechaCorta(d.fecha)}</td><td class="der num">${d.ventas}</td>
      <td class="der num">${soles(d.total)}</td><td class="der num">${soles(d.ganancia)}</td></tr>`).join('')}</tbody></table>`;

  $('resTop').innerHTML = p.masVendidos.length ? `<table class="tabla adaptable"><thead><tr><th>Producto</th><th class="der">Cantidad</th><th class="der">Vendido</th><th class="der">Ganancia</th></tr></thead>
    <tbody>${p.masVendidos.map((m, i) => `<tr><td data-t="Producto"><span class="num" style="color:var(--faint)">${i + 1}.</span> ${esc(m.producto)}</td>
      <td data-t="Cantidad" class="der num">${cant(m.cantidad)} ${esc(m.unidad)}</td><td data-t="Vendido" class="der num">${soles(m.total)}</td>
      <td data-t="Ganancia" class="der num">${m.ganancia === null ? '<span style="color:var(--faint)" title="Sin costo registrado">—</span>' : soles(m.ganancia)}</td></tr>`).join('')}</tbody></table>`
    : '<div class="vacio"><b>Sin ventas en este periodo</b>Cuando venda, aquí verá lo que más sale.</div>';

  $('resAlertasCuenta').textContent = p.alertas.length ? p.alertas.length + (p.alertas.length === 1 ? ' producto' : ' productos') : '';
  $('resAlertas').innerHTML = p.alertas.length ? p.alertas.slice(0, 12).map(a => `<li>
      <div><b>${esc(a.producto)}</b><small>Mínimo ${cant(a.stockMin)} ${esc(a.unidad)}</small></div>
      <span class="etiqueta ${a.estado === 'SIN_STOCK' ? 'mal' : 'aviso'}">${a.estado === 'SIN_STOCK' ? 'Sin stock' : 'Bajo · ' + cant(a.stock) + ' ' + esc(a.unidad)}</span>
    </li>`).join('')
    : '<li class="vacio" style="display:block"><b>Todo en orden</b>Ningún producto está por debajo de su mínimo.</li>';

  const q = p.sinMovimiento;
  $('resQuietosCuenta').textContent = `con stock y sin ventas en ${p.diasSinMovimiento} días`;
  $('resQuietos').innerHTML = q.length ? `<table class="tabla adaptable"><thead><tr><th>Producto</th><th class="der">Stock</th><th class="der">Valor parado</th><th>Última venta</th></tr></thead>
    <tbody>${q.map(s => `<tr><td data-t="Producto">${esc(s.producto)}</td><td data-t="Stock" class="der num">${cant(s.stock)} ${esc(s.unidad)}</td>
      <td data-t="Valor parado" class="der num">${soles(s.valorVenta)}</td>
      <td data-t="Última venta" class="num">${s.ultimaVenta ? fechaCorta(s.ultimaVenta) : '<span style="color:var(--faint)">Nunca</span>'}</td></tr>`).join('')}</tbody></table>`
    : '<div class="vacio"><b>Todo se mueve</b>Cada producto con stock se vendió en el último mes.</div>';
}
function pintarGrafico(dias) {
  const caja = $('resGrafico');
  if (!dias.some(d => Number(d.total) > 0)) {
    caja.innerHTML = '<div class="vacio"><b>Sin ventas en este periodo</b>Registre ventas y aquí verá cómo van día a día.</div>';
    return;
  }
  const W = Math.max(caja.clientWidth - 36, 260), H = 240, izq = 50, abajo = 22, arriba = 8, alto = H - arriba - abajo;
  const max = Math.max(...dias.map(d => Number(d.total)));
  const paso = [1, 10, 100, 1000, 10000, 100000, 1000000].flatMap(e => [e, 2 * e, 5 * e]).find(s => max / s <= 4);
  const tope = Math.ceil(max / paso) * paso;
  const y = v => arriba + alto * (1 - v / tope);
  const ancho = (W - izq) / dias.length, barra = Math.max(2, Math.min(24, ancho - 2));
  const cada = Math.ceil(dias.length / Math.max(1, Math.floor((W - izq) / 48)));
  let svg = '';
  for (let v = 0; v <= tope; v += paso)
    svg += `<line class="${v === 0 ? 'base' : 'guia'}" x1="${izq}" x2="${W}" y1="${y(v)}" y2="${y(v)}"/>`
      + `<text class="eje" x="${izq - 8}" y="${y(v) + 4}" text-anchor="end">${v >= 1000 ? (v / 1000).toLocaleString('es-PE') + ' mil' : v}</text>`;
  dias.forEach((d, i) => {
    const x = izq + i * ancho + (ancho - barra) / 2, top = y(Number(d.total)), r = Math.min(4, barra / 2, y(0) - top);
    const forma = top < y(0) ? `M${x},${y(0)}V${top + r}Q${x},${top} ${x + r},${top}H${x + barra - r}Q${x + barra},${top} ${x + barra},${top + r}V${y(0)}Z` : '';
    svg += `<rect class="zona" data-i="${i}" x="${izq + i * ancho}" y="${arriba}" width="${ancho}" height="${alto}" tabindex="0"`
      + ` aria-label="${fechaCorta(d.fecha)}: ${soles(d.total)}, ${d.ventas === 1 ? '1 venta' : d.ventas + ' ventas'}"/><path class="barra-dia" d="${forma}"/>`;
    if ((dias.length - 1 - i) % cada === 0)
      svg += `<text class="eje" x="${izq + i * ancho + ancho / 2}" y="${H - 6}" text-anchor="middle">${diaMes(d.fecha)}</text>`;
  });
  caja.innerHTML = `<svg viewBox="0 0 ${W} ${H}" role="img" aria-label="Ventas por día en ${esc(moneda.nombre)}">${svg}</svg><div class="grafico-tip" hidden></div>`;
  const tip = caja.querySelector('.grafico-tip');
  const mostrar = z => {
    const d = dias[z.dataset.i], b = z.getBoundingClientRect(), c = caja.getBoundingClientRect();
    tip.innerHTML = `<small>${fechaCorta(d.fecha)}</small><b>${soles(d.total)}</b>`
      + `<small>${d.ventas === 1 ? '1 venta' : d.ventas + ' ventas'} · ganancia ${soles(d.ganancia)}</small>`;
    tip.hidden = false;
    tip.style.left = Math.min(Math.max(b.left - c.left + b.width / 2, 90), c.width - 90) + 'px';
    tip.style.top = (b.top - c.top + (y(Number(d.total)) - arriba) * b.height / alto) + 'px';
  };
  caja.querySelectorAll('.zona').forEach(z => {
    z.addEventListener('mouseenter', () => mostrar(z));
    z.addEventListener('focus', () => mostrar(z));
    z.addEventListener('click', () => mostrar(z));
    z.addEventListener('mouseleave', () => { tip.hidden = true; });
    z.addEventListener('blur', () => { tip.hidden = true; });
  });
}
$('resDias').addEventListener('change', refreshResultados);
$('resExcel').onclick = () => {
  if (panel) descargar('/api/reportes/ventas.xlsx?' + new URLSearchParams({ desde: panel.rango.desde, hasta: panel.rango.hasta }), 'ventas.xlsx');
};
let redibujo;
window.addEventListener('resize', () => {
  clearTimeout(redibujo);
  redibujo = setTimeout(() => { if (vista === 'resultados' && panel) pintarGrafico(panel.porDia); }, 150);
});

function filaComprobante(c) {
  const fecha = (c.fecha || '').split('-').reverse().join('/');
  return `<tr class="clic" data-comp="${c.numero}" tabindex="0" title="Enter: ver el comprobante">
    <td data-t="Comprobante"><b class="num">${esc(codigoDe(c))}</b> <span class="etiqueta ${c.tipo === 'BOLETA' ? 'acento' : ''}">${tipoCorto(c.tipo)}</span>
      ${c.anulado ? '<span class="etiqueta mal">Anulado</span>' : ''}</td>
    <td data-t="Cliente">${c.cliente ? esc(c.cliente) : '<span style="color:var(--faint)">Clientes varios</span>'}</td>
    <td data-t="Fecha" class="num">${esc(fecha)} ${esc(c.hora)}</td>
    <td data-t="Total" class="der num"${c.anulado ? ' style="text-decoration:line-through;color:var(--faint)"' : ''}>${soles(c.total)}</td></tr>`;
}
document.addEventListener('click', e => {
  const fila = e.target.closest('[data-comp]');
  if (fila) verComprobante(fila.dataset.comp);
});
