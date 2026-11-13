# Plan: comprobantes electrónicos SUNAT

Estado: **propuesta, sin código todavía**. Escrito en noviembre de 2026. Las normas y precios cambian: confirmar
con un contador y en las fuentes del final antes de decidir.

## Dónde estamos

Hoy JARVIS genera la boleta (`B001`) o la nota de venta (`NV01`) con IGV, total en letras, ticket y PDF A4,
pero **no la envía a la SUNAT**. Es decir:

- La "Boleta de venta electrónica" que imprime JARVIS no es válida ante la SUNAT mientras no se envíe y acepte.
- La nota de venta es un documento interno: no figura entre los comprobantes de pago del Reglamento
  (RS 007-99/SUNAT). Sirve para control, no reemplaza a la boleta.
- Ya está resuelto: cada tienda tiene su propia serie (`B001`, `B002`…), JARVIS pide DNI desde S/ 700 y los
  números nunca se repiten. Las tres cosas son requisitos de la SUNAT.

## Opciones

| | A. Proveedor PSE/OSE por API (recomendada) | B. Enviar directo a la SUNAT | C. Portal SEE-SOL a mano |
|---|---|---|---|
| Qué hace JARVIS | Manda los datos de la venta (JSON) al proveedor, que firma, envía y devuelve el estado y el PDF | Arma el XML UBL 2.1, lo firma con certificado digital y lo envía por el servicio web de la SUNAT | Nada: alguien vuelve a escribir cada boleta en la web de la SUNAT |
| Costo | Nubefact: S/ 70 al mes o S/ 700 al año (con IGV), hasta 500 comprobantes al mes | Certificado digital (pago anual) y mucho trabajo de programación | Gratis |
| Trabajo en JARVIS | Medio: cliente HTTP, cola de envío y estados | Alto: UBL, firma XMLDSig, SOAP, CDR, resúmenes y bajas | Ninguno |
| Riesgo | Bajo: el proveedor sigue los cambios de la SUNAT | Alto: cada cambio de la norma lo tenemos que seguir nosotros | Errores de tipeo y doble trabajo |

Recomendación: **A**. El proveedor ya está homologado y absorbe los cambios de la norma. JARVIS solo le
manda datos que ya tiene. Nubefact es una opción; antes de elegir conviene comparar dos o tres proveedores
con el mismo volumen.

## Qué le falta a JARVIS (para la opción A)

1. **Datos del emisor**: RUC, razón social y dirección fiscal (ya existen en *Datos del negocio*; falta
   validarlos como obligatorios antes de emitir) y la dirección de cada tienda (cada local es un establecimiento).
2. **Códigos SUNAT** en cada línea:
   - unidad de medida (catálogo 03: `NIU` unidades, `KGM` kilos, `MTR` metros…), a partir de la unidad del producto;
   - tipo de afectación del IGV (catálogo 07: `10` gravado, `20` exonerado), a partir del modo de IGV del comprobante;
   - tipo de documento del cliente (catálogo 06: `1` DNI, `6` RUC, `-` sin documento).
3. **Cola de envío**: JARVIS funciona sin Internet, así que cada boleta se guarda como *pendiente* y se envía
   cuando haya conexión. Hay que guardar el estado (pendiente, aceptada, rechazada con motivo) y avisar en
   pantalla de lo rechazado. La SUNAT da un plazo de días, no de minutos (ver fuentes), así que se puede
   reintentar sin apuro.
4. **Anular de verdad**: hoy "anular la última venta" solo la marca en la base. Para una boleta aceptada hay que
   mandar una comunicación de baja o emitir una nota de crédito (serie `BC01`, `BC02`…). La nota de venta
   sigue anulándose como hasta ahora.
5. **Facturas** (serie `F001`…) para clientes con RUC que las pidan. Hoy JARVIS solo emite boletas y notas.
6. **Clave del proveedor** guardada fuera del código, como `jarvis.token` (archivo en la carpeta del usuario).
   Nunca en `application.properties` ni en GitHub.

## Pasos sugeridos

1. **Con el contador** (sin programar): confirmar el régimen de la empresa, si ya es emisor electrónico y qué
   comprobantes debe emitir. Darse de alta como emisor electrónico si hace falta.
2. **Elegir proveedor** y crear la cuenta **de pruebas** (los proveedores tienen ambiente demo).
3. **Etapa 1 – boletas**: puntos 1, 2, 3 y 6. Enviar al ambiente de pruebas y mostrar el estado en
   *Comprobantes*. Tests con un servidor falso, como ya se hace con Ollama.
4. **Etapa 2 – anulaciones**: punto 4.
5. **Etapa 3 – facturas**: punto 5.
6. Pasar a producción con una sola tienda; luego el resto.

Mientras no esté la etapa 1, conviene cambiar el texto impreso "Boleta de venta electrónica" por algo que no
dé a entender que ya fue informada a la SUNAT. Es una decisión del equipo.

## Fuentes

- SUNAT, orientación: [Resumen diario – Boleta de venta electrónica](https://orientacion.sunat.gob.pe/04-resumen-diario-boleta-de-venta-electronica) (plazo hasta el séptimo día calendario siguiente).
- Sovos: [Impacto de la RS N.° 000048-2026/SUNAT en la boleta de venta electrónica](https://sovos.com/es/cambios-regulatorios/iva/peru-boleta-venta-electronica-rs-000048-2026-sunat/) (cambios de plazos postergados al 1 de enero de 2027 por la RS 000143-2026/SUNAT).
- Nubefact: [Precios](https://www.nubefact.com/precios) y [OSE vs PSE](https://www.nubefact.com/blog/facturacion-electronica/ose-vs-pse-cuadro-comparativo-para-entender-sus-diferencias-en-facturacion-electronica).
- SUNAT: [Nuevo RUS (guía)](https://orientacion.sunat.gob.pe/sites/default/files/inline-files/Nuevo_rus_carpeta_V2.pdf) (qué comprobantes puede emitir cada régimen).
