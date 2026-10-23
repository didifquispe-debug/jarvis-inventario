# J.A.R.V.I.S. en Java

Asistente con voz e **inventario para cualquier negocio** (tienda de ropa, zapatería, bodega,
ferretería…) que corre en tu laptop. Funciona **100% offline, sin claves de API**, y se controla desde **cualquier dispositivo**
(la misma laptop, tu celular, una tablet u otra PC) a través del navegador.

## Cómo funciona

```
 Celular / Tablet / PC  ──(Wi-Fi, navegador)──►  Laptop: servidor Spring Boot (puerto 8080)
   - micrófono y voz                                 ├─ JarvisService  (decide quién responde)
   - se instala como app                             ├─ Skills         (hora, web, apps, notas, cálculo…)
                                                     ├─ LaptopActions  (abre programas/navegador en la laptop)
                                                     └─ AiBrain        (entiende órdenes, modelo local)
```

El cerebro vive en la laptop. Los demás dispositivos solo hablan con él por la API
`POST /api/command`, por eso cualquier cosa con navegador es compatible.
Si le dices desde el celular "abre YouTube", **se abre en la laptop**.

## 1. Requisitos

- **JDK 17 o superior** (IntelliJ lo puede descargar: *File → Project Structure → SDK → Download JDK*).
- **IntelliJ IDEA** (Community sirve).

## 2. Abrir en IntelliJ

1. Descomprime el ZIP.
2. *File → Open…* y elige la carpeta `jarvis` (la que tiene `pom.xml`). Ábrelo como proyecto Maven.
3. Espera a que descargue las dependencias (barra de abajo).
4. Abre `src/main/resources/application.properties` y **cambia `jarvis.token`** por una clave tuya.
5. Abre `JarvisApplication.java` y pulsa ▶ junto a `main`.

En tu laptop entra a **http://localhost:8080** — te pedirá la clave una sola vez.

## La pantalla

JARVIS se ve como un sistema de gestión: **menú lateral** (Inicio, Vender, Inventario, Clientes, Comprobantes) y
**JARVIS como asistente** en un panel a la derecha, con su orbe animado, chat, micrófono y manos libres.
- **Inicio:** ventas de hoy, productos con stock bajo, valor del inventario (a precio de venta y al costo), últimas
  ventas y lo que hay que reponer.
- **Vender:** busque o escanee el producto (Enter agrega), elija boleta o nota de venta, el DNI del cliente
  (si no existe, se registra desde ahí) y **Cobrar**: sale el comprobante con su PDF.
- **Inventario, Clientes y Comprobantes:** tablas con buscador y filtros; toque un comprobante para verlo.
- **Modo claro u oscuro:** sigue a Windows; se cambia en el menú. Los medidores del equipo (CPU, RAM, disco,
  batería y la dirección para el celular) están en **Equipo**, al pie del menú.
- En una ventana angosta el asistente se abre con el botón redondo de JARVIS; en el celular el menú pasa arriba.
- Funciona sin Internet: usa las fuentes del sistema.

## 2b. Programa de escritorio (JARVIS.exe)

Al darle ▶, JARVIS ya funciona como programa de escritorio:
- Se abre en **su propia ventana**, sin pestañas ni barra de direcciones. Usa el modo aplicación de Edge o Chrome, así que la voz y el micrófono funcionan.
- Aparece un **ícono junto al reloj** de Windows. Doble clic abre la ventana. Clic derecho → *Salir* lo apaga.
- Cerrar la ventana **no** apaga a JARVIS: sigue atendiendo al celular.
- Si lo abres dos veces, no falla: solo vuelve a mostrar la ventana.

Para crear el **.exe**:
1. Detén JARVIS en IntelliJ (■).
2. Abre el panel **Maven** (a la derecha) → `jarvis` → `Lifecycle` → doble clic en **package**.
   Así se crea `target/jarvis-0.1.0.jar`.
3. Doble clic en **`crear-exe.bat`**, que está en la carpeta del proyecto.
4. Se abre `dist\JARVIS\` con **JARVIS.exe** adentro. Esa carpeta incluye su propio Java,
   así que puedes copiarla a otra PC con Windows y funciona sin instalar nada.
5. Clic derecho en `JARVIS.exe` → *Enviar a → Escritorio (crear acceso directo)*.

**Que arranque solo al prender la laptop:** presiona `Win + R`, escribe `shell:startup` y
pega ahí el acceso directo de JARVIS.

Si cambias algo del código o de `application.properties`, repite los pasos 2 y 3.
Como el programa no tiene consola, sus mensajes quedan en `C:\Users\<tú>\jarvis.log`.

## 3. Usarlo desde el celular u otros dispositivos

1. Conecta el celular a **la misma red Wi-Fi** que la laptop.
2. Averigua la IP de la laptop:
   - Windows: `ipconfig` → "Dirección IPv4" (ej. `192.168.1.35`)
   - macOS/Linux: `ip a` o `ifconfig`
3. En el celular abre `http://192.168.1.35:8080`.
4. Si Windows pregunta por el Firewall al arrancar, permite **redes privadas**.
5. Para tenerlo como app: en Chrome ⋮ → *Agregar a la pantalla principal*.

### Micrófono en el celular (importante)
Los navegadores solo dejan usar el micrófono en `localhost` o con **HTTPS**.
Desde el celular por `http://192.168…` podrás **escribir** pero no hablar. Soluciones:

- **Rápida (Chrome Android):** abre `chrome://flags/#unsafely-treat-insecure-origin-as-secure`,
  agrega `http://192.168.1.35:8080`, actívalo y reinicia Chrome.
- **Desde cualquier lugar (incluso fuera de casa):** instala [Tailscale](https://tailscale.com) en laptop y
  celular, o usa un túnel HTTPS como `ngrok http 8080` / Cloudflare Tunnel.
  Si lo expones a Internet, usa una clave larga en `jarvis.token`.

## 4. Comandos incluidos

**Manual completo de voz:** botón **📖 MANUAL** en la pantalla (o `http://localhost:8081/manual.html`); se puede imprimir.

Di o escribe **"ayuda"**. Algunos ejemplos:

| Orden | Qué hace |
|---|---|
| Hola JARVIS | Saludo según la hora |
| ¿Qué hora es? / ¿Qué día es hoy? | Hora y fecha |
| Abre YouTube / Gmail / WhatsApp / GitHub / Spotify | Abre el sitio en la laptop |
| Busca recetas de quinua | Búsqueda en Google |
| Pon música de Queen en YouTube | Busca en YouTube |
| Abre la calculadora / bloc de notas / explorador / IntelliJ | Abre programas (Windows, Mac o Linux) |
| Anota comprar leche / Lee mis notas / Borra mis notas | Notas guardadas en `~/jarvis-notas.txt` |
| Cuánto es 25 por 4 más 10 | Calculadora |
| Estado del sistema | Diagnóstico de la laptop |
| Agrega 10 poleras talla M a 25 soles | Entrada de mercadería (y precio) |
| Vendí 2 poleras M y 1 zapatilla 40 | Venta de uno o varios productos con un solo comprobante |
| Nueva venta → "2 poleras M", "1 gorra negra", "quita la gorra", "cuánto va" → Cobrar | Venta tipo tienda (carrito); "Cancelar venta" la anula |
| Lista de precios / ¿Cuánto cuesta la polera M? / Cambia el precio de la gorra a 18.50 | Precios |
| ¿Cuántas tengo de zapatilla 40? / Inventario | Stock |
| Último comprobante / Cierre de caja | Comprobantes y reporte del día |

Si JARVIS no entiende una orden responde: *"No entendí la orden. Diga 'ayuda' para ver lo que puedo hacer."*

## 5. Agregar tus propias habilidades

Crea una clase en `com.jarvis.skills`:

```java
@Component
@Order(10)
public class ChisteSkill implements Skill {
    public String name() { return "Chistes"; }
    public String example() { return "Cuéntame un chiste"; }
    public Optional<String> handle(String cmd, String original) {
        if (cmd.contains("chiste")) return Optional.of("¿Por qué el libro de matemáticas está triste? Porque tiene muchos problemas.");
        return Optional.empty();
    }
}
```

Reinicia y ya está: aparece sola en "ayuda". `cmd` llega en minúsculas, sin tildes y sin la palabra "jarvis".
Las acciones que tocan el sistema operativo van en `LaptopActions` (ahí puedes añadir más programas al mapa `APPS`).

## Ideas para seguir
- Palabra de activación ("Jarvis") escuchando todo el tiempo en la laptop con Vosk (reconocimiento offline en Java).
- Clima con la API gratuita de Open-Meteo.
- Recordatorios con `@Scheduled` de Spring.
- Control de domótica (luces inteligentes) vía sus APIs HTTP.
- Una app Android nativa que llame a la misma API `/api/command`.

## Estructura

```
jarvis/
├─ pom.xml
└─ src/main/
   ├─ java/com/jarvis/
   │  ├─ JarvisApplication.java      ← arranque
   │  ├─ api/JarvisController.java   ← API REST para todos los dispositivos
   │  ├─ core/JarvisService.java     ← núcleo que enruta los comandos
   │  ├─ skills/…                    ← cada habilidad en su clase
   │  ├─ system/LaptopActions.java   ← acciones sobre Windows/Mac/Linux
   │  ├─ api/InventoryController.java ← API del inventario (alta, edición, ajuste, borrado)
   │  ├─ inventory/…                 ← inventario, búsqueda, ventas e historial
   │  └─ ai/…                        ← entiende órdenes con el modelo local (sin Internet)
   └─ resources/
      ├─ application.properties      ← puerto, clave, configuración
      └─ static/                     ← interfaz web con voz (index.html) + app instalable
```

---

## Modo Inventario (asistente para negocios)

JARVIS administra el inventario de cualquier negocio (tienda de ropa, zapatería, bodega,
ferretería…) por **pantalla**, voz o texto, con respuestas cortas pensadas para escucharse en voz alta.
Todo funciona sin Internet y sin claves de API.

### Dónde se guarda (base de datos)
Todo se guarda en una base de datos SQLite, un solo archivo: `C:\Users\<tú>\jarvis-inventario.db`. No hay que
instalar nada.
- **Cada operación se guarda entera o no se guarda:** un corte de luz a mitad de una venta nunca deja el stock
  descontado sin su comprobante. Si algo no se pudo guardar, JARVIS lo dice y vuelve a lo que estaba guardado.
- **Respaldo diario automático:** `jarvis-inventario.db.respaldo-AAAAMMDD`, junto a la base; se conservan los
  últimos 7. Si al arrancar la base está dañada, JARVIS la aparta (`.danado-<fecha>`) y vuelve al último respaldo.
- **Historial:** cada cambio (ventas, entradas, ajustes, precios, altas, bajas) queda en la base con fecha y
  dispositivo. Se consulta con `GET /api/inventory/movimientos`.
- **Si venía de una versión anterior** (inventario en `jarvis-inventario.json`): la primera vez se importa todo
  solo, incluidas las ventas archivadas y el historial de la carpeta `jarvis-inventario-historial`. El JSON queda
  renombrado como `jarvis-inventario.json.migrado` y no se borra nada.
- Para copiar el inventario a otra PC, copie `jarvis-inventario.db` con JARVIS apagado.

### Comprobantes: boleta y nota de venta, con IGV
Cada venta emite un comprobante con su **serie y correlativo**: boleta `B001-00000001` o nota de venta
`NV01-00000001`. Cada serie lleva su propia numeración, que nunca se repite (ni al empezar un inventario nuevo).
- **Por defecto, boleta.** Se cambia en `application.properties` (`jarvis.comprobante.tipo=nota de venta`) o al vender:
  *"vendí 2 poleras M **con nota de venta**"*, *"nueva venta **con boleta**"*.
- **IGV 18%** (`jarvis.igv`): `incluido` (los precios ya lo tienen: el total no cambia y el comprobante muestra
  base e IGV por separado), `agregado` (se suma al precio) o `exonerado`. Las ventas hechas antes de esta versión
  quedan como notas de venta sin IGV (no se sabe si el negocio lo cobraba; no se inventan impuestos).
- **Cliente:** sin cliente, el comprobante dice *CLIENTES VARIOS*. Con un cliente registrado:
  *"vendí 2 poleras M con boleta **para el DNI 12345678**"*, o en una venta con carrito *"cliente DNI 12345678"*
  (también hay un selector de comprobante y un campo de DNI en el panel de la venta en curso). Si una boleta llega a
  700 soles o más sin cliente, JARVIS avisa: la SUNAT pide identificar al cliente desde ese monto.
- **PDF:** en la ventana del comprobante, el botón **PDF** abre la representación impresa en A4 (con el importe en
  letras y la marca *ANULADO* si se anuló); **IMPRIMIR** sigue sacando el ticket de 80 mm. Los datos del negocio
  salen de `jarvis.negocio`, `.ruc`, `.direccion`, `.telefono` y `.email`.
- Nota: JARVIS no envía los comprobantes a la SUNAT; son la representación interna del negocio.

### Compras y proveedores
En **Compras → Nueva compra** se registra la mercadería que llega: RUC del proveedor (si es nuevo, se registra
solo con su razón social), documento (factura, boleta, guía de remisión o nota de venta), serie y número, fecha
(no puede ser futura), IGV (los costos lo incluyen, se suma o exonerado) y los productos con su cantidad y costo.
- **Suma el stock** y **recalcula el costo promedio ponderado** de cada producto, sin IGV (el IGV de la compra es
  crédito fiscal): `(stock × costo actual + cantidad × costo nuevo) / (stock + cantidad)`. Ese costo es el que queda
  guardado en cada venta, así la ganancia sale de compras reales.
- **No se registra dos veces el mismo documento** del mismo proveedor (salvo que la primera se haya anulado).
- **Anular una compra** retira del stock lo que ingresó con ella, solo si esa mercadería no se vendió todavía.
  El costo promedio no se recalcula al anular (igual que en el sistema de referencia).
- Todo se guarda en una sola operación: la compra, el stock y el costo, o nada.
- Un proveedor con compras no se puede eliminar. API: `/api/proveedores` y `/api/compras` (ver `ComprasController.java`).

### Inventario valorizado, kardex y Excel
- **Inventario valorizado:** en *Inventario* se ve el valor al costo promedio, el valor a precio de venta y el margen
  (solo de los productos con costo registrado), y cada producto muestra su valor al costo.
- **Kardex:** el botón de kardex de cada producto muestra cada entrada y salida (alta, compra, venta, anulación,
  corrección, ajuste, baja) con el **saldo** que quedó y el costo promedio de ese momento. Los movimientos registrados
  antes de esta versión salen con su entrada o salida pero sin saldo (no se guardaba y no se inventa).
- **Exportar Excel** (con filtros y totales que respetan el filtro):
  - *Inventario:* inventario valorizado con resumen, y el kardex de todos los productos.
  - *Kardex:* el de un producto, desde su ventana.
  - *Comprobantes:* las ventas del periodo elegido y su detalle con el **costo y la ganancia de cada línea**,
    calculada sin IGV (venta sin IGV − costo). Los anulados figuran con importe 0.
  - *Compras:* las compras del periodo (y del filtro) con su detalle.
- API: `/api/inventory/valorizado`, `/api/inventory/kardex?nombre=…` y `/api/reportes/*.xlsx` (ver `ReportesController.java`).

### Clientes
Se registran por la API (`/api/clientes`, ver abajo): DNI de 8 dígitos o RUC de 11 (que empiece con 10, 15, 17 o
20), nombre, y opcionalmente correo, teléfono (6 a 9 dígitos) y dirección. No se repite el documento, y un cliente
con comprobantes no se puede eliminar. El comprobante guarda el nombre con el que se emitió.

### Arranca vacío
La primera vez el inventario **no trae ningún producto** y el panel dice *"Aún no tienes productos. Agrega el
primero."* Para empezar de cero más adelante, diga *"nuevo inventario"* (guarda una copia antes).

### Agregar y editar productos por pantalla
En el panel **INVENTARIO** (a la izquierda; en el celular, debajo del reactor):
- **＋ Agregar**: abre un formulario con *nombre*, *precio*, *stock inicial*, *unidad* (unidades, pares,
  metros, kilos…), *stock mínimo*, *costo*, *categoría*, *código / SKU*, *código de barras* y *otros nombres*
  (cómo lo piden los clientes). Nombre, precio y unidad son obligatorios; no acepta precios, costos ni stock
  negativos, ni dos productos con el mismo nombre (da igual mayúsculas o tildes: "Pantalón" y "pantalon" son
  el mismo) ni con el mismo código.
- **Costo y ganancia:** al escribir precio y costo, el formulario muestra la ganancia por unidad, y avisa en rojo
  si vende por debajo del costo (se permite, por ejemplo en una liquidación). Cada venta guarda el costo que tenía
  el producto ese día, así un cambio de costo posterior no altera la ganancia de ventas pasadas.
- **Códigos:** el código interno (SKU) y el código de barras se guardan como texto (no pierden ceros a la izquierda).
  En el campo *Código de barras* puede usar un **lector de código de barras** (de los que se conectan por USB y
  "escriben" el código). En el buscador del inventario, el código exacto muestra ese producto. En las órdenes
  también sirve: *"vendí 2 7750182000123"* o *"¿cuántas tengo de ZAP-NK-40?"*. Los números de hasta 5 cifras se
  leen siempre como cantidad o talla, no como código.
- **Categorías:** cuando hay productos con categoría aparece un filtro *Todas las categorías* sobre la lista; el
  formulario sugiere las que ya existen.
- **✎ Editar**: cambia cualquiera de esos datos. Deje vacío un campo opcional para borrarlo.
- **－ / ＋**: ajuste rápido de stock de 1 en 1 (no es una venta: no emite comprobante; queda como AJUSTE en el historial).
- **🗑 Eliminar**: pide confirmación. Las ventas pasadas se conservan.
- **⬆ Cargar lista**: registra muchos productos de una vez (ver abajo).
- Con 6 o más productos aparece un **buscador** (por nombre, categoría o código). Los productos con poco stock salen en ámbar con ⚠.

### Cargar muchos productos de una vez (⬆ Cargar lista)
Toque **⬆ Cargar lista**, pegue su lista (o elija un archivo `.csv` / `.txt`) y toque **CARGAR**.
Una línea por producto, en este orden:

```
Nombre, Precio, Stock, Unidad, Stock mínimo, Costo, Categoría, Código, Código de barras
```

Las cinco primeras columnas son las de siempre; las cuatro últimas son opcionales (las listas viejas siguen sirviendo).

```
# mercadería de octubre
Polo blanco M, 25, 10, unidades
Zapatilla Nike 40; 180; 5; pares; 2
Tela drill; 12,50; 30; metros
Vela, 2
Zapatilla Nike 41; 180; 4; pares; 2; 110; Calzado; ZAP-NK-41; 7750182000123
```

- **Separador:** coma o punto y coma. También puede copiar las columnas directamente desde Excel (van con tabulador).
  Con coma como separador, los decimales van con punto (`12.50`); con punto y coma o desde Excel, puede usar `12,50`.
  Si un nombre lleva coma, escríbalo entre comillas: `"Polo, rayas", 25, 3`.
- **Obligatorios:** nombre y precio. Sin unidad se usa `unidades`; sin stock, `0`; el resto es opcional.
  Para dejar vacía una columna del medio, deje el espacio entre separadores: `Vela; 2; 10; unidades; ; ; Velas`.
- **Códigos repetidos:** un código que ya es de otro producto (o que ya vino en otra línea de la lista) es un error.
- **Desde Excel:** dé formato de *Texto* a la columna del código de barras antes de guardar como CSV; si no, Excel
  puede escribirlo como `7.75018E+12` y se pierde.
- Se ignoran las líneas vacías, las que empiezan con `#` y una primera fila de títulos ("Nombre, Precio, …").
- **No pisa nada:** si el producto ya existe (sin importar mayúsculas ni tildes) se **omite**; si un nombre se
  repite dentro de la lista, se registra el primero y se omiten los demás.
- Las líneas con errores (precio vacío o que no es número, negativos, decimales en un producto por unidades…)
  **no se registran**; el resto sí. Al terminar verá cuántos se agregaron, cuántos se omitieron y cada error con su
  número de línea. Corrija esas líneas y vuelva a tocar CARGAR: los que ya se agregaron se omiten solos.
- Todo se guarda de una vez, en una sola operación, y cada alta queda en el historial.
- Archivos de Excel: use *Guardar como → CSV*. Se aceptan tanto UTF-8 como el "ANSI" de Windows (tildes incluidas).

#### Dictar la lista con el micrófono (🎙 DICTAR)
Dentro de *Cargar lista*, toque **🎙 DICTAR** y diga **un producto por frase** (haga una pausa entre producto y producto).
Cada frase se **escribe** como una línea nueva en el cuadro; **no se registra nada** hasta que usted toque **CARGAR**,
así que puede seguir dictando, corregir a mano o borrar líneas. Toque **■ PARAR** para dejar de escuchar.

| Usted dice | Se escribe |
|---|---|
| "polo blanco M **a** 25 **soles** 10 unidades" | `Polo blanco M; 25; 10; unidades` |
| "zapatilla nike 40 **a** 180 **soles** 5 pares **mínimo** 2" | `Zapatilla nike 40; 180; 5; pares; 2` |
| "polo blanco M **coma** 25 **coma** 10 **coma** unidades" | `Polo blanco M; 25; 10; unidades` |
| "tela drill a 12 **con** 50 30 metros" | `Tela drill; 12,50; 30; metros` |
| "chompa de lana" (sin precio) | `Chompa de lana` → complete el precio a mano antes de CARGAR |

- Los campos se separan diciendo **"coma"** o **"punto y coma"**, o con las palabras clave **a / precio** (antes del precio),
  **soles** (después del precio) y **mínimo** (antes del stock mínimo). Sin ninguna de ellas, el nombre llega hasta el primer número.
- Los números pequeños dichos en palabras ("diez") se escriben con cifras.
- Mientras dicta, el micrófono de *Manos libres* se pausa y vuelve solo al terminar.
- **Internet:** el reconocimiento de voz lo hace el navegador (Chrome/Edge). Si el navegador tiene instalado el
  reconocimiento de español *en el equipo*, la ventana dice **"🔒 Dictado en este equipo"** y no se usa la red.
  Si no, avisa **"⚠ … necesita Internet"**: en ese caso el audio lo procesa el servicio de voz del navegador
  (lo mismo que ya pasa con el botón 🎙 HABLAR). Sin conexión, escriba o pegue la lista: la carga en sí siempre es local.
- En el celular, el micrófono requiere HTTPS (vea *Micrófono en el celular*).

### Ropa y calzado (tallas y colores)
Registra cada talla/color como un producto con nombre compuesto: `Zapatilla Nike Air 39`,
`Zapatilla Nike Air 40`, `Polera roja M`, `Polera azul M`…
- La talla y el color **deciden**: "vendí 2 zapatillas nike air 40" nunca descuenta la 39, y
  "polera verde M" no vende la roja.
- Si falta el dato ("vendí 1 zapatilla nike air"), JARVIS **pregunta cuál** y no vende nada:
  *"Tengo varios parecidos: Zapatilla Nike Air 39 y Zapatilla Nike Air 40. ¿Cuál de ellos?"*
- "¿Cuántas zapatillas nike quedan?" lista el stock de cada talla.

### Diccionario: enséñele sus palabras
Para que entienda las palabras de su negocio ("chompa" = polera, "zapas" = zapatillas):
- Por voz: *"Jarvis, aprende que chompa significa polera"*, *"¿qué palabras aprendiste?"*, *"olvida la palabra chompa"*.
- O edite `C:\Users\<tú>\jarvis-diccionario.txt` con el Bloc de notas (una línea `chompa = polera` por palabra);
  se vuelve a leer solo al guardar. Lo que trae de fábrica está en `src/main/resources/diccionario-es.txt`.

### Eliminar por voz
*"Elimina la polera roja M"*, *"borra la gorra negra"* o *"da de baja la casaca"*: pregunta y solo borra si dice **sí**.

### Nombre del negocio
En `application.properties` ponga `jarvis.negocio=` con el nombre de su negocio (escriba la ñ como
`\u00f1`, la í como `\u00ed`…). Sale en el saludo (*"…Bienvenido a Zapatería Mi Paso."*), en el título
de la pantalla y en el comprobante. Vacío = no se muestra.

### Qué entiende por voz
| Dices | JARVIS hace |
|---|---|
| "Agrega 10 poleras talla M a 25 soles" | Suma al stock (si no existe, crea el producto) |
| "Vendí 2 poleras M" | Resta del stock, valida y emite comprobante |
| "¿Cuántas tengo de zapatilla 40?" | Consulta (avisa si hay stock bajo) |
| "Cierre de caja" | Resumen del día: ventas, total en soles y producto más vendido |
| "Inventario" | Lista todos los productos |

- **Búsqueda difusa:** "zapatillas nike 40" encuentra "Zapatilla Nike Air 40".
- **Números en palabras:** entiende "veinte", "doscientos", "dos y medio", etc., y también "una docena" (12),
  "media docena" (6), "un par" (2), "dos pares" (4), "medio ciento" (50) y "un millar" (1000). Si el producto
  ya se cuenta en *pares* (zapatillas), "un par" es 1 par.
- **Confirmación:** si la cantidad es mayor a 100 o el producto es dudoso, pide un "sí/no".
- **Validación:** no deja vender más de lo que hay y avisa cuando el stock baja del mínimo.

### API del inventario (para otros programas)
Todas piden la cabecera `X-Jarvis-Token` y responden `{ ok, reply }` (400 si falta algo, 404 si no existe):
`POST /api/inventory/productos` (crear), `PATCH /api/inventory/productos` (editar),
`POST /api/inventory/productos/ajuste` (`{"nombre": "...", "cantidad": -1}`),
`DELETE /api/inventory/productos?nombre=...`. Detalles en `InventoryController.java`.
Al crear o editar también se aceptan `categoria`, `codigo`, `codigoBarras` y `costo`; al editar, un texto `""`
borra ese dato y `"quitarCosto": true` borra el costo.

Ventas y comprobantes: `POST /api/inventory/ventas` con
`{"items": [{"nombre": "Polera roja M", "cantidad": 2}], "tipo": "boleta", "cliente": "12345678", "igv": "incluido"}`
(solo `items` es obligatorio; nombres exactos), `GET /api/inventory/comprobantes/{numero}` y
`GET /api/inventory/comprobantes/{numero}/pdf`, y `PUT /api/inventory/venta/opciones` (`{"tipo", "cliente"}`) para la
venta en curso. Clientes: `GET /api/clientes?q=…`, `GET/PATCH/DELETE /api/clientes/{documento}`, `POST /api/clientes`
(`{"documento", "nombre", "email", "telefono", "direccion"}`).

Alta masiva: `POST /api/inventory/productos/lote`, con un arreglo JSON
(`Content-Type: application/json`, `[{"nombre": "Polo blanco M", "precio": 25, "stock": 10, "unidad": "unidades", "stockMin": 2}, …]`)
o con la lista en texto (`Content-Type: text/plain`, el mismo formato de *Cargar lista*). Responde
`{ ok, reply, agregados, omitidos, omitidosDetalle: [{linea, motivo}], errores: [{linea, motivo}] }`
(400 si la lista está vacía o tiene más de 5000 productos).

## Asistente con IA (entiende lo que le diga)

Con una clave de Anthropic e internet, JARVIS entiende frases libres y las ejecuta, como un asistente:

| Usted dice | JARVIS hace |
|---|---|
| "Vamos a hacer un nuevo inventario" | Pregunta si confirma; con un sí guarda una copia y empieza vacío |
| "Sube 10% el precio de todo el calzado" | Revisa los productos de Calzado, cambia cada precio y resume |
| "Registra unas zapatillas Adidas 41 a 220, me llegaron 6 pares" | Crea el producto con sus datos |
| "¿Qué me conviene reponer?" | Revisa el stock bajo y responde |
| "Despacha dos poleras rojas talla M" | Hace la venta con su comprobante |

**Cómo activarlo:**
1. Cree una clave en [console.anthropic.com](https://console.anthropic.com) (*API Keys*). Se paga por uso.
2. Abra el Bloc de notas, pegue la clave en la primera línea y guárdelo como `C:\Users\<tú>\jarvis-ia-clave.txt`.
   (También sirve la variable de entorno `ANTHROPIC_API_KEY`.) No la escriba en `application.properties` ni la comparta.
3. Reinicie JARVIS. En `jarvis.log` verá `Asistente con IA listo`.

**Modo mixto:** sin clave, sin internet o si la IA falla, JARVIS responde con sus órdenes de siempre, sin que
usted haga nada (tras un corte de internet espera un minuto antes de volver a intentar con la IA). Si la clave es
rechazada, el log lo dice y se usan las reglas hasta reiniciar.

**Seguridad:** la IA usa las mismas funciones que la pantalla, así que valen las mismas reglas (no vender más de lo
que hay, códigos únicos…). **Eliminar un producto y empezar un inventario nuevo siempre piden su "sí"**, y esa
regla está en el programa, no en la IA. Los "sí/no" de las órdenes de siempre y los productos de una venta con
carrito van directo a las reglas, para que la caja sea rápida.

Ajustes en `application.properties`: `jarvis.ia.enabled` (false = nunca usar IA), `jarvis.ia.modelo`,
`jarvis.ia.clave-archivo` y `jarvis.ia.fallback-servidor` (si el modelo declina una petición por sus filtros de
seguridad, la API la reintenta con otro modelo).

**Sin IA también puede empezar de cero:** diga *"nuevo inventario"*, *"empezar el inventario de cero"* o
*"vaciar el inventario"*; pregunta y solo vacía con un sí. La copia queda junto al inventario, como
`jarvis-inventario.db.antes-de-nuevo-<fecha>`. Los números de comprobante no se reinician.

## Reconocimiento de intenciones local (offline, DJL)

Además de las reglas, JARVIS puede entender órdenes de inventario dichas **con sus propias palabras**
("me dejaron 25 pares de zapatillas", "se fueron 12 metros de tela") usando un modelo que corre
en la laptop con [Deep Java Library](https://djl.ai) + ONNX Runtime, sin Internet.

1. Crea la carpeta `C:\Users\<tú>\jarvis-models\intent\`.
2. Descarga de [paraphrase-multilingual-MiniLM-L12-v2](https://huggingface.co/sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2/tree/main)
   los archivos `onnx/model.onnx` y `tokenizer.json` y ponlos en esa carpeta (sin subcarpeta `onnx`).
3. Arranca JARVIS. En `jarvis.log` verás `Clasificador de intenciones listo`.

Intenciones: `REGISTRAR_ENTRADA`, `REGISTRAR_SALIDA`, `CONSULTAR_STOCK`, `REPORTE_DIARIO` y `DESCONOCIDO`.
Para enseñarle frases de tu negocio (ropa, minimarket…), añádelas en `src/main/resources/intents-es.json`;
no hay que entrenar nada. Si el modelo no está, la confianza es baja o falta la cantidad/producto,
JARVIS usa automáticamente las reglas de siempre. Ajustes en `application.properties` (`jarvis.intent.*`).
