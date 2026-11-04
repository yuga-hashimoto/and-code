# AndCode

<p align="center">
  <a href="https://github.com/yuga-hashimoto/and-code/actions/workflows/android.yml"><img src="https://github.com/yuga-hashimoto/and-code/actions/workflows/android.yml/badge.svg" alt="CI" /></a>
  <a href="https://github.com/yuga-hashimoto/and-code/releases/latest"><img src="https://img.shields.io/github/v/release/yuga-hashimoto/and-code" alt="Release" /></a>
  <a href="LICENSE"><img src="https://img.shields.io/github/license/yuga-hashimoto/and-code" alt="License: MIT" /></a>
  <a href="https://github.com/yuga-hashimoto/and-code/releases/latest"><img src="https://img.shields.io/github/downloads/yuga-hashimoto/and-code/total" alt="Downloads" /></a>
</p>

**Ejecuta agentes de programación de IA localmente en Android con una GUI nativa — sin terminal.**

AndCode es una app Android nativa con GUI que lleva los agentes de programación de IA a tu teléfono. Chatea con [OpenCode](https://github.com/sst/opencode), [Claude Code](https://github.com/anthropics/claude-code), [Google Antigravity](https://github.com/google-antigravity/antigravity-cli) y [OpenAI Codex](https://github.com/openai/codex) mediante una interfaz pensada para el tacto — sin terminal, sin SSH y sin PC para el uso en el dispositivo. Ejecuta los runtimes de los agentes mediante PRoot (en el dispositivo) o se conecta de forma remota a tu servidor OpenCode existente en PC/Mac/Linux.

[Releases](https://github.com/yuga-hashimoto/and-code/releases/latest)

[English](README.md) | [日本語](README.ja.md) | [简体中文](README.zh-CN.md) | [Español](README.es.md) | [Français](README.fr.md) | [Português (Brasil)](README.pt-BR.md) | [Русский](README.ru.md) | [العربية](README.ar.md)

> [!IMPORTANT]
> AndCode es una interfaz gráfica independiente y local-first que instala o inicia herramientas de línea de comandos de terceros compatibles en el propio dispositivo Android del usuario. AndCode no proporciona ni revende los servicios de IA subyacentes, suscripciones, acceso a modelos ni derechos de cuenta. La autenticación, el acceso a modelos, la inferencia y la comunicación con los proveedores los gestiona la CLI oficial correspondiente o el proveedor configurado por el usuario. AndCode no está afiliado, respaldado, patrocinado ni soportado oficialmente por OpenCode, Anthropic, Google ni OpenAI. Consulta [Legal y software de terceros](#legal-y-software-de-terceros) más abajo.

<div align="center">

### Capturas de pantalla

<table>
  <tr>
    <td align="center"><img src="screenshots/navigation-drawer.jpg" width="180" alt="Panel de navegación con agentes, proyectos y chats recientes"><br><em>Panel de navegación</em></td>
    <td align="center"><img src="screenshots/model-picker.jpg" width="180" alt="Selector de modelo y runtime con modelos favoritos buscables"><br><em>Selector de modelo y runtime</em></td>
    <td align="center"><img src="screenshots/repository-chat.jpg" width="180" alt="Chat con un agente que inspecciona el repositorio de AndCode"><br><em>Chat del repositorio</em></td>
  </tr>
  <tr>
    <td align="center"><img src="screenshots/image-generation.jpg" width="180" alt="Imagen generada por el agente mostrada dentro de una conversación"><br><em>Generación de imágenes</em></td>
    <td align="center"><img src="screenshots/schedules.jpg" width="180" alt="Prompt programado con una ejecución diaria activada"><br><em>Programaciones</em></td>
    <td align="center"><img src="screenshots/run-result.jpg" width="180" alt="Ejecución programada completada con opción de abrir el chat"><br><em>Resultado de la ejecución</em></td>
  </tr>
</table>

</div>

> [!IMPORTANT]
> AndCode es un proyecto de código abierto independiente. **No** está afiliado a OpenCode, Anthropic, Google ni OpenAI.

---

## Índice

- [Agentes compatibles](#agentes-compatibles)
- [Funciones](#funciones)
- [Antigravity](#antigravity)
- [Codex](#codex)
- [OpenCode remoto](#opencode-remoto)
- [Pantallas](#pantallas)
- [Inicio rápido](#inicio-rápido)
- [Seguridad](#seguridad)
- [Detalles del entorno en el dispositivo](#detalles-del-entorno-en-el-dispositivo)
- [Handoff (cambio de runtime a mitad de conversación)](#handoff-cambio-de-runtime-a-mitad-de-conversación)
- [Conexión con OpenCode Desktop](#conexión-con-opencode-desktop)
- [Compilar desde el código fuente](#compilar-desde-el-código-fuente)
- [Documentación](#documentación)
- [Contribuir](#contribuir)
- [Legal y software de terceros](#legal-y-software-de-terceros)
- [Licencia](#licencia)

## Agentes compatibles

| Agente | En el dispositivo | PC remoto | Estado |
|--------|:-----------------:|:---------:|--------|
| [OpenCode](https://github.com/sst/opencode) | ✓ | ✓ | Estable |
| [Claude Code](https://github.com/anthropics/claude-code) | ✓ | — | Beta |
| [Google Antigravity](https://github.com/google-antigravity/antigravity-cli) | ✓ | — | Beta |
| [OpenAI Codex](https://github.com/openai/codex) | ✓ | — | Beta |

Los agentes en el dispositivo se ejecutan dentro de un entorno Linux mediante PRoot. OpenCode, Claude Code y Codex usan Alpine Linux; Google Antigravity instala además un rootfs de Debian Bookworm junto a Alpine, ya que el binario oficial `agy` enlaza contra glibc.

## Funciones

- **GUI nativa de Android** — Interfaz pensada para el tacto para agentes de programación; no se necesita CLI ni terminal en el uso diario
- **Runtime en el dispositivo** — Alpine Linux, Git, bash, curl, ripgrep y los agentes de programación se instalan automáticamente en tu dispositivo Android mediante PRoot
- **Repositorio y espacio de trabajo** — Abre repositorios git en el dispositivo, explora el árbol de archivos y visualiza archivos con resaltado de sintaxis
- **Archivos del dispositivo** — Concede acceso a todos los archivos y todo el teléfono (`/sdcard`, tarjetas SD, unidades USB) será navegable en el selector de carpetas y accesible para el agente, abierto en su sitio en lugar de copiarse a la app
- **Compatibilidad con Git** — Consulta el estado y revisa los diffs desde la GUI; usa la terminal del runtime integrada para otras operaciones de Git
- **Visor de diffs** — Revisa los cambios de código en línea antes de aceptarlos
- **Terminal integrada** — Un shell interactivo (PTY real) dentro del runtime del dispositivo: salida en streaming, entrada estándar, Ctrl+C, colores, y se abre en el espacio de trabajo desde el que viniste. Los servidores de desarrollo iniciados en ella (Vite, `python -m http.server`, …) aparecen como chips que abren la página en el navegador integrado. Los programas de pantalla completa como `vim` aún no son compatibles
- **Distintivos de pull request** — Las pull requests abiertas en un chat permanecen fijadas sobre el compositor con el tamaño del diff y su estado (borrador, abierta, conflicto, fusionada, cerrada); toca para abrirlas en GitHub
- **Aprobación de herramientas** — Aprueba o rechaza operaciones peligrosas de las herramientas
- **Gestión de sesiones** — Crea, reanuda, renombra y elimina sesiones
- **Tareas programadas** — Ejecuta prompts automáticamente con una programación única o recurrente (cron), con historial de ejecuciones
- **Modelos dinámicos** — Modelos, proveedores y agentes obtenidos en vivo desde la instancia del agente conectada
- **Streaming en tiempo real** — Respuestas, ejecución de herramientas y solicitudes de aprobación en vivo mediante SSE
- **Línea de tiempo estructurada** — Razonamiento, llamadas a herramientas y salida de comandos plegables
- **Voz + palabra de activación** — Push-to-talk con reconocimiento de voz de Android y detección de palabra de activación
- **Texto a voz** — Lee las respuestas en voz alta
- **Asistente digital** — Regístrate como asistente predeterminado de Android (gesto de inicio / deslizamiento desde la esquina)
- **Widget de pantalla de inicio** — Lanza un prompt directamente desde el launcher sin abrir la app
- **Almacenamiento seguro** — Credenciales de conexión cifradas con Android Keystore
- **Interfaz localizada** — Inglés, japonés, chino (simplificado), ruso, español, francés, portugués (Brasil) y árabe, con cambio desde Ajustes

## Antigravity

Google Antigravity (`agy`) se ejecuta en el dispositivo dentro del mismo entorno PRoot. A diferencia de OpenCode y Claude Code, que se ejecutan en Alpine Linux, Antigravity usa un rootfs de Debian Bookworm por compatibilidad de glibc con el binario oficial de la CLI.

- **Inicio de sesión OAuth** — Autenticación mediante el flujo de URL en el navegador + código de un solo uso; las credenciales se guardan solo en el rootfs Linux (`~/.gemini`), nunca en las preferencias de Android
- **Selección de modelo** — Catálogo de modelos en vivo obtenido de la instancia `agy` autenticada (variantes de Gemini, Claude y GPT-OSS)
- **Modos de permisos** — Control de tres niveles: Plan, Accept Edits y Full Access (`--dangerously-skip-permissions`)
- **Servidores MCP** — Lee y escribe directamente `~/.gemini/config/mcp_config.json`, con el mismo formato de configuración que la CLI oficial
- **Lanzador de sandbox** — Proceso basado en PTY con geometría de terminal explícita; `AGY_CLI_DISABLE_AUTO_UPDATE=1` mantiene las actualizaciones controladas por la app

## Codex

La CLI de Codex de OpenAI se ejecuta en el dispositivo dentro del mismo sandbox Alpine/PRoot que OpenCode y Claude Code, comunicándose con la app mediante su protocolo JSON-RPC `app-server`.

- **Inicio de sesión** — Cuenta de ChatGPT (flujo en el navegador) o una clave de API de OpenAI, mediante el mismo diálogo de inicio de sesión que otros proveedores
- **Servidores MCP** — Se gestionan con el subcomando oficial `codex mcp`, de modo que la app nunca edita `~/.codex/config.toml` a mano
- **Generación de imágenes** — Las imágenes de la herramienta integrada `image_gen` se muestran en línea dentro del chat
- **Configuración** — Instalable desde la guía de configuración o Ajustes → Agentes → Codex; no necesita un rootfs aparte, ya que el binario de Codex enlazado con musl se ejecuta directamente en el entorno Alpine compartido

Consulta [docs/CODEX.md](docs/CODEX.md) para los detalles de implementación.

## OpenCode remoto

Además de los agentes en el dispositivo, AndCode puede conectarse a OpenCode ejecutándose en tu PC/Mac/Linux como función adicional:

- **Conexión remota** — Conecta por LAN o Tailscale
- **Cambio de runtime** — Cambia sin problemas entre ejecución local y remota, incluso a mitad de conversación (handoff)
- **Descubrimiento** — Encuentra PCs mediante código QR o mDNS (descubrimiento LAN sin configuración)

## Pantallas

| Pantalla | Descripción |
|----------|-------------|
| Chat | Pantalla de inicio y vista de conversación — sesiones recientes, razonamiento/herramientas plegables, entrada de voz, cambio de modelo, aprobaciones, handoff |
| Espacios de trabajo | Configuración del runtime local, conexiones a PC, carpetas de trabajo, explorador de archivos, visor de código, terminal integrada |
| Programaciones | Crea, edita, activa/desactiva y revisa el historial de ejecuciones de prompts únicos o recurrentes (cron) |
| Ajustes | Agentes y proveedores, GitHub, servidores MCP, visibilidad de modelos, voz/TTS, asistente digital, idioma |

## Inicio rápido

### Opción A: En el dispositivo (sin PC)

1. Instala el APK desde [Releases](https://github.com/yuga-hashimoto/and-code/releases/latest)
2. Abre la app → toca **Espacios de trabajo** → **Este dispositivo Android** → **Configurar en este dispositivo**
3. Espera a que el runtime se descargue e instale (~2 min con una buena conexión)
4. Selecciona tu agente de programación y empieza a chatear

### Opción B: PC remoto

1. Inicia OpenCode en tu PC:

```bash
OPENCODE_SERVER_PASSWORD='your-strong-password' \
  opencode serve --hostname 0.0.0.0 --port 4096 --mdns
```

2. Instala el APK en tu dispositivo Android
3. Abre la app → **Espacios de trabajo** → **Añadir conexión**
4. Introduce la IP de tu PC (o usa **Búsqueda en LAN** / **Código QR** para descubrimiento automático)

```text
Name:     Mac mini
URL:      http://192.168.1.10:4096
Username: opencode
Password: your-strong-password
```

> Tailscale también funciona: `http://100.x.y.z:4096` o `http://your-mac.tailnet-name.ts.net:4096`

### Configuración por código QR

Genera un código QR en tu PC:

```bash
npx qrcode "opencode://connect?name=Mac%20mini&url=http%3A%2F%2F192.168.1.10%3A4096&username=opencode&password=your-password&insecure=true"
```

Luego escanéalo desde **Espacios de trabajo** → **Añadir por QR** en la app.

## Seguridad

- **Nunca** expongas el puerto 4096 directamente a internet
- Usa LAN o Tailscale para la conectividad
- Usa un proxy inverso HTTPS en redes públicas
- La app nunca aprueba automáticamente operaciones peligrosas
- El HTTP en texto plano en LAN requiere una aceptación explícita por conexión
- PRoot es un runtime de compatibilidad, no un sandbox de seguridad completo. Los modos de acceso total / omisión de permisos permiten que la CLI oficial ejecute comandos y edite archivos sin preguntar; la app advierte antes de activarlos, pero aun así deberías hacer copias de seguridad de los archivos importantes y apuntar un agente solo a repositorios, prompts y servidores MCP de confianza
- Los códigos QR generados para el flujo `opencode://connect` pueden incluir una contraseña de conexión en texto plano — escanea solo códigos de una fuente de confianza y trata un código QR impreso o mostrado igual que compartir esa contraseña

## Detalles del entorno en el dispositivo

El proceso de configuración (iniciado desde Espacios de trabajo):

1. Verifica el runner nativo de PRoot incluido en el APK
2. Descarga el minirootfs de Alpine Linux desde el CDN oficial
3. Descarga el binario del agente desde GitHub Releases
4. Valida las sumas de comprobación SHA-256 de ambos
5. Extrae a un directorio privado de la app
6. Instala Git, bash, curl, ripgrep y certificados de CA dentro de Alpine
7. Inicia el servidor del agente en `127.0.0.1:4097`
8. Cambia la app al runtime local

Versiones fijadas (actualizables mediante releases de la app sin cambios en el agente; consulta [`local-runtime-manifest.json`](app/src/main/assets/local-runtime-manifest.json)):

- Alpine Linux 3.24.1
- OpenCode 1.18.5
- Google Antigravity CLI 1.1.7 (rootfs de Debian Bookworm)
- Arquitecturas: arm64-v8a, x86_64

## Handoff (cambio de runtime a mitad de conversación)

Desde el menú de la cabecera del chat → **Continuar en otro runtime** — la app genera un prompt de resumen de la conversación y lo envía al runtime seleccionado, permitiéndote retomar donde lo dejaste (por ejemplo, empezar en el dispositivo mientras viajas y continuar en tu PC al llegar a casa).

## Conexión con OpenCode Desktop

Añade la configuración del servidor a `~/.config/opencode/opencode.json`:

```json
{
  "server": {
    "port": 4096,
    "hostname": "0.0.0.0",
    "mdns": true
  }
}
```

Reinicia la app de escritorio y luego descúbrela desde la app de Android mediante **Búsqueda en LAN**.

## Compilar desde el código fuente

Requisitos: JDK 17, Android SDK, Python 3, acceso a red (solo la primera compilación)

```bash
./gradlew detekt spotlessCheck :app:lintGithubDebug :app:testGithubDebugUnitTest :app:assembleGithubDebug :app:assembleGithubRelease
```

APKs generados:

```text
app/build/outputs/apk/github/debug/app-github-debug.apk
app/build/outputs/apk/github/release/app-github-release-unsigned.apk
```

Instalar en el dispositivo:

```bash
adb install -r app/build/outputs/apk/github/debug/app-github-debug.apk
```

## Documentación

- [Diseño del runtime en el dispositivo](docs/LOCAL_RUNTIME.md)
- [Runtime local de Antigravity](docs/ANTIGRAVITY.md)
- [Diseño de paridad del agente Antigravity](docs/superpowers/specs/2026-07-27-antigravity-agent-parity-design.md)
- [Runtime local de Codex](docs/CODEX.md)
- [Guía de CI](docs/CI.md)
- [Guía de releases](docs/RELEASE.md)
- [Guía de traducción](docs/TRANSLATION.md)
- [Matriz de validación de dispositivos](docs/device-matrix.md)

## Contribuir

Las contribuciones son bienvenidas — código, informes de errores y traducciones. Consulta [CONTRIBUTING.md](CONTRIBUTING.md) para la configuración y el flujo de trabajo, y [docs/TRANSLATION.md](docs/TRANSLATION.md) si quieres ayudar a traducir la app.

## Legal y software de terceros

- **Las CLI oficiales se ejecutan en el dispositivo.** OpenCode, Claude Code, Google Antigravity y OpenAI Codex son los binarios oficiales sin modificar de sus respectivos proyectos, descargados de sus canales de distribución oficiales y ejecutados dentro del entorno Linux PRoot de tu dispositivo (o, para OpenCode, en tu propia máquina PC/Mac/Linux). AndCode no bifurca, parchea ni reimplementa la lógica de sus agentes.
- **Tú aportas tu propia cuenta o acceso a la API.** AndCode no vende, revende ni proporciona de otro modo acceso a Claude, Antigravity, Codex, modelos de OpenCode ni GitHub. Te autenticas con tu propia cuenta o configuración de proveedor, sujeta a los términos de servicio vigentes de ese proveedor.
- **AndCode no retransmite prompts ni tokens a través de sus propios servidores.** No existe un backend de AndCode por el que pasen prompts, archivos ni tokens de OAuth. Las solicitudes van directamente desde la CLI en el dispositivo (o el servidor OpenCode de tu PC) al proveedor que hayas configurado. Consulta [docs/AUTHENTICATION_AND_DATA_FLOW.md](docs/AUTHENTICATION_AND_DATA_FLOW.md).
- **Los tokens OAuth no se reutilizan entre herramientas.** Claude Code, Antigravity y Codex guardan cada uno sus propias credenciales OAuth dentro del rootfs Linux/Debian como haría normalmente su CLI oficial; AndCode nunca las copia a los ajustes de la app Android, al almacén de credenciales de otro agente ni a un servicio externo.
- **Consulta también:** [PRIVACY.md](PRIVACY.md), [TERMS.md](TERMS.md), [THIRD_PARTY_SERVICES.md](THIRD_PARTY_SERVICES.md), [TRADEMARKS.md](TRADEMARKS.md) y [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) (licencias de OSS de los componentes del runtime incluidos y las dependencias). Todos ellos también son accesibles sin conexión desde **Ajustes → Legal y privacidad** en la app.

La generación del runtime reutiliza lógica genérica de resolución y extracción de paquetes de Termux, rediseñada para agentes de programación, inspirada en la implementación Android de Hermes Agent bajo licencia MIT. Consulta [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) para la lista completa de componentes de terceros incluidos y sus licencias.

## Licencia

El **código fuente de AndCode** en este repositorio tiene [licencia MIT](LICENSE). Esa licencia cubre solo el código Kotlin/Android propio de AndCode — **no** se extiende a las CLI, runtimes o paquetes de terceros que AndCode instala o inicia (Claude Code, Google Antigravity, OpenAI Codex, OpenCode, PRoot, paquetes de Alpine/Debian y otros), cada uno distribuido bajo su propia licencia upstream. Consulta [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) y [TRADEMARKS.md](TRADEMARKS.md).
