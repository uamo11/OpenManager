# OpenManager v1.9.48: Botón de Atrás al Ver APKs y Rediseño Anti-Desbordamiento en Menú de APK con Icono de Lápiz

### 🔙 Botón de Retroceso y Navegación al Explorar APKs
- **Corrección de `isHomeOrRootPath` para Rutas de Archivos Comprimidos (`ArchivePath`)**:
  - Al explorar el interior de un archivo APK o comprimido mediante la acción "VER", la ruta raíz interna (`/`) se clasificaba erróneamente como la raíz del sistema o directorio home.
  - Esto provocaba que `canNavigateUp` devolviese `false`, ocultando el botón dedicado de retroceso `←` (`btnMainBack`) en la barra superior y desactivando los callbacks de retorno (`navigateUpCallback`).
  - Con la nueva verificación (`if (path.isArchivePath) return false`), el botón `←` permanece visible en la barra superior y tanto el botón superior, como el botón inferior `<` y el gesto de retroceso del sistema permiten regresar de forma limpia a la carpeta contenedora (`/storage/emulated/0`).
  - Se garantiza además la presencia del elemento `[📁↑] ..` como primer elemento en la lista de archivos del APK.

### 📱 Rediseño Anti-Desbordamiento del Menú APK y Botón Ver con Lápiz
- **Alineación Responsiva y Soporte Multi-Resolución (`dialog_mt_apk_info.xml`)**:
  - Encapsulado de la botonera inferior dentro de un contenedor `HorizontalScrollView` con `fillViewport="true"`, permitiendo que en resoluciones compactas o con fuentes ampliadas los botones nunca se corten contra el borde derecho ("traspasen la pared").
  - Optimización del padding del diálogo (`paddingStart/End="16dp"`) y márgenes entre botones para garantizar que en cualquier pantalla estándar de smartphone quepan las 4 acciones principales en una sola línea sin desbordar.
- **Icono de Lápiz en el Botón "VER" (`ic_pencil_edit_16dp.xml`)**:
  - Incorporación del vector de edición/lápiz Material en el botón de visualización (`btnApkView`) con tinte azul acorde a la acción (`#2196F3`), aportando una referencia visual clara de inspección y edición del APK.

---

# OpenManager v1.9.47: Buscador de Duplicados MT, Reemplazador de Palabras por Lotes y Fix de Barra Inferior con Teclado

### 🔍 Buscador de Archivos Duplicados MT
- **Motor de Escaneo en 3 Fases (`DuplicateFinderEngine.kt`)**:
  - Detección ultrarrápida:
    1. Agrupamiento inicial por tamaño (descarta archivos únicos sin lecturas de disco innecesarias).
    2. Hash parcial rápido (primeros y últimos 4KB con SHA-256).
    3. Verificación exhaustiva con suma SHA-256 completa en búfer para garantizar identidad exacta.
  - Filtros flexibles: profundidad recursiva, tamaño mínimo (`Todos`, `> 1 KB`, `> 100 KB`, `> 1 MB`), y extensiones de archivo personalizadas.
- **Interfaz y Gestión de Duplicados (`DuplicateFinderDialog.kt`, `dialog_duplicate_finder.xml`)**:
  - Vista de resultados agrupada por coincidencias idénticas con cálculo en tiempo real de bytes extra desperdiciados.
  - Píldoras de selección inteligente: **"Conservar más antiguo"**, **"Conservar más nuevo"**, **"Invertir"**, y **"Desmarcar todo"**.
  - Eliminación segura con confirmación y liberación inmediata de espacio en disco.

### 📝 Buscador y Reemplazador de Palabras en Archivos
- **Motor de Búsqueda y Reemplazo de Alto Rendimiento (`FileTextSearchEngine.kt`)**:
  - Búsqueda recursiva o local en archivos de texto, con detección y protección contra archivos binarios (análisis de bytes nulos y límite de 20MB).
  - Opciones de coincidencia: distinción de mayúsculas/minúsculas, palabra completa (`\b...\b`), expresiones regulares (Regex).
  - Filtro por extensiones con chips rápidos (`Todos`, `.xml`, `.smali`, `.java, .kt`, `.json, .txt`).
- **Sustitución de Texto Flexible ("algunas o todas las palabras")**:
  - **Reemplazar en seleccionados ("algunas palabras")**: Reemplaza únicamente en los archivos marcados por el usuario.
  - **Reemplazar en todos ("todas las palabras")**: Aplica el reemplazo en todas las coincidencias encontradas.
  - Sustitución atómica y segura mediante `Matcher.quoteReplacement` (inmune a fallos con caracteres especiales como `$` o `\`).
- **Navegación Interactiva al Editor de Código**:
  - Al pulsar sobre cualquier coincidencia en los resultados, se abre de inmediato en `TextEditorActivity` posicionando el cursor y haciendo scroll a la línea exacta (`EXTRA_GOTO_LINE`).

### ⌨️ Corrección de Visibilidad de Barra Inferior con Teclado
- **Ajuste de `windowSoftInputMode` y Detección de Insets IME**:
  - Declaración explícita de `android:windowSoftInputMode="adjustResize"` en `AndroidManifest.xml` para `FileListActivity`.
  - Integración de escucha de insets `WindowInsetsCompat.Type.ime()` en `FileListActivity.kt`: al abrirse el teclado virtual, el margen inferior de la barra de navegación se anula dinámicamente a `0dp`, permitiendo que la barra inferior (`#bottomBar`) repose sobre el teclado sin ocultarse ni salirse de pantalla.
  - Sincronización en `TextEditorFragment.kt` para que la barra de símbolos (`symbolBar`) y el panel de búsqueda se mantengan anclados sobre el teclado sin espacios muertos.

---

# OpenManager v1.9.46: Rediseño Developer-Grade de Terminal, Keycaps Táctiles y Blindaje de Seguridad

### ⚡ Rediseño Integral de la Interfaz de Terminal (Developer-Grade)
- **Eliminación del Aspecto Improvisado / Generado por IA en la Barra Superior**:
  - Reestructuración de `activity_terminal.xml`: eliminación de los botones de texto planos (`A-`, `14sp`, `A+`) amontonados en la barra de herramientas.
  - La barra principal (`MaterialToolbar`) ahora se concentra de forma limpia en la identidad del proceso (`scriptName` o shell activo) con tipografía monoespaciada y acciones superiores dedicadas: Escudo de auditoría de scripts, Copia rápida de salida y Menú contextual de tres puntos.
- **Franja HUD Técnica y Breadcrumb de Sesión en Tiempo Real**:
  - Subcabecera técnica (`38dp`, fondo Obsidian `#161B22` con borde divisor sutil `#21262D`):
    - **Insignia de Sesión (`layoutSessionBadge`)**: Píldora redondeada con indicador LED en vivo (`●` verde esmeralda `#3FB950` en ejecución, rojo `#F85149` si finalizó) con etiquetas monoespaciadas: `ROOT (#)`, `TERMEX (bash)`, `SHELL (sh)`, `EXIT [código]`.
    - **Ruta de Trabajo / Breadcrumb (`tvTerminalBreadcrumb`)**: Icono de carpeta vectorial con ruta acortada inteligentemente (`~`, `[app]`, `/system/bin`) en tipografía monoespaciada `#8B949E`.
    - **Cápsula de Zoom Ergonómica (`layoutZoomPill`)**: Control táctil unificado `[ − ]  14sp  [ + ]` dentro de una cápsula oscura (`#161B22`, radio 14dp), manteniendo el 100% de compatibilidad con atajos táctiles, restablecimiento a 14sp y teclas físicas de volumen.
- **Keycaps Táctiles de Desarrollador en el Teclado Extra (`TerminalExtraKeysView.kt`)**:
  - **Fila 0 (Chips de sintaxis y señales)**: Píldoras con resaltado semántico de sintaxis:
    - `Ctrl+C`: Insignia carmesí (`#3D1B1E` / texto `#FF7B72`) para identificación inmediata de interrupción SIGINT.
    - `Ctrl+D`: Púrpura (`#2A1B38` / `#D2A8FF`) para señal EOF.
    - `Ctrl+Z`: Ámbar (`#332212` / `#FFA657`) para suspensión SIGTSTP.
    - `Clear`: Azul cian (`#122738` / `#79C0FF`) para limpieza de pantalla.
    - Comillas (`"`, `'`), variables (`$`, `~`), tuberías (`|`, `>`, `&`) y banderas (`/`, `-`, `*`) con colores syntax-highlighting.
  - **Filas 1 y 2 (Keycaps táctiles)**:
    - Teclas direccionales (`▲`, `◀`, `▼`, `▶`): Keycaps elevados en gris pizarra (`#282E38`), borde `#3E4756`, glifos `#F0F6FC` y repetición continua con vibración háptica.
    - Modificadores `Ctrl` y `Alt`: En reposo presentan texto `#58A6FF`; al activarse/enclavarse se iluminan en **azul eléctrico brillante (`#1F6FEB` con borde `#58A6FF` de 2dp y texto blanco)**, brindando certeza visual absoluta del estado enclavado.
    - Tecla de borrado `⌫`: Acento ámbar `#FFA657` con auto-repetición.
- **Insignia Flotante de Seguridad Dead Man (`layoutDeadManAlertBadge`)**:
  - Cápsula flotante con elevación (4dp), degradado carmesí profundo (`#4A161A` ➔ `#2C0C0E`), borde `#F85149` (1.5dp), icono de calavera luminosa y texto monoespaciado `DEAD MAN: BLOQUEADO`.
- **Encuadre de Pantalla del Monitor de Terminal y Paleta Obsidian Dark**:
  - Fondo del contenedor en Obsidian oscuro (`#0D1117`), barras del sistema integradas (`#161B22` y `#0F1217`), y margen sutil para encuadrar la pantalla como un auténtico monitor de consola sin tocar los bordes del dispositivo.
- **Diálogo de Preferencias Material 3 (`terminal_preferences_dialog.xml`)**:
  - Reorganizado en tarjetas temáticas (`bg_terminal_pref_card`): Entorno & Ruta, Pantalla & Tipografía, Sistema & Binarios, con divisores suaves e iconos vectoriales.
- **Suite de Pruebas Unitarias de Terminal (`TerminalLogicTest.kt`)**:
  - Validación de combinaciones `Ctrl` / `Alt` y formateo de rutas de breadcrumb en el HUD.

---

# OpenManager v1.9.45: Blindaje Integral de Seguridad, Remediación CVE y Protección Avanzada de Kernel

### 🛡️ Auditoría y Mitigación Integral de Seguridad y Vulnerabilidades CVE
- **#1 [ALTA] Hardening del Servidor FTP Integrado**:
  - Restricción del acceso anónimo desactivado por defecto (`anonymous_login = false`).
  - Si el usuario habilita el acceso anónimo, se fuerza modo estrictamente de solo lectura (`writable = false`), impidiendo sobreescritura, borrado o subida maliciosa de archivos en la red local.
  - Generación de contraseñas seguras de alta entropía con `SecureRandom` excluyendo caracteres visualmente ambiguos (0, O, 1, l, I).
- **#2 [CRÍTICA] Mitigación Definitiva de CVE-2024-52046 en Apache MINA (`mina-core:2.1.3`, CVSS 9.8)**:
  - Neutralización en tiempo de compilación mediante clases seguras sustitutas (`ObjectSerializationDecoder.java` y `ObjectSerializationInputStream.java`) que anulan los gadgets RCE por deserialización insegura lanzando `SecurityException`.
  - Reglas de optimización en `proguard-rules.pro` para depurar el paquete del binario final compilado sin romper compatibilidad con Android API < 24.
- **#3 [MEDIA] Seguridad de Red y Supresión de Tráfico en Texto Claro Global**:
  - Corrección del nombre del recurso `res/xml/network_security_config.xml` (eliminando typo `netework_`).
  - Eliminación de `usesCleartextTraffic="true"` global en `AndroidManifest.xml`.
  - Confinamiento de certificados de usuario (`user` CA) exclusivamente a compilaciones de depuración (`debug-overrides`).
- **#4 [BAJA] Acotamiento de Exenciones de Hidden APIs en Runtime JNI (`hiddenapi.c`)**:
  - Eliminación de las exenciones globales wildcard (`""` y `"L"`).
  - Definición de lista blanca estricta (`EXEMPTION_PREFIXES`) limitada a las 17 firmas DEX esenciales para las funciones de OpenManager, con manejo y limpieza de excepciones JNI.
- **#5 [ALTA] Mitigación de CVE-2026-15028 en `libarchive:1.1.7` (CVSS 8.6)**:
  - Sanitizador continuo con estado `ArchiveSecuritySanitizer.StreamSanitizer` en `ReadArchive.kt` para inspeccionar flujos de entrada en tiempo real y neutralizar secuencias malformadas de atributos extendidos `SUN.holesdata` en cabeceras PAX de archivos TAR.
- **#6 [ALTA] Mitigación de CVE-2026-14164 en `libarchive:1.1.7` (CVSS 7.8)**:
  - Desactivación por defecto del soporte de descompresión RAR5 en `ArchiveSecurityManager.kt`.
  - Diálogo interactivo de advertencia (`MaterialAlertDialogBuilder`) al intentar habilitar el formato en Ajustes.
  - Pre-validación estructural profunda en Kotlin (`validateRar5`) antes de invocar la biblioteca C nativa: verificación de número mágico de 8 bytes, validación de CRC32 de cabecera, comprobación de VINT y limitación estricta de tipos de bloque (`HEAD_MAIN` / `HEAD_CRYPT`).
- **#7 [ALTA] Mitigación de GHSA-hvp5-5x4f-33fq en `jadx-core:1.4.7` (CVSS 7.5)**:
  - Decompilación exclusivamente en memoria con `NoOpCodeCache` y omisión de extracción de recursos nativos (`isSkipResources = true`).
  - Sanitización estricta de nombres relativos de clases y archivos exportados (`sanitizeRelativeSourcePath`) y confinamiento de rutas canónicas (`canonicalPath.startsWith(...)`) para prevenir Path Traversal y Zip Slip.
- **#8 [ALTA] Blindaje contra Syscalls Destructivas, Evasión por Symlinks y Comandos Encadenados**:
  - Resolución canónica profunda con `File.canonicalPath` en `DeadManProtectionManager.kt` (`isProtectedFromModification`, `isCatastrophicSystemDeletion`, `isCriticalBootFile`) que impide saltos de seguridad mediante enlaces simbólicos o secuencias `..` dirigidas a `/system`, `/vendor`, `/dev/block/`, `/data/system/` o `/data/adb/`.
  - Desglose de comandos encadenados mediante `[;&|\n]+` en `DeadManProtectionManager.kt` y `ScriptRunnerHelper.kt` para auditar cada subcomando de forma independiente contra patrones destructivos (`rm -r -f`, comodines, alias `toybox rm`/`busybox rm`, `chroot`, `pivot_root`, `reboot`, `sys_reboot`).
  - Incorporación en `ElfInspector.kt` de símbolos sensibles para enlaces simbólicos (`symlink`, `symlinkat`, `readlink`, `readlinkat`) y aislamiento (`chroot`, `pivot_root`, `setns`, `unshare`, `sys_reboot`).
  - Monitoreo en tiempo real de `/proc/<pid>/cwd` en `SyscallReaderMonitor.kt` para detectar ejecuciones con directorio de trabajo en particiones protegidas.

---

# OpenManager v1.9.44: Ejecución Avanzada de Binarios C/C++ (ELF) y Telemetría de Syscalls en Vivo

### ⚙️ Ejecución Hardened de Scripts y Binarios Compilados en C/C++ (ELF)
- **Superación de Restricciones SELinux y `noexec` en Android Storage**:
  - `/storage/emulated/0` está montado con la bandera `noexec`, lo que bloquea la ejecución de binarios ELF (`execve: Permission denied`) incluso bajo superusuario.
  - **Modo Root**: El binario se copia automáticamente a `/data/local/tmp/<bin>`, se le asignan permisos `chmod 755` y se ejecuta directamente desde una partición sin restricciones de ejecución.
  - **Modo Termex**: El binario se despliega en el directorio ejecutable de Termex (`$PREFIX/tmp` o `$PREFIX/bin`) con permisos `chmod 755`, ejecutándose nativamente con soporte de bibliotecas compartidas de Termex.
  - **Modo Sin Root / Cargador Dinámico**: Detección y ejecución mediante el enlazador dinámico de Android (`/system/bin/linker64` o `/system/bin/linker`), permitiendo cargar binarios dinámicos nativamente.
- **Detección de Binarios PIE vs Non-PIE**:
  - Verificación del tipo ELF (`ET_DYN` vs `ET_EXEC`). Se advierte al usuario si un binario antiguo no es Position-Independent Executable (PIE), el cual es requerido por Android 5.0+.

---

### ⚡ Lectura en Vivo y Telemetría de Syscalls de Linux/Android
- **Monitor de Sondeo en Tiempo Real (`SyscallReaderMonitor`)**:
  - Monitoreo en segundo plano de `/proc/<pid>/syscall` y `/proc/<pid>/io` durante la ejecución activa de cualquier proceso.
  - Rastreo automático de hilos y subprocesos hijos descubiertos en `/proc/<pid>/task`.
- **Traductor de Llamadas al Sistema Multi-Arquitectura (`SyscallResolver`)**:
  - Traducción instantánea de números de syscall según la arquitectura del dispositivo (ARM64, ARM32, x86_64, x86).
  - Categorización precisa: `DELETION_FS`, `PROCESS_EXEC`, `SIGNALING`, `PRIVILEGE`, `DEBUG_PTRACE`, `NETWORK`, `SYSTEM_ADMIN`, `MEMORY`, `FILE_IO`.
  - Métrica de llamadas I/O en tiempo real: `syscr` (lecturas del sistema) y `syscw` (escrituras del sistema), junto con bytes leídos y escritos.

---

### 🛡️ Transparencia Total y Detección de Syscalls de Riesgo
- **Inspector Estático de Binarios ELF (`ElfInspector`)**:
  - Análisis estático previo a la ejecución: lee encabezados ELF, arquitectura de máquina, bitness (32/64 bit), endianness, intérprete dinámico y símbolos de bibliotecas compartidas.
  - Detección de funciones y syscalls sensibles (`unlink`, `unlinkat`, `rmdir`, `system`, `popen`, `execve`, `ptrace`, `kill`, `socket`, `connect`, `mount`, `reboot`, `setuid`, `mprotect`).
  - Alerta preventiva en el diálogo de ejecución: si el binario C contiene llamadas de riesgo, se muestra el diálogo `showPreExecutionAuditWarningDialog` detallando cada riesgo antes de ejecutar.
- **Nueva Tarjeta en el Diálogo de Auditoría: "Lectura y Detección de Syscalls en Vivo"**:
  - Indicador de estado del monitor en tiempo real (`MONITOREANDO`, `FINALIZADO`, `EN ESPERA`).
  - Syscall activa actual con nombre, número, PID, categoría y argumentos de registros.
  - Tabla de desglose de las syscalls más frecuentes con insignias de riesgo (`CRÍTICO`, `ALTO`, `MEDIO`, `NORMAL`).
  - Métricas de lecturas (`syscr`) y escrituras (`syscw`).
- **Integración en Pestaña Script y Detalles de Propiedades**:
  - Reconocimiento automático de ejecutables nativos ELF compilados en C/C++, mostrando su arquitectura, tipo de enlazado y reporte de seguridad.

---

# OpenManager v1.9.43: Sistema Avanzado de Auditoría de Scripts Shell (.sh) y Supresión de Bucle de Notificaciones

### 🛡️ Sistema Integral de Auditoría de Scripts (.sh) Antes y Durante la Ejecución
- **Alerta Preventiva Previa a la Ejecución (`showPreExecutionAuditWarningDialog`)**:
  - Al pulsar el botón "EJECUTAR" en cualquier script shell o binario, el motor audita de forma estática su contenido. Si detecta operaciones potencialmente peligrosas (borrado de datos, comandos root, conexiones de red o accesos a particiones de sistema), presenta una alerta clara indicando la cantidad exacta de riesgos por categoría y permitiendo auditar las líneas directamente o continuar con la ejecución bajo conocimiento del usuario.
- **Visor Interactivo de Código con Resaltado de Líneas Advertidas (`showFullCodeDialog`)**:
  - Visor monospaciado con numeración de líneas (`01`, `02`, ...), resaltando en fondo ámbar/rojizo con el marcador `⚠️` cada línea con operaciones críticas detectadas.
  - Título con conteo de líneas advertidas y botón directo para copiar el código completo al portapapeles.
- **Auditoría Integrada en la Pestaña Script de Propiedades**:
  - La pestaña "Script" de las Propiedades del Archivo ahora incorpora una fila interactiva de **Auditoría de Seguridad**:
    - `🛡️ Script verificado (Limpio)` o `⚠️ X riesgos detectados`.
    - Al tocar, abre inmediatamente el desglose completo por categorías y el visor de código con líneas resaltadas.
- **Tarjeta de Auditoría Estática en el Diálogo de Auditoría de Terminal (`ScriptAuditDialogFragment`)**:
  - Al abrir el diálogo de auditoría desde la terminal (icono de escudo en la barra de herramientas o menú):
    - Muestra la nueva tarjeta **Auditoría Estática de Código Fuente** con el estado (`LIMPIO` o `X RIESGOS`), desglose de borrados, llamadas a superusuario, conexiones de red y accesos a particiones de sistema.
    - Botón para examinar el código y líneas advertidas directamente en cualquier momento durante o después de la ejecución.
    - La exportación de registro (`Copiar Registro`) ahora incluye tanto los hallazgos estáticos del código fuente como la telemetría en tiempo real.

---

### 🔇 Supresión Definitiva del Bucle de Notificaciones al Cerrar Scripts
- **Control de Terminación por el Usuario (`isTerminatedByUser`)**:
  - Se identificó y resolvió la condición de carrera donde, al pulsar "Terminar" desde la notificación en segundo plano, el cierre forzado del proceso (`SIGKILL`) emitía concurrentemente un evento `TerminalSession.onSessionFinished` a través del hilo PTY, lo que provocaba que el servicio recreara inmediatamente una nueva notificación de "Script Terminado", entrando en un bucle infinito de notificaciones.
  - Con el flag atómico `isTerminatedByUser`, cualquier callback posterior al cierre voluntario por parte del usuario es silenciado de inmediato, cancelando la notificación y deteniendo el servicio en primer plano sin duplicados.

---

### 🌐 Interceptación Heredable en Subshells y Prevención de Errores en Storage
- **Exportación de Funciones de Shell a Subshells Bash y POSIX**:
  - Se exportaron las funciones interceptoras (`export -f rm rmdir unlink 2>/dev/null || true`) en `om_shell_rc.sh`.
  - Se agregaron las variables de entorno `BASH_ENV` y `ENV` apuntando al script de inicialización del shell, permitiendo que cualquier script secundario o invocación `bash script.sh` herede las políticas sin intentar ejecutar shims en `filesDir/bin` que violen SELinux en Android 10+.

---

# OpenManager v1.9.42: Auditoría Integral y Blindaje de Selección, Copia, Mover y Ejecución de Scripts

### 🛡️ Blindaje Completo de Ejecución de Scripts y Prevención Definitiva de `rm: Permission denied`
- **Prioridad de Binarios de Sistema en `PATH`**:
  - Se colocó `/system/bin` al inicio absoluto de `EXTENDED_SYSTEM_PATH` (`/system/bin:/system/xbin:...`), garantizando que cualquier script (como `a.sh`) que ejecute `rm`, `rmdir`, etc. resuelva inmediatamente al ejecutable del sistema (`/system/bin/rm`) y nunca a directorios de datos de la app.
- **Limpieza Estricta de `files/bin`**:
  - Se eliminan automáticamente todos los archivos del directorio privado `binDir` excepto `apktool` y `.apk_path`. Se purga cualquier shim heredado o ejecutable que viole las políticas SELinux W^X (`execve` en `app_data_file`).
- **Soporte de `apktool` como Función de Shell**:
  - `apktool` se expone en `om_shell_rc.sh` mediante alias y función shell que invocan explícitamente `/system/bin/sh`, garantizando compatibilidad total sin violar restricciones SELinux en Android 10+.

---

### 🔍 Nueva UI de Auditoría de Scripts de Seguridad
- **Análisis de Seguridad en Diálogo de Scripts**:
  - Al abrir un script (`.sh`, binario ELF o ejecutable), el diálogo de opciones incluye una fila interactiva de **Auditoría**:
    - `🛡️ Script Seguro (0 avisos)` si no contiene comandos destructivos ni llamadas de red.
    - `⚠️ X avisos detectados` si detecta operaciones críticas.
- **Detección Exhaustiva de Operaciones**:
  - **Eliminación y Formateo**: Detección de comandos `rm`, `rmdir`, `unlink`, `truncate`, `dd if=`, `mkfs`, `fdisk`.
  - **Superusuario / Root**: Detección de llamadas a `su`, `sudo`, `magisk`, `ksud`.
  - **Conexiones de Red**: Detección de `curl`, `wget`, `nc`, `netcat`, `ssh`, `ping`.
  - **Rutas Críticas de Sistema**: Detección de accesos a `/system`, `/vendor`, `/dev/block`, `/data/data`, `/data/user`.
- **Visor de Código Completo con Líneas Numeradas**:
  - Diálogo que permite auditar línea por línea el contenido antes de decidir su ejecución o edición.

---

### ⚡ Perfeccionamiento y Corrección de Selección de Archivos
- **Corrección de Alternancia (Toggle) por Deslizamiento**:
  - Al deslizar sobre un archivo que ya estaba seleccionado, el sistema ahora lo **deselecciona** correctamente en lugar de volver a seleccionarlo o forzar una selección por rango, permitiendo desmarcar archivos individuales con un simple swipe.
- **Gestión Inteligente del Ancla de Rango**:
  - Al deseleccionar el elemento que servía de ancla, el puntero de rango se reubica automáticamente en el elemento seleccionado más cercano, o se reinicia a `-1` si ya no quedan archivos marcados.
- **Aislamiento Total al Navegar entre Directorios**:
  - Al cambiar de carpeta (`navigateTo`, `navigateUp`, `resetTo`, `onCurrentPathChanged`), cualquier selección activa se limpia automáticamente, eliminando selecciones fantasma entre directorios.
- **Poda Automática de Archivos Inexistentes**:
  - Al recargar la lista de archivos, cualquier elemento que haya sido eliminado o movido se purga automáticamente del conjunto de seleccionados.
- **Inmunidad del Directorio Padre `..`**:
  - El elemento `..` está estrictamente excluido de `makePathListForJob`, diálogos de confirmación de borrado, copia, movimiento y selección masiva.

---

### 🚀 Blindaje de Operaciones de Copia y Mover
- **Protección contra Movimiento en el Mismo Directorio (Self-Move)**:
  - Se bloquea el intento de mover archivos al mismo directorio donde ya se encuentran, notificando al usuario con `"El origen y el destino son el mismo directorio"`. El botón `Mover >>` / `<< Mover` se atenúa y desactiva de forma reactiva si ambos paneles apuntan a la misma carpeta.
- **Protección contra Bucles Recursivos (Mover/Copiar dentro de sí misma)**:
  - Se valida preventivamente que ninguna carpeta seleccionada sea ancestro de la carpeta de destino (`targetDirectory.startsWith(item.path)`). Se notifica inmediatamente evitando inicios de servicios innecesarios o bucles infinitos en disco.
- **Protección contra Escritura en Archivos Comprimidos**:
  - `canWriteTo` verifica si la ruta de destino es un archivo comprimido (`isArchivePath`) o solo lectura, bloqueando operaciones no soportadas e informando al usuario.

---

### 🧪 Pruebas Unitarias Automatizadas
- Nueva suite `SelectionCopyMoveLogicTest.kt` validando:
  - Alternancia y deselección de elementos ya seleccionados con actualización de ancla.
  - Bloqueo de auto-movimiento en el mismo directorio.
  - Bloqueo de copia o movimiento de carpetas dentro de sus propias subcarpetas.
  - Detección precisa del motor de auditoría de scripts en comandos destructivos, root, red y rutas críticas.

---

# OpenManager v1.9.41: Dynamic Directional Action Indicators (>> / <<) & MT-Style Selection Bar

### 🎯 Flechas Dinámicas de Destino (`>>` y `<<`) en Mover y Copiar
- **Orientación según el Panel Activo**:
  - Al seleccionar uno o varios archivos, los botones de **Copiar** y **Mover** adaptan automáticamente la posición de sus indicadores de dirección según el panel donde te encuentres:
    - **Panel Izquierdo (A)**: Las flechas apuntan hacia el panel derecho de destino:
      `Copiar >>` (o `Extraer >>` en archivos comprimidos)
      `Mover >>`
    - **Panel Derecho (B)**: Las flechas apuntan hacia el panel izquierdo de destino:
      `<< Copiar` (o `<< Extraer` en archivos comprimidos)
      `<< Mover`
  - Se eliminó la orientación fija exclusivamente a la derecha (`➔` o `->`), reflejando con exactitud la dirección real de la transferencia entre paneles duales idéntico a MT Manager.

---

### 📱 Barra Inferior de Selección MT Manager (5 Columnas)
- **Rediseño Completo de `bottomBarSelection`**:
  - **Columna 1: `Todo` / `Desmarcar`**: Muestra `Todo ($count)` al seleccionar archivos, o `Desmarcar` cuando todos los elementos del directorio están marcados. Pulsación corta para alternar selección completa; pulsación larga para invertir selección.
  - **Columna 2: `Copiar >>` / `<< Copiar`**: Copia o extrae al panel opuesto según la dirección activa.
  - **Columna 3: `Mover >>` / `<< Mover`**: Mueve los archivos al panel opuesto. Se desactiva y atenúa de forma reactiva si el origen es de solo lectura o un archivo ZIP/APK comprimido.
  - **Columna 4: `Borrar`**: Acceso directo para eliminar los archivos seleccionados con diálogo de confirmación.
  - **Columna 5: `Más...`**: Menú contextual con acciones avanzadas (`Invertir selección`, `Desmarcar todo`, `Renombrar`, `Propiedades`, `Intercambiar nombres`, `Comprimir a ZIP`, `Compartir`, `Exportar lista...`).

---

### 📋 Menú Contextual de Archivo MT (`dialog_mt_file_menu.xml`)
- Actualizadas las etiquetas de acción de la primera fila en el menú emergente:
  - Muestra `Copiar >>` / `Mover >>` cuando se abre desde el panel izquierdo.
  - Muestra `<< Copiar` / `<< Mover` cuando se abre desde el panel derecho.
  - Elimina el punto redundante (`•`) para garantizar legibilidad limpia y sin truncamiento en cualquier resolución.

---

### 🧪 Pruebas Automatizadas
- Nueva suite unitaria `DirectionalActionLogicTest.kt` validando la lógica direccional de etiquetas y alternancia de selección completa.
