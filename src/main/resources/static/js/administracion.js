// Proyecto DYSA - JARVIS Inventario. Sesión y usuarios, datos del negocio, tiendas y copias de seguridad.

function aplicarSesion() {
  document.querySelectorAll('[data-permiso]').forEach(el => { el.hidden = !puede(el.dataset.permiso); });
  const conNombre = conUsuarios && yo && yo.nombre !== 'Laptop';
  $('salirBtn').hidden = !conNombre;
  $('salirTexto').textContent = conNombre ? 'Salir · ' + yo.nombre : 'Salir';
  $('salirBtn').title = conNombre ? `${yo.nombre} (${yo.rolDescripcion}). Toque para cerrar la sesión.` : '';
  if (!vistaPermitida(vista)) irA('inicio');
}
async function cargarSesion() {
  const s = await traer('/api/sesion');
  if (!s) return;
  conUsuarios = s.usuarios;
  yo = s.yo;
  tiendaActual = s.tienda || tiendaActual;
  tiendasLista = s.tiendas || [];
  pintarTienda();
  aplicarSesion();
  if (conUsuarios && !yo) mostrarLogin(s.nombres);
}
let loginElegido = '';
function mostrarLogin(nombres) {
  const caja = $('loginUsuarios');
  caja.innerHTML = nombres.map(n => `<button type="button" role="radio" aria-checked="false" data-nombre="${esc(n)}">${esc(n)}</button>`).join('');
  loginElegido = nombres.length === 1 ? nombres[0] : '';
  caja.querySelectorAll('button').forEach(b => {
    b.setAttribute('aria-checked', String(b.dataset.nombre === loginElegido));
    b.onclick = () => {
      loginElegido = b.dataset.nombre;
      caja.querySelectorAll('button').forEach(x => x.setAttribute('aria-checked', String(x === b)));
      $('loginPin').focus();
    };
  });
  $('loginPin').value = '';
  $('loginErr').textContent = '';
  $('loginModal').classList.add('show');
  setTimeout(() => (loginElegido ? $('loginPin') : caja.querySelector('button'))?.focus(), 80);
}
let pidiendoLogin = false;
async function pedirLogin() {
  if (pidiendoLogin || $('loginModal').classList.contains('show')) return;
  pidiendoLogin = true;
  guardar('jarvisUsuario', '');
  yo = null;
  try { await cargarSesion(); } finally { pidiendoLogin = false; }
}
$('loginForm').addEventListener('submit', async e => {
  e.preventDefault();
  if (!loginElegido) { $('loginErr').textContent = 'Elija su usuario.'; return; }
  const pin = $('loginPin').value.trim();
  if (!/^\d{4,6}$/.test(pin)) { $('loginErr').textContent = 'El PIN tiene de 4 a 6 números.'; $('loginPin').focus(); return; }
  $('loginEntrar').disabled = true;
  const res = await invCall('/api/sesion', 'POST', { usuario: loginElegido, pin });
  $('loginEntrar').disabled = false;
  if (!res.ok) { $('loginErr').textContent = res.reply || 'No pude iniciar sesión.'; $('loginPin').select(); return; }
  guardar('jarvisUsuario', res.data.token);
  yo = res.data.yo;
  $('loginModal').classList.remove('show');
  aplicarSesion();
  irA(vistaPermitida(vista) ? vista : 'inicio');
  refrescarTodo();
  avisar(res.reply);
});
$('salirBtn').onclick = async () => {
  await invCall('/api/sesion', 'DELETE');
  guardar('jarvisUsuario', '');
  yo = null;
  await cargarSesion();
};

let usuariosLista = [];
async function abrirUsuarios() {
  $('usErr').textContent = '';
  $('usuariosModal').classList.add('show');
  await refreshUsuarios();
}
async function refreshUsuarios() {
  const l = await traer('/api/usuarios');
  if (!l) { $('usLista').innerHTML = '<div class="vacio">No pude leer los usuarios.</div>'; return; }
  usuariosLista = l;
  const roles = [['ADMIN', 'Administrador'], ['CAJERO', 'Cajero'], ['ALMACEN', 'Almacén']];
  const variasTiendas = tiendasLista.length > 1;
  $('usTiendaLbl').hidden = !variasTiendas;
  if (variasTiendas) $('usTienda').innerHTML = opcionesTienda(tiendaActual.id);
  $('usLista').innerHTML = l.length ? `<table class="tabla adaptable"><thead><tr><th>Usuario</th><th>Rol</th>${variasTiendas ? '<th>Tienda</th>' : ''}<th>Estado</th><th>Nuevo PIN</th><th class="acciones"></th></tr></thead>
    <tbody>${l.map((u, i) => `<tr data-i="${i}"><td data-t="Usuario"><b>${esc(u.nombre)}</b></td>
      <td data-t="Rol"><select class="campo" data-accion="rol" aria-label="Rol de ${esc(u.nombre)}">${roles.map(([v, t]) =>
        `<option value="${v}"${u.rol === v ? ' selected' : ''}>${t}</option>`).join('')}</select></td>
      ${variasTiendas ? `<td data-t="Tienda"><select class="campo" data-accion="tienda" aria-label="Tienda de ${esc(u.nombre)}">${opcionesTienda(u.tienda)}</select></td>` : ''}
      <td data-t="Estado"><span class="etiqueta ${u.activo ? 'ok' : 'mal'}">${u.activo ? 'Activo' : 'Inactivo'}</span></td>
      <td data-t="Nuevo PIN"><input class="campo pin-nuevo" type="password" inputmode="numeric" maxlength="6" autocomplete="new-password"
        aria-label="Nuevo PIN de ${esc(u.nombre)}" placeholder="••••"> <button class="boton" type="button" data-accion="pin">Cambiar</button></td>
      <td class="acciones"><button class="boton" type="button" data-accion="activo">${u.activo ? 'Desactivar' : 'Activar'}</button>
        <button class="icono peligro" type="button" data-accion="eliminar" title="Eliminar a ${esc(u.nombre)}" aria-label="Eliminar a ${esc(u.nombre)}"><svg><use href="#i-basura"/></svg></button></td></tr>`).join('')}</tbody></table>`
    : '<div class="vacio"><b>Aún no hay usuarios</b>Cree primero al administrador: desde ese momento los celulares pedirán usuario y PIN.</div>';
  $('usRol').value = l.length ? 'CAJERO' : 'ADMIN';
}
async function cambiarUsuario(u, cambios, aviso) {
  const res = await invCall('/api/usuarios/' + u.id, 'PATCH', cambios);
  $('usErr').textContent = res.ok ? '' : res.reply;
  if (res.ok) avisar(aviso || res.reply);
  await refreshUsuarios();
  cargarSesion();
}
$('usLista').addEventListener('change', e => {
  const sel = e.target.closest('select[data-accion]'); if (!sel) return;
  const u = usuariosLista[sel.closest('tr').dataset.i];
  if (sel.dataset.accion === 'tienda') cambiarUsuario(u, { tienda: Number(sel.value) }, `${u.nombre} ahora trabaja en otra tienda.`);
  else cambiarUsuario(u, { rol: sel.value });
});
$('usLista').addEventListener('click', async e => {
  const b = e.target.closest('button[data-accion]'); if (!b) return;
  const fila = b.closest('tr'), u = usuariosLista[fila.dataset.i]; if (!u) return;
  if (b.dataset.accion === 'pin') {
    const pin = fila.querySelector('.pin-nuevo').value.trim();
    if (!pin) { $('usErr').textContent = 'Escriba el nuevo PIN de ' + u.nombre + '.'; fila.querySelector('.pin-nuevo').focus(); return; }
    cambiarUsuario(u, { pin }, 'PIN de ' + u.nombre + ' cambiado.');
  } else if (b.dataset.accion === 'activo') {
    cambiarUsuario(u, { activo: !u.activo });
  } else if (b.dataset.accion === 'eliminar') {
    if (!await confirmar('Eliminar usuario', `¿Eliminar a ${u.nombre}? Sus ventas y movimientos se conservan.`, 'Eliminar')) return;
    const res = await invCall('/api/usuarios/' + u.id, 'DELETE');
    $('usErr').textContent = res.ok ? '' : res.reply;
    if (res.ok) avisar(res.reply);
    await refreshUsuarios();
    cargarSesion();
  }
});
$('usForm').addEventListener('submit', async e => {
  e.preventDefault();
  const datos = { nombre: $('usNombre').value.trim(), rol: $('usRol').value, pin: $('usPin').value.trim(),
    tienda: tiendasLista.length > 1 ? Number($('usTienda').value) : 1 };
  if (!datos.nombre) { $('usErr').textContent = 'Escriba el nombre del usuario.'; $('usNombre').focus(); return; }
  const res = await invCall('/api/usuarios', 'POST', datos);
  $('usErr').textContent = res.ok ? '' : res.reply;
  if (!res.ok) return;
  avisar(res.reply);
  $('usNombre').value = ''; $('usPin').value = '';
  await refreshUsuarios();
  cargarSesion();
});
$('usuariosBtn').onclick = abrirUsuarios;

const CAMPOS_NEGOCIO = { negNombre: 'nombre', negRuc: 'ruc', negTelefono: 'telefono', negDireccion: 'direccion', negEmail: 'email',
  negSimbolo: 'monedaSimbolo', negMoneda: 'monedaNombre', negSingular: 'monedaSingular' };
async function abrirNegocio() {
  const d = await traer('/api/negocio');
  if (!d) { avisar('No pude leer los datos del negocio.', true); return; }
  Object.entries(CAMPOS_NEGOCIO).forEach(([id, k]) => { $(id).value = d[k] || ''; });
  await cargarImpresion();
  $('negAncho').value = String(impresion.ancho);
  $('negAlCobrar').checked = impresion.alCobrar;
  $('negDirecta').checked = impresion.directa;
  $('negErr').textContent = '';
  $('negocioModal').classList.add('show');
}
$('negocioForm').addEventListener('submit', async e => {
  e.preventDefault();
  const datos = {};
  Object.entries(CAMPOS_NEGOCIO).forEach(([id, k]) => { datos[k] = $(id).value.trim(); });
  const res = await invCall('/api/negocio', 'PUT', datos);
  if (!res.ok) { $('negErr').textContent = res.reply; return; }
  const imp = await invCall('/api/impresion', 'PUT',
    { ancho: Number($('negAncho').value), alCobrar: $('negAlCobrar').checked, directa: $('negDirecta').checked });
  if (!imp.ok) { $('negErr').textContent = imp.reply; return; }
  await cargarImpresion();
  $('negocioModal').classList.remove('show');
  avisar(res.reply + (/directa/.test(imp.reply) ? ' ' + imp.reply.replace(/^[^.]*\. /, '') : ''));
  await refreshSystem();
  pintarSaludo();
});
$('negocioBtn').onclick = abrirNegocio;
$('negCancelar').onclick = () => $('negocioModal').classList.remove('show');
$('usCerrar').onclick = () => $('usuariosModal').classList.remove('show');

let tiendaActual = { id: 1, nombre: '' }, tiendasLista = [];
const opcionesTienda = sel => tiendasLista.map(t => `<option value="${t.id}"${t.id === sel ? ' selected' : ''}>${esc(t.nombre)}</option>`).join('');
function pintarTienda() {
  const elige = tiendasLista.length > 1;
  $('tiendaCaja').hidden = !elige && tiendaActual.id === 1;
  $('tiendaSel').hidden = !elige;
  $('tiendaNombre').hidden = elige;
  $('tiendaNombre').textContent = tiendaActual.nombre || '';
  if (elige) $('tiendaSel').innerHTML = opcionesTienda(tiendaActual.id);
  if ((leer('jarvisTienda') || '1') !== String(tiendaActual.id)) guardar('jarvisTienda', String(tiendaActual.id));
}
async function cambiarTienda(id) {
  guardar('jarvisTienda', String(id));
  posLineas = []; posCliente = null; guardarPos();
  await cargarSesion();
  await refreshSystem();
  cargarImpresion();
  refrescarDatos();
  irA(vista);
  avisar('Trabajando en ' + tiendaActual.nombre + '.');
}
$('tiendaSel').onchange = () => cambiarTienda(Number($('tiendaSel').value));

async function refreshTiendas() {
  const d = await traer('/api/tiendas');
  if (!d) { $('tiLista').innerHTML = '<div class="vacio">No pude leer las tiendas.</div>'; return; }
  tiendasLista = d.tiendas;
  $('tiLista').innerHTML = `<table class="tabla adaptable"><thead><tr><th>Nombre</th><th>Creada</th><th class="acciones"></th></tr></thead>
    <tbody>${tiendasLista.map((t, i) => `<tr data-i="${i}">
      <td data-t="Nombre"><div class="ti-nombre"><input class="campo" value="${esc(t.nombre)}" maxlength="60" aria-label="Nombre de la tienda ${esc(t.nombre)}">
        <button class="boton" type="button" data-accion="renombrar">Guardar</button></div></td>
      <td data-t="Creada">${esc((t.creada || '').slice(0, 10).split('-').reverse().join('/'))}</td>
      <td class="acciones">${t.id === tiendaActual.id ? '<span class="etiqueta ok">En uso</span>'
        : '<button class="boton" type="button" data-accion="usar">Trabajar aquí</button>'}</td></tr>`).join('')}</tbody></table>`;
}
async function abrirTiendas() {
  $('tiErr').textContent = '';
  $('tiendasModal').classList.add('show');
  await refreshTiendas();
}
$('tiendasBtn').onclick = abrirTiendas;
$('tiCerrar').onclick = () => $('tiendasModal').classList.remove('show');
$('tiForm').addEventListener('submit', async e => {
  e.preventDefault();
  const nombre = $('tiNombre').value.trim();
  if (!nombre) { $('tiErr').textContent = 'Escriba el nombre de la tienda.'; $('tiNombre').focus(); return; }
  const res = await invCall('/api/tiendas', 'POST', { nombre });
  $('tiErr').textContent = res.ok ? '' : res.reply;
  if (!res.ok) return;
  avisar(res.reply);
  $('tiNombre').value = '';
  await refreshTiendas();
  await cargarSesion();
});
$('tiLista').addEventListener('click', async e => {
  const b = e.target.closest('button[data-accion]'); if (!b) return;
  const fila = b.closest('tr'), t = tiendasLista[fila.dataset.i]; if (!t) return;
  if (b.dataset.accion === 'usar') {
    await cambiarTienda(t.id);
    await refreshTiendas();
    return;
  }
  const res = await invCall('/api/tiendas/' + t.id, 'PATCH', { nombre: fila.querySelector('input').value.trim() });
  $('tiErr').textContent = res.ok ? '' : res.reply;
  if (res.ok) avisar(res.reply);
  await refreshTiendas();
  await cargarSesion();
});

const TIPOS_RESPALDO = { DIARIO: ['Diaria', 'ok'], MANUAL: ['Manual', 'acento'], ANTES_DE_NUEVO: ['Antes de inventario nuevo', 'aviso'],
  ANTES_DE_RESTAURAR: ['Antes de restaurar', 'aviso'] };
let respaldosLista = [];
function tamano(b) { return b >= 1048576 ? (b / 1048576).toFixed(1) + ' MB' : Math.max(1, Math.round(b / 1024)) + ' KB'; }
function fechaRespaldo(r) {
  const d = new Date(r.fecha);
  if (isNaN(d)) return r.fecha;
  const dia = d.toLocaleDateString('es-PE', { weekday: 'short', day: '2-digit', month: 'short', year: 'numeric' });
  return r.tipo === 'DIARIO' && d.getHours() === 0 && d.getMinutes() === 0 ? dia
    : dia + ', ' + d.toLocaleTimeString('es-PE', { hour: '2-digit', minute: '2-digit' });
}
async function refreshRespaldos(llenarCarpeta) {
  const d = await traer('/api/respaldos');
  if (!d) { $('respLista').innerHTML = '<div class="vacio">No pude leer las copias de seguridad.</div>'; return; }
  respaldosLista = d.respaldos || [];
  $('respDonde').textContent = 'Se guardan en ' + d.carpeta + (d.carpetaExtra ? ' y también en ' + d.carpetaExtra : '') + '.';
  if (llenarCarpeta || document.activeElement !== $('respCarpeta')) $('respCarpeta').value = d.carpetaExtra || '';
  $('respLista').innerHTML = respaldosLista.length ? `<table class="tabla adaptable"><thead><tr><th>Fecha</th><th>Tipo</th><th>Tamaño</th><th class="acciones"></th></tr></thead>
    <tbody>${respaldosLista.map((r, i) => { const [t, c] = TIPOS_RESPALDO[r.tipo] || [r.tipo, '']; return `<tr data-i="${i}">
      <td data-t="Fecha"><b>${esc(fechaRespaldo(r))}</b></td><td data-t="Tipo"><span class="etiqueta ${c}">${esc(t)}</span></td>
      <td data-t="Tamaño">${tamano(r.bytes)}</td>
      <td class="acciones"><button class="boton" type="button" data-accion="descargar" title="Guardar este archivo en otra parte">Descargar</button>
        <button class="boton" type="button" data-accion="restaurar">Restaurar</button></td></tr>`; }).join('')}</tbody></table>`
    : '<div class="vacio"><b>Aún no hay copias</b>Pulse «Hacer copia ahora» para crear la primera.</div>';
}
async function abrirRespaldos() {
  $('respErr').textContent = '';
  $('respaldosModal').classList.add('show');
  await refreshRespaldos(true);
}
$('respaldosBtn').onclick = abrirRespaldos;
$('respCerrar').onclick = () => $('respaldosModal').classList.remove('show');
$('respAhora').onclick = async () => {
  $('respAhora').disabled = true;
  const res = await invCall('/api/respaldos', 'POST');
  $('respAhora').disabled = false;
  $('respErr').textContent = res.ok ? '' : res.reply;
  if (res.ok) avisar(res.reply);
  await refreshRespaldos();
};
$('respCarpetaForm').addEventListener('submit', async e => {
  e.preventDefault();
  const res = await invCall('/api/respaldos/carpeta', 'PUT', { carpeta: $('respCarpeta').value.trim() });
  $('respErr').textContent = res.ok ? '' : res.reply;
  if (res.ok) avisar(res.reply);
  await refreshRespaldos();
});
$('respLista').addEventListener('click', async e => {
  const b = e.target.closest('button[data-accion]'); if (!b) return;
  const r = respaldosLista[b.closest('tr').dataset.i]; if (!r) return;
  if (b.dataset.accion === 'descargar') {
    descargar('/api/respaldos/descargar?' + new URLSearchParams({ nombre: r.nombre }), 'jarvis-respaldo.db');
    return;
  }
  if (!await confirmar('Restaurar copia', `¿Volver el inventario a como estaba el ${fechaRespaldo(r)}? Se perderá lo registrado después `
      + '(el inventario de ahora quedará guardado como otra copia). Los demás usuarios tendrán que volver a entrar.', 'Restaurar')) return;
  const res = await invCall('/api/respaldos/restaurar', 'POST', { nombre: r.nombre });
  $('respErr').textContent = res.ok ? '' : res.reply;
  if (!res.ok) return;
  avisar(res.reply);
  await refreshRespaldos();
  await cargarSesion();
  await refreshSystem();
  pintarSaludo();
  refrescarDatos();
});
