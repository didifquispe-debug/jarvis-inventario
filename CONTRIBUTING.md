# Guía para el equipo

Cómo bajar JARVIS, hacerlo correr en tu computadora y subir tus cambios sin pisar el trabajo de los demás.

## 1. Antes de empezar (una sola vez)

1. **Acepta la invitación** al repositorio que te llegó por correo (o en https://github.com/notifications).
   Sin eso no podrás subir cambios.
2. Instala:
   - **Git**: https://git-scm.com/download/win
   - **JDK 17**: IntelliJ lo descarga en *File → Project Structure → SDK → Download JDK*.
   - **IntelliJ IDEA** (Community sirve). Trae Maven incluido.
3. Baja el proyecto:

   ```
   git clone https://github.com/didifquispe-debug/jarvis-inventario.git
   ```

4. En IntelliJ: *File → Open…* y elige la carpeta `jarvis-inventario` (la que tiene `pom.xml`).
   Espera a que descargue las dependencias.

La primera vez que hagas `git push`, Windows abrirá una ventana para iniciar sesión en GitHub. Acéptala.

## 2. Correr JARVIS

- Abre `src/main/java/com/jarvis/JarvisApplication.java` y pulsa ▶ junto a `main`.
- Entra a **http://localhost:8081**. La clave es la de `jarvis.token` en `src/main/resources/application.properties`.
- Pruebas: en IntelliJ, clic derecho en `src/test/java` → *Run 'All Tests'*
  (o `mvn test` si tienes Maven en la consola). **Antes de subir, las pruebas deben pasar.**

JARVIS guarda sus datos (base de datos, diccionario, log) en tu carpeta de usuario, no en el proyecto.
Cada uno tiene su propio inventario de prueba.

### Voz sin Internet (opcional)

Para que JARVIS entienda la voz sin Internet necesitas el modelo pequeño de español de Vosk:

1. Descarga `vosk-model-small-es-0.42.zip` desde https://alphacephei.com/vosk/models
2. Descomprímelo para que quede así: `C:\Users\<tu-usuario>\jarvis-voz\vosk\vosk-model-small-es-0.42\`
   (adentro deben estar las carpetas `am`, `conf`, `graph`…). Si lo pones en otro lugar, cambia
   `jarvis.voz.vosk-model` en `application.properties`.

Sin el modelo todo funciona igual. La voz usa la del navegador y la prueba de voz offline se salta sola.

Para que JARVIS **hable** sin Internet, descarga Piper para Windows (https://github.com/rhasspy/piper/releases) en
`C:\Users\<tu-usuario>\jarvis-voz\piper\` y la voz `es_ES-davefx-medium` (`.onnx` y `.onnx.json`) en
`...\piper\voces\`. Sin Piper habla con las voces de Windows. Las pruebas generan audio pero nunca lo reproducen.

### Asistente con IA (opcional)

Instala [Ollama](https://ollama.com) y ejecuta `ollama pull llama3.2:3b`. JARVIS lo usa en `http://localhost:11434/v1`,
sin internet. Sin Ollama todo funciona igual con las reglas. Las pruebas usan un servidor falso, nunca el real.

## 3. Cómo subir tus cambios

Nadie trabaja directo en `main`: cada cambio va en su propia rama y entra con un *pull request*.

1. Baja lo último:

   ```
   git checkout main
   git pull
   ```

2. Crea tu rama con un nombre que diga qué haces:

   ```
   git checkout -b reporte-ventas-mes
   ```

3. Programa, prueba y guarda tus cambios (puedes hacer varios commits):

   ```
   git add .
   git commit -m "Reporte de ventas del mes en Excel"
   ```

4. Sube tu rama:

   ```
   git push -u origin reporte-ventas-mes
   ```

5. En GitHub aparecerá **"Compare & pull request"**. Ábrelo, explica qué cambiaste y pide a un compañero que lo revise.
   Cuando esté aprobado, pulsa **"Merge pull request"**.
6. Vuelve a `main` y baja lo nuevo (paso 1) antes de empezar lo siguiente.

### Si GitHub dice que hay conflictos

Significa que otra persona cambió las mismas líneas. Trae lo último de `main` a tu rama y resuélvelo:

```
git checkout main
git pull
git checkout reporte-ventas-mes
git merge main
```

IntelliJ marca los archivos en conflicto (*Git → Resolve Conflicts*). Elige qué versión queda, guarda,
haz `git commit` y `git push`.

## 4. Reglas del proyecto

- Escribe en español: nombres de clases, métodos, mensajes y commits, como el código que ya existe.
- Los archivos usan finales de línea **LF**. Si Git avisa de `CRLF`, configura una vez:
  `git config --global core.autocrlf input`
- No subas archivos generados ni datos personales: `target/`, `dist/`, `*.log`, `.idea/`, bases de datos,
  claves. El `.gitignore` ya ignora la mayoría. Revisa con `git status` antes de `git add .`.
- Si cambias cómo funciona algo, actualiza el `README.md` y el manual (`src/main/resources/static/manual.html`).
