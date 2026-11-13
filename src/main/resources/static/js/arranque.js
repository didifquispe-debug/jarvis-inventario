// Proyecto DYSA - JARVIS Inventario. Arranque de la pantalla. Debe cargarse al final: usa todo lo anterior.

(async function arrancar() {
  getToken();
  await cargarSesion();
  cargarImpresion();
  setTimeout(revisarVersion, 20000);
  irA(location.hash.slice(1) || 'inicio');
  setState('idle');
  await refreshSystem();
  refrescarDatos();
  setInterval(refreshSystem, 5000);
  await iniciarHablaPc();
  iniciarMicPc();
  darBienvenida();
})();

async function darBienvenida() {
  setState('thinking');
  const b = await traer('/api/bienvenida');
  if (!b) { send('hola jarvis', true); return; }
  addEntry('bot', b.reply, true);
  if (puedeHablar() || !vozActiva) { speak(b.reply); return; }
  resumeIdle();
  const voz = fraseVoz;
  const alTocar = () => {
    removeEventListener('pointerdown', alTocar, true); removeEventListener('keydown', alTocar, true);
    setTimeout(() => { if (fraseVoz === voz) speak(b.reply); }, 0);
  };
  addEventListener('pointerdown', alTocar, true); addEventListener('keydown', alTocar, true);
}
