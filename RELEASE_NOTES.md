# OpenManager v1.9.57: Atribución Completa de Autoría a Uamo11, Sitio Web uam.lol y Sección Dedicada de Agradecimientos a Hai Zhang (Shangai)

### 👤 Autoría y Desarrollo Exclusivo de OpenManager: Uamo11
- **Atribución Clara y Precisa del Creador**:
  - Se actualizó toda la información de autoría en la aplicación para dejar perfectamente claro que el único autor y desarrollador de OpenManager es **Uamo11**.
  - Enlace directo al sitio web personal del autor: [uam.lol](https://uam.lol), acompañado de un nuevo icono vectorial de globo terráqueo/web de Material Design.
  - Enlace directo al perfil de GitHub del desarrollador: [github.com/uamo11](https://github.com/uamo11).
  - Actualización exhaustiva en los 34 idiomas soportados por la aplicación (`res/values-*/strings.xml`).

### 🤝 Sección Dedicada de Agradecimientos y Proyecto Base (Hai Zhang / Shangai)
- **Reconocimiento Honesto al Proyecto Base**:
  - Incorporada una sección especial titulada *"Agradecimientos y Proyecto Base"* que acredita formalmente a **Hai Zhang (Shangai)** como autor del proyecto base original Material Files.
  - Se explica claramente que OpenManager toma como base el trabajo de Hai Zhang con profundas modificaciones y añadidos (gestor root MT Manager, compatibilidad libarchive, terminal, editor de texto, backup en la nube, APK tools, etc.).
  - Enlaces directos al repositorio original de Material Files (`github.com/zhanghai/MaterialFiles`) y al perfil de Hai Zhang (`github.com/zhanghai`).

### 📱 Rediseño Modular de la Pantalla «Acerca de OpenManager» en Material Cards
- **Estructura Visual Elegante y Limpia**:
  - **Tarjeta 1 (OpenManager)**: Nombre de la app, icono, versión actual, repositorio oficial (`github.com/uamo11/OpenManager`), licencias de código abierto (GPLv3) y política de privacidad.
  - **Tarjeta 2 (Autor de OpenManager)**: Ficha de Uamo11 como único creador de OpenManager con accesos directos a su web (`uam.lol`) y GitHub (`uamo11`).
  - **Tarjeta 3 (Agradecimientos y Proyecto Base)**: Ficha de agradecimientos a Hai Zhang (Shangai) y enlace al repositorio base.
- **Navegación Inteligente desde Ajustes**:
  - Al pulsar la opción *"Agradecimientos"* en el menú de Ajustes, la aplicación abre automáticamente la pantalla y realiza un desplazamiento fluido (`smoothScrollTo`) directamente hasta la tarjeta de Agradecimientos y Proyecto Base.

---

# OpenManager v1.9.56: Detección Dinámica de Root en Caliente (Sin Salir de la App), Corrección Integral de Iconos y Soporte Robusto para DFRoot y Gestores

### 🚀 Detección Dinámica de Superusuario en Caliente (Sin Reiniciar la App)
- **Eliminada la Necesidad de Salir de la App tras Conceder Permisos Root**:
  - Se corrigió el problema por el cual al conceder permisos root (en Magisk, KernelSU, APatch, DFRoot, etc.), era necesario forzar el cierre y volver a abrir la aplicación para que reconociera el acceso root.
  - Implementado sistema de invalidación dinámica de caché (`SuperuserDetector.invalidateCache()`) y reactivación en `FileListActivity.onResume()`.
  - Al regresar a OpenManager tras conceder permisos en el diálogo o app de root, se re-evalúan los privilegios en segundo plano automáticamente, actualizando el estado, estadísticas del drawer y recargando el directorio raíz si estaba abierto con error de permisos.
- **Re-detección Instantánea al Pulsar la Tarjeta en Ajustes**:
  - Al tocar la tarjeta de superusuario en `Ajustes`, se realiza una comprobación forzada en caliente (`detect(forceRefresh = true)`), actualizando el diálogo de detalles al segundo con el estado real.

### 🎨 Corrección Integral de Renderizado y Calidad de Iconos de Root
- **Eliminación del Tintado Gris Parásito (`app:tint`)**:
  - Se corrigió la regla XML en la tarjeta de estado donde `app:tint="#B0BEC5"` teñía de gris todos los iconos de superusuario (KernelSU, Magisk, APatch, DFRoot, Shizuku), destruyendo sus colores originales y contraste.
  - Se configuró la limpieza completa de tintes (`imageTintList = null` y `clearColorFilter()`) en modo activo para que cada icono luzca con sus colores e identidad originales.
- **Aislamiento de Fondos y Transparencias Alpha**:
  - Se eliminaron los fondos sólidos cuadrados (marrones, blancos y fucsias) que causaban bordes toscos en KernelSU, Magisk Delta y APatch dentro del contenedor circular.
  - Se procesaron e integraron versiones con transparencia alfa limpia en `drawable/` y `drawable-nodpi/` para máxima nitidez en pantallas de cualquier densidad (xxhdpi, xxxhdpi).
- **Corrección de Duplicidad de Iconos y Encabezados en DFRoot**:
  - Corregido el bug donde el logo de DFRoot se mostraba duplicado (icono principal + mini icono) y el encabezado repetía `"DFRoot (Jailbreak) + DFRoot"`. Ahora se muestra de forma limpia y elegante.

### 🛡️ Detección Robusta de DFRoot (DirtyFlag Exploit) y Gestores de Kernel
- **Ampliación Exhaustiva de Firmas para DFRoot**:
  - Reconocimiento de nuevas rutas de binarios y scripts de elevación: `/data/local/tmp/dfroot`, `/data/local/tmp/dirtyflag`, `/data/local/tmp/dfroot.sh`, `/data/local/tmp/dirty_flag.sh`, `/data/local/tmp/dfsu`, `/data/local/tmp/rootshell`, `/system/bin/dfroot`.
  - Reconocimiento de propiedades de sistema: `ro.boot.dirtyflag`, `ro.dfroot`, `sys.dfroot`, `persist.dfroot`, `ro.dirtyflag`, `dfroot.version`.
  - Detección de nuevos paquetes del gestor DFRoot / DirtyFlag.
  - Preservación del tipo `DFROOT` con su icono nativo tras la concesión de privilegios root (evitando degradación a genérico).

---

# OpenManager v1.9.55: Elevación Automática de la Barra Inferior del Editor de Texto sobre el Teclado Virtual (Paridad MT Manager)

### ⌨️ Elevación y Sincronización de la Barra de Herramientas y Símbolos con el Teclado
- **Barra de Símbolos sobre el Teclado Virtual (`WindowInsetsCompat.Type.ime()`)**:
  - Se corrigió el problema por el cual la barra inferior de símbolos y navegación (`[◀] [▶] [▲] [▼] [→|] [|←] [TAB] [↶] [↷] [🔍]`) quedaba oculta detrás del teclado al editar archivos en Android 10, 11, 12, 13, 14 y 15.
  - Ahora, al abrir el teclado virtual de Android (IME), la barra inferior sube automáticamente y se posiciona directamente encima del teclado, exactamente igual que en MT Manager.
  - Al cerrar o descartar el teclado, la barra regresa suavemente al borde inferior de la pantalla, adaptándose a la barra de navegación o barra de gestos del sistema.
- **Animación Fluida Sincronizada con el Teclado (`WindowInsetsAnimationCompat`)**:
  - Implementado callback de animación en tiempo real que desliza la barra hacia arriba y hacia abajo acompañando el movimiento del teclado a 60/120 Hz sin saltos visuales.
- **Auto-Ajuste del Área de Edición y Auto-Scroll del Cursor**:
  - El espacio de trabajo del editor se redimensiona reactivamente para mantenerse visible entre la barra superior y la barra de símbolos.
  - Si la posición actual del cursor queda debajo del área visible al desplegarse el teclado, el editor realiza automáticamente un desplazamiento suave (`smoothScrollTo`) para mantener la línea activa a la vista del usuario.
- **Transición Edge-to-Edge en `TextEditorActivity`**:
  - Habilitada la gestión avanzada de ventanas mediante `WindowCompat.setDecorFitsSystemWindows(window, false)` garantizando entrega precisa de eventos de teclado en todos los fabricantes (MIUI, HyperOS, OneUI, Pixel OS, etc.).
- **Suite de Pruebas Unitarias Automatizadas**:
  - Nueva suite `TextEditorKeyboardInsetsTest.kt` validando el cálculo de márgenes, restauración tras descartar teclado y compatibilidad con particularidades de fabricantes OEM.

---

# OpenManager v1.9.54: Rediseño de la Barra Inferior de Selección con Iconos MT Manager y Botón de Cancelación [✕]

### 🎯 Rediseño Estético y Funcional de la Barra de Selección (Paridad MT Manager)
- **Sustitución de Texto Simple por Barra de Iconos Estilizada**:
  - Se sustituyeron los textos planos (`Todo (1)`, `Copiar >>`, `Mover >>`, `Borrar`, `Más...`) por una barra inferior idéntica en diseño y dimensiones a la barra de estado normal (`bottomBarNormal`).
  - Cada acción es ahora un `ImageButton` con efecto ripple sin bordes (`?selectableItemBackgroundBorderless`), tintado blanco nítido y distribución simétrica de 5 columnas.
- **Botón de Cancelación de Selección [✕]**:
  - El primer botón ahora muestra el icono de cierre `[✕]`.
  - **Pulsación corta**: Desmarca todos los elementos y cierra el modo de selección al instante (`activeFragment.clearSelectedFiles()`).
  - **Pulsación larga**: Atajo para seleccionar todos los archivos (`activeFragment.selectAllFiles()`) con aviso toast.
- **Iconos Direccionales e Inteligentes para Copiar y Mover**:
  - **Copiar / Extraer**: Muestra el icono de duplicación/copia o el icono de extracción de caja con flecha (`extract_icon_white_24dp`) cuando se navega dentro de archivos comprimidos.
  - **Mover Direccional**: Muestra la carpeta con flecha apuntando a la derecha (`move_icon_white_24dp`) al estar en el panel izquierdo (A), o apuntando a la izquierda (`move_left_icon_white_24dp`) al estar en el panel derecho (B).
  - Atenuación y deshabilitación reactiva (`alpha = 0.35f`) cuando la operación de mover no es permitida (rutas de sólo lectura o archivos comprimidos).
- **Iconos de Borrado y Menú Contextual con Tooltips**:
  - Icono de papelera para eliminación rápida y 3 puntos verticales (`⋮`) para el menú desplegable.
  - Soporte completo de tooltips nativos (`TooltipCompat.setTooltipText`) al mantener pulsado cualquier icono.
  - Incorporada la opción `"Seleccionar todo"` en el menú desplegable de más opciones (`showMoreSelectionMenu`).
- **Pruebas Unitarias**:
  - Suite `DirectionalActionLogicTest.kt` ampliada para validar la resolución dinámica de iconos según el panel y el tipo de origen.

---

# OpenManager v1.9.53: Sincronización Automática y Detección de Cambios Externos en el Editor de Texto (Paridad MT Manager)

### 🔄 Detección Inteligente de Cambios Externos en el Editor de Texto
- **Aviso en Tiempo Real cuando un Archivo es Modificado Externamente**:
  - Exactamente como en MT Manager, cuando un archivo abierto en el Editor de Texto es modificado en segundo plano en disco (por scripts de consola/terminal, compiladores, comandos Git, descargas o aplicaciones externas), OpenManager detecta el cambio automáticamente y despliega un diálogo de confirmación:
    - **Título**: `"Archivo modificado externamente"`
    - **Opciones claras**: `"Sincronizar"` y `"Conservar mis cambios"` (o `"Cancelar"` si no hay cambios pendientes).
- **Protección de Cambios Locales sin Guardar**:
  - Si el usuario tiene ediciones sin guardar en el editor, el diálogo advierte explícitamente:
    `"El archivo ha sido modificado externamente y tienes cambios locales sin guardar en el editor.\n\nSi sincronizas, se perderán tus modificaciones actuales.\n\n¿Deseas sincronizar de todas formas o conservar tus cambios?"`
  - Evita la pérdida accidental de trabajo en curso.
- **Sincronización Limpia y Preservación de Estado**:
  - Al pulsar `"Sincronizar"`:
    - Se recarga el contenido fresco desde el disco.
    - Se preserva de forma transparente la posición del cursor de texto y el desplazamiento de scroll (`scrollY`), evitando desorientación visual.
    - Se notifica mediante Toast: `"Archivo sincronizado exitosamente"`.
  - Al pulsar `"Conservar mis cambios"` o descartar el diálogo:
    - Se actualiza la marca de tiempo conocida (`acknowledgeExternalChange`), evitando que el cuadro de diálogo vuelva a aparecer de manera molesta mientras el usuario continúa redactando.
- **Doble Mecanismo de Verificación (Al Reanudar y en Segundo Plano)**:
  - Verificación inmediata en `onResume()` al regresar de otra aplicación o pestaña.
  - Observador periódico (`startFileChangeWatcher`) cada 2.5 segundos mientras la pantalla está activa, con cancelación automática en `onPause()` y `onDestroyView()` para garantizar cero impacto en la batería.
- **Supresión de Falsos Positivos durante el Autoguardado (`isSelfSaving`)**:
  - Cuando el usuario pulsa Guardar o la app guarda el archivo, la bandera `isSelfSaving` ignora temporalmente la actualización del timestamp en disco generada por la propia aplicación, actualizando atómicamente los metadatos internos de carga.
- **Suite de Pruebas Unitarias Automatizadas**:
  - `TextEditorFileSynchronizationTest.kt` validando detección de timestamp, variaciones de tamaño, supresión en autoguardado, persistencia tras descartar diálogo y selección adaptativa de textos de confirmación.

---

# OpenManager v1.9.52: Corrección y Blindaje de Archivos Script (.sh) y Binarios dentro de Archivos Comprimidos (Paridad MT Manager)

### 🛡️ Corrección Crítica de Crash al Pulsar Archivos de Script (.sh, .bash, .zsh, etc.) dentro de Archivos Comprimidos
- **Apertura Directa en Editor de Texto (Paridad MT Manager)**:
  - Al explorar dentro de archivos comprimidos (ZIP, APK, TAR, etc.) y pulsar sobre cualquier archivo `.sh`, `.bash`, `.zsh`, `.py`, `.lua`, etc., la aplicación ahora lo abre de inmediato en el Editor de Texto (`TextEditorActivity`), exactamente como lo hace MT Manager.
  - Se eliminó el intento erróneo de enrutar rutas virtuales dentro de archivos hacia el ejecutor de scripts del sistema (`ScriptRunnerHelper.showScriptActionDialog`), el cual causaba caídas al intentar resolver permisos POSIX o invocar streams nativos concurrentes sobre entradas de archivo virtuales no extraídas.
- **Blindaje Total de `ScriptRunnerHelper`**:
  - `isExecutableOrScript(file: FileItem)` y `isExecutableOrScript(path: Path)` retornan estrictamente `false` si la ruta pertenece a un archivo comprimido (`path.isArchivePath == true`).
  - Safe-calls con respaldo ante nombres de archivo nulos (`path.fileName?.toString() ?: ""`) previniendo `NullPointerException`.
  - En `showScriptActionDialogInternal`: Se agregó una guarda inicial que redirige inmediatamente a `TextEditorActivity` si la ruta es un `ArchivePath`, además de envolver toda la inicialización de la interfaz en bloques defensivos `try-catch` con respaldo seguro.
  - En `executeScriptOrBinary`: Se añadió comprobación que advierte amigablemente si se intenta ejecutar directamente un script virtual sin extraerlo primero.
- **Protección de Permisos en Archivos Comprimidos (`MtPermissionsHelper`)**:
  - `MtPermissionsHelper.getPermissions` detecta `path.isArchivePath` y genera información de permisos de sólo lectura simulada (660/755) sin intentar llamar a `Os.lstat` sobre rutas locales ficticias.
  - `showPermissionsDialog` y `applyPermissions` rechazan modificaciones sobre archivos comprimidos informando al usuario que son de sólo lectura.
- **Ampliación de Formatos de Texto y Código en `FileListFragment`**:
  - Se expandió la lista de tipos editables reconocidos directamente para abarcar todos los scripts y lenguajes comunes (`sh`, `bash`, `zsh`, `py`, `lua`, `js`, `ts`, `sql`, `gradle`, `rs`, `go`, etc.) para abrir sin bloqueos ni lecturas síncronas innecesarias en el hilo principal.
- **Suite de Pruebas Unitarias**:
  - Nueva suite `ScriptArchiveHandlingTest.kt` validando la clasificación de documentos no ejecutables, la auditoría segura de scripts y la simulación del enrutamiento de scripts virtuales hacia el editor de texto.

---

# OpenManager v1.9.51: Sistema Inteligente de Actualizaciones In-App, Detección de Canal (Debug / Estable) y Descarga Directa

### 🔄 Sistema de Actualizaciones Integrado y Verificación Automática
- **Interruptor de Verificación Automática en Ajustes (Desactivado por Defecto)**:
  - En la sección **10. Información del Sistema y Registro** de Ajustes, se incorporó la opción `"Buscar actualizaciones automáticamente"` (`key_auto_check_updates`), configurada como **desactivada (`false`) por defecto** según los requerimientos.
  - Al estar activada, la aplicación comprueba en segundo plano al iniciar (`FileListActivity.onCreate`) si existe una versión más reciente en GitHub Releases. Si hay una nueva versión disponible, despliega una ventana emergente informativa con las novedades y el enlace de actualización. Si no hay actualizaciones o no hay conexión, se mantiene en silencio sin interrumpir al usuario.
- **Comprobación Manual con Diálogos Informativos**:
  - Al pulsar `"Buscar actualizaciones"` en Ajustes:
    - Muestra un diálogo de progreso modal: `"Comprobando versiones disponibles en GitHub…"`.
    - Si el usuario ya cuenta con la versión más reciente: Despliega el diálogo `"OpenManager está al día"` informando que se posee la última versión disponible (`v1.9.51`) y mostrando el canal actual del dispositivo.
    - Si se produce un error de red o timeout: Informa amigablemente con opción de `"Reintentar"`.
    - Si existe una actualización: Abre la ventana de actualización completa con las mejoras y opciones de descarga.

### 📱 Detección Inteligente de Canal (Debug vs Release / Estable)
- **Selección Precisa del APK Adecuado para el Celular**:
  - `UpdateChecker` inspecciona el entorno del dispositivo mediante `BuildConfig.DEBUG` y banderas de desarrollo (`ApplicationInfo.FLAG_DEBUGGABLE`).
  - **Canal Debug**: Asigna y descarga automáticamente `OpenManager-vX.Y.Z-debug.apk` con distintivo visual en azul `[Canal: Debug (Desarrollo)]`.
  - **Canal Release / Estable**: Asigna y descarga automáticamente `OpenManager-vX.Y.Z-release.apk` con distintivo visual en verde `[Canal: Release (Estable)]`.
  - Evita inconsistencias de firma o errores de paquete cruzados entre versiones de depuración y producción.

### 📥 Descarga en Tiempo Real e Instalación Directa
- **Diálogo de Descarga con Métricas**:
  - Progreso visual mediante `LinearProgressIndicator` horizontal (`0% - 100%`).
  - Muestra bytes descargados contra el total (`12.5 MB / 34.9 MB (35%)`) y velocidad de transferencia en tiempo real (`3.2 MB/s`).
  - Soporta redirecciones HTTP (301, 302, 307, 308) hacia el almacenamiento de GitHub/Amazon S3.
  - Opción de cancelación limpia eliminando fragmentos temporales (`.apk.tmp`).
  - Guarda los archivos en `/storage/emulated/0/OpenManager/updates/` (o caché interno si el almacenamiento externo no está disponible).
- **Instalación Integrada y Multimétodo**:
  - Al concluir la descarga, inicia automáticamente el instalador:
    - Si el usuario tiene activo Root y privilegios concedidos: Instala silenciosamente vía `pm install` con `ApkInstallHelper.installApkWithRoot`.
    - Si el usuario tiene activo Shizuku y el servicio enlazado: Instala vía `ApkInstallHelper.installApkWithShizuku`.
    - Por defecto o respaldo: Lanza el instalador estándar de Android mediante `FileProvider` (`ACTION_VIEW`, `application/vnd.android.package-archive`, `FLAG_GRANT_READ_URI_PERMISSION`).

### 🧪 Pruebas Unitarias Automatizadas
- Nueva suite `UpdateCheckerLogicTest.kt` validando:
  - Comparación de versiones semánticas (`isVersionNewer`).
  - Selección de APK según el canal (Debug vs Release) y fallback ante APK único.
  - Parseo de respuestas JSON de la API de GitHub Releases.
  - Formateo de bytes y unidades (`B`, `KB`, `MB`, `GB`).
  - Validación de que la comprobación automática está desactivada por defecto (`default == false`).

---

# OpenManager v1.9.50: Integración Completa de la Papelera de Reciclaje, Estado Predeterminado Desactivado y Limpieza Segura

### 🗑️ Integración de la Papelera de Reciclaje en el Menú Lateral y Ajustes
- **Título Limpio en el Menú Lateral ("Papelera de Reciclaje")**:
  - Se eliminó el prefijo numérico `"4. "` en el menú lateral ("Herramientas"), mostrando `"Papelera de Reciclaje"` (en español) / `"Recycle Bin"` (en inglés) de manera limpia y consistente.
- **Estado Inicial Desactivado por Defecto ("Que vendrá off")**:
  - La preferencia `key_enable_recycle_bin` viene configurada en `false` por defecto, respetando la configuración inicial solicitada.
- **Acceso Inteligente desde el Menú Lateral**:
  - Al pulsar sobre `"Papelera de Reciclaje"` en la barra de herramientas lateral:
    - Si está **activa en ajustes**: Navega de inmediato a la carpeta de la papelera (`/storage/emulated/0/OpenManager/.recycle`), asegurando la creación del directorio y el archivo `.nomedia` para que los archivos borrados no aparezcan en la galería multimedia de Android.
    - Si está **desactivada en ajustes**: Muestra un diálogo informativo que permite activarla y abrirla con un solo toque, o ingresar a los Ajustes.
- **Eliminación Definitiva y Prevención de Bucles**:
  - Al eliminar elementos situados dentro de la papelera (`isInsideRecycleBin`), el diálogo cambia automáticamente a `"Eliminar definitivamente"` y ejecuta un borrado permanente e irreversible (`FileJobService.delete`), evitando reubicar archivos sobre sí mismos.
  - Al eliminar elementos fuera de la papelera con la función activa, se presenta la casilla de verificación `[✓] Mover a la papelera de reciclaje`.
- **Acción "Vaciar papelera de reciclaje"**:
  - Se añadió la opción `"Vaciar papelera de reciclaje"` en el menú contextual de 3 puntos (`mt_overflow_menu`), visible únicamente cuando el usuario se encuentra dentro del directorio de la papelera.
- **Limpieza Automática Programada y Pruebas Unitarias**:
  - Ejecución en segundo plano de limpieza de archivos expirados (según el intervalo configurado: nunca, 7 días o 30 días), preservando `.nomedia`.
  - Cobertura completa de pruebas unitarias en `RecycleBinLogicTest.kt`.

### 🗜️ Corrección Definitiva de Exploración de ZIPs en Tarjeta SD y Desintoxicación de Error Xar
- **Eliminación del Formato XAR en `ArchiveSecurityManager`**:
  - Al registrar formatos en libarchive, el formato XAR (no compilado en Android por falta de libxml2) emitía `ARCHIVE_WARN` y envenenaba el búfer de error interno con `"Xar not supported on this platform"`. Cualquier fallo posterior reportaba este mensaje engañoso.
  - Se retiró la llamada a `readSupportFormatXar`, evitando la contaminación del búfer de error nativo.
- **Prevención de Falsos Fin de Archivo (EOF) en Callbacks de Lectura (`ReadArchive.kt`)**:
  - Cuando los callbacks de lectura sobre canales o streams leían 0 bytes transitorios, devolvían un búfer con `remaining() == 0`, lo que libarchive interpretaba como EOF prematuro.
  - Los callbacks ahora leen en bucle hasta obtener al menos 1 byte o `-1` (EOF real).
- **Motor de Respaldo Resiliente con `ZipFile` y `ZipInputStream` de Android**:
  - Si libarchive experimenta problemas de seek o compatibilidad al leer archivos en tarjetas MicroSD o USB, el sistema conmuta automáticamente al motor nativo de Android (`java.util.zip.ZipFile` / `ZipInputStream`), permitiendo explorar archivos ZIP, ROMs, APKs y extraer sus contenidos sin fallo alguno.
- **Corrección de Referencia en Diálogo de Ajustes (`FileListActivity.kt`)**:
  - Se corrigió la referencia `R.string.settings` por `R.string.settings_title`.

---

# OpenManager v1.9.49: Corrección de Exploración de Archivos ZIP/ROM en Tarjeta SD y Aislamiento de Raíz Virtual

### 🗜️ Corrección de Exploración de ZIPs y ROMs desde Tarjeta SD / Almacenamiento Externo
- **Aislamiento de Claves de Caché en `DirectorySnapshotCache`**:
  - Se corrigió la colisión de claves en la caché en memoria LRU (`DirectorySnapshotCache`), donde la ruta raíz interna de cualquier archivo comprimido (`path.toString() == "/"`) compartía la misma clave que el directorio raíz de Linux (`"/"`).
  - Al abrir un archivo ZIP, ROM o APK, la caché devolvía inmediatamente el listado almacenado de `/` (carpetas de sistema `/root`: `acct`, `apex`, `bin`, `data`, etc.) antes o durante la carga del archivo.
  - La clave de caché ahora utiliza el URI completo y único (`path.toUri().toString()`), garantizando independencia absoluta entre el sistema de archivos raíz (`file:///`) y cada archivo comprimido (`archive:////.../archivo.zip?/`).
- **Blindaje Estricto de `UnprivilegedDirectoryResolver`**:
  - Se añadió validación obligatoria al inicio de `UnprivilegedDirectoryResolver.resolveEntriesForPath`: si la ruta corresponde a un archivo comprimido (`isArchivePath`) o no es una ruta Linux (`!isLinuxPath`), devuelve inmediatamente una lista vacía.
  - En `FileListLiveData`, se eliminó cualquier llamada de contingencia a `UnprivilegedDirectoryResolver` o a `java.io.File("/")` cuando la ruta cargada es un `ArchivePath`.
  - Si un archivo comprimido no puede cargarse o está protegido con contraseña, se propaga el error limpio o el diálogo de contraseña en lugar de mostrar falsamente el árbol de carpetas de `/root`.
- **Modo Fallback Resiliente en `ArchiveReader` para ZIPs Grandes y ROMs con Firma OTA**:
  - Al abrir archivos ZIP complejos, paquetes de ROMs de Android (LineageOS, Xiaomi.eu, PixelExperience, etc.) con bloques de firma digital de recovery/OTA al final del archivo central, o canales de almacenamiento secundario con errores de seek, `ArchiveReader.openArchive` ahora captura cualquier excepción en `ReadArchive(channel)` y conmuta fluidamente a lectura secuencial vía `InputStream` desde el byte 0.
  - En `ArchiveReader.readEntries`, si la lectura de entradas falla a través del canal seekable, se realiza un segundo intento automático mediante streaming secuencial antes de fallar.
  - En `ReadArchive.kt`, los callbacks de seek, skip y read protegen contra desplazamientos negativos (`newPosition < 0`) y encapsulan excepciones de canal NIO (`IllegalArgumentException`, etc.) previniendo fugas no controladas a través de la interfaz JNI de libarchive.

---

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
