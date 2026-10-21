# J.A.R.V.I.S. en Java

Asistente personal con voz que corre en tu laptop y se controla desde **cualquier dispositivo**
(la misma laptop, tu celular, una tablet u otra PC) a través del navegador.

## Cómo funciona

```
 Celular / Tablet / PC  ──(Wi-Fi, navegador)──►  Laptop: servidor Spring Boot (puerto 8080)
   - micrófono y voz                                 ├─ JarvisService  (decide quién responde)
   - se instala como app                             ├─ Skills         (hora, web, apps, notas, cálculo…)
                                                     ├─ LaptopActions  (abre programas/navegador en la laptop)
                                                     └─ AiBrain        (IA opcional para conversar)
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
| Llegaron 20 bolsas de cemento a 28 soles | Entrada de mercadería (y precio) |
| Vendí 2 bolsas de cemento y 3 focos | Venta de uno o varios productos con un solo comprobante |
| Nueva venta → "2 bolsas de cemento", "3 focos", "quita los focos", "cuánto va" → Cobrar | Venta tipo tienda (carrito); "Cancelar venta" la anula |
| Lista de precios / ¿Cuánto cuesta el cable? / Cambia el precio del foco a 8.50 | Precios |
| ¿Cuántos focos quedan? / Inventario | Stock |
| Último comprobante / Cierre de caja | Comprobantes y reporte del día |

## 4b. Modo agente (la JARVIS que hace tareas sola)

Con el botón **🧠 AGENTE** (abajo a la derecha), JARVIS deja de solo responder y empieza a
**hacer tareas de varios pasos por su cuenta**, en segundo plano, mientras tú sigues en lo tuyo.

Tú le das una orden en lenguaje normal y él la divide en pasos, pensando y usando herramientas
reales de tu laptop:
- **ejecutar comandos** en la terminal,
- **crear, leer y listar archivos**,
- **abrir programas y páginas web**.

En el registro ves lo que piensa ("JARVIS PIENSA"), cada acción y su resultado, en vivo.

**Ejemplos de tareas:**
- "Crea una carpeta 'Informe' en el escritorio y dentro un archivo resumen.txt con un índice de 5 puntos."
- "Dime qué programas se abren al iniciar Windows."
- "Busca todos los PDF de la carpeta Descargas y haz una lista en un archivo."

### Seguridad (importante)
Darle a una IA acceso a tu sistema es potente y delicado, así que tiene frenos:
1. Antes de **ejecutar un comando** o **escribir un archivo**, te muestra un cuadro
   **AUTORIZAR / RECHAZAR**. Nada se ejecuta sin tu permiso.
2. Los comandos que **borran o dañan** datos (`del`, `rm`, `format`…) están **bloqueados** siempre.
3. Trabaja dentro de una carpeta propia (`C:\Users\<tú>\JARVIS`).

En `application.properties` puedes ajustar:
- `jarvis.agent.auto-approve=true` → hace todo sin pedir permiso (cómodo pero riesgoso).
- `jarvis.agent.allow-dangerous=true` → quita el freno a comandos destructivos (**no recomendado**).
- `jarvis.agent.workdir=...` → la carpeta donde trabaja.

El modo agente **necesita** la clave `ANTHROPIC_API_KEY` (ver la sección 5).

## 5. Darle inteligencia de verdad (opcional)

Sin IA, JARVIS solo entiende los comandos de arriba. Para que converse de cualquier tema:

1. Crea una clave en https://console.anthropic.com (la API tiene costo por uso).
2. En IntelliJ: *Run → Edit Configurations → JarvisApplication → Environment variables* y agrega
   `ANTHROPIC_API_KEY=tu-clave`.
3. Reinicia. Ahora lo que no sea un comando lo responde la IA, recordando las últimas frases.

## 6. Agregar tus propias habilidades

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
- Control de domótica (focos inteligentes) vía sus APIs HTTP.
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
   │  └─ ai/AiBrain.java             ← IA opcional
   └─ resources/
      ├─ application.properties      ← puerto, clave, configuración
      └─ static/                     ← interfaz web con voz (index.html) + app instalable
```

---

## Modo Inventario (asistente para negocios)

JARVIS también administra el inventario de un negocio (ferretería, bodega, minimarket) por voz
o texto, con respuestas cortas pensadas para escucharse en voz alta. Funciona **sin** clave de IA:
los comandos de inventario están integrados.

El stock se guarda en `C:\Users\<tú>\jarvis-inventario.json` y se ve en el panel **INVENTARIO**
de la pantalla (los productos con poco stock salen en ámbar con ⚠).

**Qué entiende:**
| Dices | JARVIS hace |
|---|---|
| "Llegaron 20 bolsas de cemento Yura" | Suma al stock: *"Entrada registrada: 20 bolsas de Cemento Yura 42.5kg. Stock actual: 70."* |
| "Vendí 5 metros de cable vulcanizado" | Resta del stock y valida: *"Salida registrada… Stock actual: 45."* |
| "¿Cuántos focos quedan?" | Consulta: *"Quedan 27 unidades de Foco LED 9W."* (avisa si hay stock bajo) |
| "Cierre de caja" | Resumen del día: ventas, total en soles y producto más vendido |
| "Inventario" | Lista todos los productos |

**Cosas inteligentes que ya trae:**
- **Búsqueda difusa:** "cemento" encuentra "Cemento Yura 42.5kg"; "clavos de dos" → "Clavo con cabeza 2 pulgadas".
- **Números en palabras:** entiende "veinte", "doscientos", etc.
- **Confirmación:** si la cantidad es mayor a 100 o el producto es ambiguo, pide un "sí/no".
- **Validación:** no deja vender más de lo que hay, y avisa cuando el stock baja del mínimo.
- **Productos nuevos:** si registras una entrada de algo que no existe, lo crea solo.

Viene con 5 productos de ejemplo. Para partir de cero, borra el archivo `jarvis-inventario.json`
(se vuelve a crear). Más adelante se puede añadir una pantalla para editar productos, precios y mínimos.

## Reconocimiento de intenciones local (offline, DJL)

Además de las reglas, JARVIS puede entender órdenes de inventario dichas **con sus propias palabras**
("me dejaron 25 bolsas de cemento", "se fueron 12 metros de cable") usando un modelo que corre
en la laptop con [Deep Java Library](https://djl.ai) + ONNX Runtime, sin Internet.

1. Crea la carpeta `C:\Users\<tú>\jarvis-models\intent\`.
2. Descarga de [paraphrase-multilingual-MiniLM-L12-v2](https://huggingface.co/sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2/tree/main)
   los archivos `onnx/model.onnx` y `tokenizer.json` y ponlos en esa carpeta (sin subcarpeta `onnx`).
3. Arranca JARVIS. En `jarvis.log` verás `Clasificador de intenciones listo`.

Intenciones: `REGISTRAR_ENTRADA`, `REGISTRAR_SALIDA`, `CONSULTAR_STOCK`, `REPORTE_DIARIO` y `DESCONOCIDO`.
Para enseñarle frases de tu negocio (ropa, minimarket…), añádelas en `src/main/resources/intents-es.json`;
no hay que entrenar nada. Si el modelo no está, la confianza es baja o falta la cantidad/producto,
JARVIS usa automáticamente las reglas de siempre. Ajustes en `application.properties` (`jarvis.intent.*`).
