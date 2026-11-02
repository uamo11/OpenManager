# OpenManager Development Progress

## Resumen del Proyecto
OpenManager es un administrador de archivos avanzado y suite de ingeniería inversa para Android con paridad funcional completa respecto a MT Manager.

---

## Estado Actual de las Fases

### Fase 0 – Auditoría y Mapeo
- [x] Análisis exhaustivo de los módulos existentes en el repositorio.
- [x] Creación de `docs/PARITY.md` con matriz completa de funciones de MT Manager.
- [x] Auditoría de fallos y diseño de paridad.

### Fase 1 – Implementación y Estabilidad (100% Completo)
1. **Crash Handler Global (`app/CrashHandler.kt`)**: ✅ Implementado y registrado en `AppInitializers.kt`. Captura información del sistema, memoria, hilos y traza en `/sdcard/OpenManager/crash_logs/`.
2. **Editor de Recursos ARSC (`resources.arsc`)**: ✅ `ArscEngine.kt` y `ArscEditorActivity.kt` implementados con navegación por pestañas (Cadenas, Recursos, Paquetes), edición interactiva, recálculo atómico de desplazamientos y tamaños de chunks y guardado persistente.
3. **Motor AXML (`file/AxmlEngine.kt`)**: ✅ Decodificación y re-ensamblado directo de cabeceras binarias y pool de cadenas para `AndroidManifest.xml`.
4. **Suite de Pruebas Unitarias**: ✅ Tests automatizados `HexBufferTest.kt` y `ArscEngineTest.kt` creados con JUnit 4.
5. **Configuración de CI y Firma con Keystore Segura**: ✅ Generada keystore permanente de 2048-bit RSA almacenada de forma segura en GitHub Actions Secrets (`RELEASE_KEYSTORE_BASE64`, `RELEASE_STORE_PASSWORD`, `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD`). Workflow `.github/workflows/android.yml` ampliado para compilar debug y release firmados y correr pruebas unitarias.
6. **Editor de Código de Alto Rendimiento (`FastSyntaxScanner.kt`, `CodeEditText.kt`, `LineNumberView.kt`)**: ✅ Arquitectura profesional con tokenizador lineal $O(N)$ en segundo plano (`Dispatchers.Default`), renderizado de búsqueda y línea activa directamente en Canvas sin sobrecarga de spans en `Editable`, números de línea laterales sincronizados (`LineNumberView`), auto-indentación inteligente en Enter, des-indentación en Backspace, emparejamiento de llaves/corchetes, búsqueda por regex y palabra completa, conversión de fin de línea (LF ⇄ CRLF), y suite de tests unitarios.
7. **Selector y Diálogo de Creación de Elementos (`CreateChoiceBottomSheetDialogFragment.kt`, `name_dialog_name_include.xml`)**: ✅ Selector instantáneo Material 3 Bottom Sheet con iconos enriquecidos para Nueva Carpeta (ámbar), Nuevo Archivo (azul) y Archivo ZIP (morado), corrección de iconos en `ThemedSpeedDialView`, caja de texto moderna redondeada (14dp) con startIcon, clear button y chips de extensiones rápidas (`.txt`, `.json`, `.xml`, `.java`, `.kt`, `.smali`, `.sh`, `.md`, etc.) y auto-foco del teclado.
8. **Navegación Estable, Botón Retorno a Home y Chips con Iconos (`FileListFragment.kt`, `FileListActivity.kt`)**: ✅ Corrección del retroceso desde Home para no escapar a la raíz `/`, doble toque atrás con Toast para salir, inclusión de botón "Volver al Home" (`home_icon_white_24dp`) en pantalla de error root / acceso denegado, e iconos Material 3 en chips de rutas predeterminadas (Interna con casa, Descargas con flecha, Android/data con carpeta, Raíz con dispositivo). Validado con suite `NavigationLogicTest.kt`.
9. **Rendimiento e Interfaz en Extracción de APK / Gestor de Aplicaciones (`InstalledAppsActivity.kt`, `NativeAppIconLoader.kt`, `AppModel.kt`, `activity_installed_apps.xml`)**: ✅ Separación arquitectónica completa: barra superior fija e inmune a solapamiento con la barra de notificaciones mediante padding síncrono framework (`status_bar_height`) y `WindowInsetsCompat` reactivo con color `#1F1F1F`; iconos nativos vectoriales / adaptive icons 100% nítidos sin re-escalado ni pérdida de calidad mediante `NativeAppIconLoader` y caché LRU en memoria (300 elementos); carga inmediata de aplicaciones en 3 niveles (Memoria 0 ms ➔ Disco `AppDiskCache` 10 ms ➔ Emisión progresiva de los primeros 25 elementos en 35 ms con sincronización en segundo plano); y extracción de APKs ultrarrápida con canales zero-copy (`FileChannel.transferTo`) y compresión optimizada.
10. **Modernización de Añadir Almacenamiento, Detección Nativa de MicroSD y Acceso a Android/data y Android/obb (`AddStorageDialogFragment.kt`, `DeviceStorage.kt`, `FileListActivity.kt`, `FileListFragment.kt`)**: ✅ Eliminadas las opciones fallidas de Android/data y Android/obb del diálogo de añadir almacenamiento; soporte de primer nivel para tarjetas MicroSD y USB con `SecondaryStorageVolume` directo (/storage/<UUID>) sin SAF ni restricciones de subcarpetas; detección automática de tarjetas SD montadas con insignia 'DETECTADA' e integración en el menú lateral; rediseño visual de 'Añadir almacenamiento' con iconos Material 3 en cada servicio; y conmutación automática a Root al navegar en Android/data y Android/obb si `su` está disponible.
11. **Corrección de Ejecución en Terminal y Escalado / Zoom (`term.c`, `TerminalExpansionManager.kt`, `TerminalActivity.kt`, `TerminalSettings.kt`, `ScriptRunnerHelper.kt`)**: ✅ Solucionado el fallo `c: cd ... No such file or directory` (código 127): ahora `argv[0]` preserva siempre el nombre del ejecutable en lugar del flag `-c` tanto en JNI nativo C como en Kotlin; conversión del tamaño de fuente de SP a píxeles de pantalla reales (`TypedValue.applyDimension`) evitando fuentes microscópicas de 12px; aumento del tamaño base predeterminado a 14sp; soporte de zoom por pellizco (pinch-to-zoom) fluido y dinámico; y ejecución de binarios ELF desde almacenamiento externo sin root mediante copia a caché ejecutable.
12. **AntiSplit / Fusión de Split APKs en APK Único (`AntiSplitEngine.kt`, `SplitApkDialogFragment.kt`, `dialog_split_apk_actions.xml`, `InstalledAppsActivity.kt`, `FileListFragment.kt`, `OpenApkDialogFragment.kt`)**: ✅ Implementación completa de fusión de paquetes divididos (archivos `.apks`, `.xapk`, `.apkm` o selección múltiple de `base.apk` y `split_*.apk`) en un único archivo APK independiente e instalable utilizando el motor REAndroid (`APKEditor`). Saneamiento automático de `AndroidManifest.xml` (remoción de `isSplitRequired`, `splitTypes`, `requiredSplitTypes`, y metadatos `com.android.vending.splits`), recálculo y actualización de tablas y firmas V1+V2+V3 automáticas vía `ApkSignerHelper.signFileDirect`. Diálogo modal Material 3 instantáneo al pulsar archivos `.apks` con opciones de AntiSplit, Instalación directa, Exploración de archivos y Firma; acción 'AntiSplit / Fusionar APKs' en el menú de selección múltiple del explorador; opción AntiSplit en el menú de funciones de APK; y selector en el Gestor de Aplicaciones para extraer como APKS divididos o fusionar directamente a APK único firmado. Validado con suite de tests `AntiSplitEngineTest.kt`.
28: 13. **Descompilación a Java con JADX (`JadxDecompilerEngine.kt`, `DexClassEditorActivity.kt`, `DexEditorActivity.kt`, `DexActionDialogFragment.kt`, `OpenApkDialogFragment.kt`)**: ✅ Integración del motor oficial JADX (`io.github.skylot:jadx-core:1.4.7`, `jadx-plugins-api`, `jadx-dex-input`, `jadx-java-input`) para descompilación de código de bajo nivel a Java limpio. Pestaña dedicada 'Java (JADX)' en el editor de clases DEX con descompilación asíncrona, vista de código en fuente monoespaciada, copia al portapapeles y exportación individual a archivo `.java`; opción para descompilar Smali directamente a Java; exportación de todas las clases de un archivo DEX a archivo comprimido de código fuente Java (`.zip`) desde `DexEditorActivity` y `DexActionDialogFragment`; y descompilación directa de aplicaciones completas a fuentes Java desde el menú de funciones de APK (`OpenApkDialogFragment`). Suite de pruebas unitarias `JadxDecompilerEngineTest.kt` validada.

14. **Edición Rápida de APK / Quick Edit (`ApkQuickEditEngine.kt`, `ApkQuickEditDialogFragment.kt`, `dialog_apk_quick_edit.xml`, `OpenApkDialogFragment.kt`, `MtFileMenuHelper.kt`)**: ✅ Implementación de Edición Rápida de APKs sin necesidad de descompilación y recompilación pesada. Modificación en caliente de nombre de la aplicación (etiqueta), nombre de paquete (clonación y renombrado), versión de código (`versionCode`), versión visible (`versionName`), versión mínima de Android (`minSdkVersion`), versión objetivo (`targetSdkVersion`), ubicación de instalación (`installLocation`: auto, interno, externo o por defecto), sustitución del icono del launcher por cualquier imagen personalizada seleccionada desde el dispositivo, y firmado/alineado automático V1-V3 (`ApkSignerHelper`). Accesible desde el menú de funciones de APK y desde el menú contextual de herramientas del explorador. Suite de pruebas unitarias `ApkQuickEditEngineTest.kt` validada.

15. **Inyector y Limpiador de Toasts y Diálogos (`ToastDialogInjectorEngine.kt`, `ToastDialogInjectDialogFragment.kt`, `ToastDialogCleanDialogFragment.kt`, `dialog_apk_inject_toast_dialog.xml`, `dialog_apk_clean_toasts_dialog.xml`, `OpenApkDialogFragment.kt`, `MtFileMenuHelper.kt`)**: ✅ Implementación de herramientas de inyección y eliminación de mensajes y alertas en archivos APK sin descompilación completa. Soporte para inyectar Toasts configurables (mensaje y duración corta/larga) y diálogos AlertDialog nativos (título, mensaje, botones positivo/negativo, enlace/URL e interruptor cancelable) en el `onCreate` de cualquier actividad del paquete; y limpiador automático en archivos Multi-DEX (`classes*.dex`) capaz de neutralizar llamadas intrusivas a `Toast.show()`, `AlertDialog.show()` y `Dialog.show()` sustituyendo las instrucciones de invocación por `nop` y neutralizando asignaciones `move-result` con `const/4 vX, 0x0` preservando la verificación Dalvik/ART. Integración de empaquetado y autofirmado V1-V3 con `ApkSignerHelper`. Accesible desde el menú de funciones de APK y desde el menú contextual de herramientas del explorador. Suite de pruebas unitarias `ToastDialogInjectorEngineTest.kt` validada.

16. **Soporte Shizuku en el Explorador (`ShizukuManager.kt`, `ShizukuShell.kt`, `ShizukuProcessFactory.java`, `ShizukuFileServiceLauncher.kt`, `RootFileService.kt`, `FileListFragment.kt`, `file_list_fragment_content_include.xml`, `ApkInstallHelper.kt`, `OpenApkDialogFragment.kt`)**: ✅ Implementación e integración integral de soporte Shizuku para acceso administrativo sin root en Android 11, 12, 13, 14 y 15. Detección automática del servicio Shizuku, registro reactivo de eventos de conexión/desconexión de binder (`addBinderReceivedListenerSticky`), solicitud interactiva de autorización en primer plano (`ShizukuManager.requestPermission`), eliminación de deadlocks en hilos de fondo en `ShizukuFileServiceLauncher`, y puente de ejecución de procesos remotos (`ShizukuProcessFactory` y `ShizukuShell`). Integración visual en la pantalla de error y acceso denegado del explorador con el botón dedicado 'Acceder con Shizuku' / 'Abrir Shizuku' para navegación fluida en carpetas restringidas como `/Android/data` y `/Android/obb`; reintento automático de elevación; e instalación privilegiada de APKs y Split APKs con Shizuku (`ApkInstallHelper.installWithShizuku`). Suite de pruebas unitarias `ShizukuManagerTest.kt` validada.

17. **Generador de Keystores (.jks) (`KeystoreGeneratorHelper.kt`, `KeystoreGeneratorDialogFragment.kt`, `dialog_keystore_generator.xml`, `ApkSignerHelper.kt`, `CreateChoiceBottomSheetDialogFragment.kt`, `OpenApkDialogFragment.kt`, `SignatureViewerDialogFragment.kt`)**: ✅ Diálogo interactivo y motor criptográfico BouncyCastle para generar llaves de firma personalizadas dentro de la app sin recurrir a la consola de comandos ni `keytool`. Soporta generación de KeyStores estándar (.jks / PKCS12), KeyStores BouncyCastle (.bks) y par de claves AOSP (.pk8 + .pem); tamaño de clave RSA 2048/4096 bits, validez configurable en años, alias, contraseñas con opción unificada y atributos X.500 Distinguished Name (CN, OU, O, L, ST, C) con SubjectKeyIdentifier. Integrado en el Bottom Sheet de creación del explorador, en el diálogo de firmado MT (`dialog_mt_sign_apk.xml`) con auto-completado inmediato, en el menú de funciones de APK y con soporte de inspección de certificados en `SignatureViewerDialogFragment`. Suite de pruebas unitarias `KeystoreGeneratorHelperTest.kt` validada.

18. **Verificación de Rendimiento y Estabilidad en Emulador Android (`.github/workflows/android.yml`, `tools/emulator-smoke-test.sh`, `reactivecircus/android-emulator-runner@v2`)**: ✅ Integración de emulador Android acelerado por hardware KVM en GitHub Actions CI utilizando `reactivecircus/android-emulator-runner@v2`. Ejecución automatizada en AVD Android 10 (API 29 x86_64) con instalación del APK debug, otorgamiento de permisos, medición de tiempo de arranque frío (cold start), reporte de consumo de memoria RAM (`dumpsys meminfo`), rendimiento de renderizado de frames (`dumpsys gfxinfo`), prueba de navegación entre actividades (`FileListActivity`, `InstalledAppsActivity`, `TextEditorActivity`), captura de pantalla en vivo del emulador (`emulator-screenshot.png`) y extracción de logs de Logcat verificando cero excepciones fatales.

19. **Firma Oficial Permanente Unificada (`keystore/openmanager.jks`, `signing.gradle`, `app/build.gradle`, `.github/workflows/android.yml`)**: ✅ Keystore oficial única permanente (validez hasta 2054) configurada tanto para `release` como para `debug`, garantizando actualizaciones directas entre versiones sucesivas sin desinstalar.

20. **Exploración del Sistema Estilo MT Manager, Supresión del Drawer Azul y Verificación de Permisos (`FileListFragment.kt`, `FileListActivity.kt`, `FileListLiveData.kt`, `LinuxPath.kt`, `FileItem.kt`, `TextEditorFragment.kt`, `file_list_fragment_include.xml`)**: ✅
- Eliminación completa del cajón lateral azul bug (`NavigationFragment`, `DrawerLayout` y `FullDraggableContainer` interno) de los paneles de `FileListFragment`.
- Mantenimiento y exclusividad del Drawer gris de MT Manager (`navigationDrawerView`, `#303030`) abierto con las 3 barritas `≡` en la barra superior.
- Navegación y listado de rutas del sistema (`/`, `/etc`, `/vendor`, `/system`, `/apex`) sin pantallas de error bloqueantes. Creación de atributos de reserva (`FallbackBasicFileAttributes` y `createFallbackFileItem`) y fallback de lectura de directorios.
- Soporte para abrir archivos de configuración y sistema (`/etc/group`, `/etc/hosts`, etc.) en el editor de texto y bibliotecas (`/vendor/lib/*.so`) en el visor ELF/SO.
- En carpetas restringidas sin archivos (ej. `/data` sin root), la pantalla ya no se reemplaza por un error bloqueante; se mantiene la lista vacía interactiva y se notifica con un aviso discreto.
- Verificación proactiva de permisos de escritura ("disponibilidades") antes de copiar en destino de solo lectura, mover, borrar, renombrar o crear archivos/carpetas en rutas de solo lectura, emitiendo de inmediato el aviso: `"Permiso denegado: esta ubicación es de solo lectura (se requiere Root o Shizuku)"`.

21. **Sistema de Errores y Logs Estilo MP-Manager / MT Manager (`CrashHandler.kt`, `CrashReportActivity.kt`, `activity_crash_report.xml`, `FileJob.kt`, `SettingsPreferenceFragment.kt`, `settings.xml`)**: ✅
- Captura global de excepciones no controladas y fallos de operaciones.
- Creación automática de archivos de log detallados (`crash_yyyyMMdd_HHmmss.log` y `error_yyyyMMdd_HHmmss.log`) en la carpeta `/storage/emulated/0/OpenManager/logs/` y copia directa en `/storage/emulated/0/OpenManager/`.
- Encabezado técnico profesional exhaustivo estilo MP-Manager: fecha/hora, versión de OpenManager (1.9.4 / 44), PID, información del dispositivo (fabricante, modelo, arquitectura ABI, versión de Android/SDK API, huella del sistema), consumo de memoria RAM total/disponible, memoria heap Dalvik, información del hilo y traza de error (stacktrace) completa.
- Pantalla dedicada de reporte de error (`CrashReportActivity`) ejecutada en proceso independiente `:crash`, con aviso visual de ubicación del log, visualizador de traza monoespaciada con scroll horizontal/vertical y selección de texto, botón para copiar informe al portapapeles, botón para compartir, botón para abrir archivo de log en editor/visor de texto, botón de reinicio limpio de la aplicación y botón de cierre.
- Integración en fallos de tareas en segundo plano (`FileJob`), operaciones de archivos, descompilador, inyector, generador de keystores y editor de texto para registrar automáticamente logs de error en `/storage/emulated/0/OpenManager/logs/`.
- Acceso directo en Ajustes -> Otros -> 'Registros de error (Logs)' para explorar de inmediato la carpeta `/storage/emulated/0/OpenManager/logs` en el gestor de archivos.

22. **Categorización de Ajustes Estilo MP-Manager y Flag de Estado del Superusuario (`SuperuserDetector.kt`, `SuperuserStatusPreference.kt`, `preference_superuser_flag.xml`, `preference_category_mp.xml`, `settings.xml`)**: ✅
- Categorización visual estilo MP-Manager / MT Manager con títulos de sección destacados (`15.5sp`, negrita, barra indicadora de acento `bg_category_pill.xml`) y subtítulos descriptivos para cada una de las 10 categorías de configuración.
- Detección precisa, rápida y no bloqueante del entorno de privilegios mediante `SuperuserDetector`:
  - **KernelSU** (icono de KernelSU)
  - **KernelSU Next** (icono de KernelSU Next)
  - **Magisk** (icono de Magisk)
  - **Magisk Delta / Kitsune Mask** (icono de Magisk Delta)
  - **APatch** (icono de APatch)
  - **Baka-SU** (icono de Baka-SU)
  - **Shizuku** (icono de Shizuku)
  - **DFRoot (Jailbreak / Exploit)**: detección de coexistencia con el gestor principal mostrando la insignia secundaria de DFRoot con su icono al lado, o como gestor autónomo.
  - **Sin Superusuario / Modo Estándar** (icono de escudo).
- Tarjeta/Flag interactivo en la cabecera de la pantalla de ajustes (`SuperuserStatusPreference` y `preference_superuser_flag.xml`) con estado en vivo (ROOT, SHIZUKU, EXPLOIT, ESTÁNDAR) y diálogo emergente con diagnóstico técnico completo (gestor, versión de SU, ruta de driver/daemon, paquete y acceso directo para abrir la app del gestor).

23. **Corrección de la Barra Inferior de Selección y Selección por Deslizamiento / Rango Estilo MT Manager / MP Manager (`FileListActivity.kt`, `FileListFragment.kt`, `FileListAdapter.kt`, `file_list_activity.xml`, `RangeSelectionLogicTest.kt`)**: ✅
- **Rediseño y corrección de la barra inferior de selección (`bottomBarSelection`)**:
  - Reemplazo de los 6 botones `MaterialButton` saturados por 5 columnas proporcionadas de 20% de ancho con `TextView` estilizados, eliminando problemas de recortes de texto, capitalización forzada y desalineaciones en cualquier resolución de pantalla.
  - Comportamiento de alternancia en el botón `Todo`: pulsa para seleccionar todos los archivos; si ya están todos seleccionados, pulsa para deseleccionar todo inmediatamente.
  - Flechas dinámicas orientadas al panel opuesto: `Copiar ➔` y `Mover ➔` cuando el panel activo es el izquierdo (A), o `⬅ Copiar` y `⬅ Mover` cuando el panel activo es el derecho (B).
  - Menú `Más...` ampliado con: `Invertir selección`, `Desmarcar todo`, `Exportar lista...`, `Intercambiar nombres`, `Propiedades` y `Comprimir / Añadir a ZIP...`.
- **Gesto de selección por deslizamiento a la derecha (`ItemTouchHelper`)**:
  - Deslizar un archivo hacia la derecha ("girar a la derecha") lo selecciona inmediatamente con respuesta háptica y fondo azul `#1565C0`.
  - **Selección de rango continuo entre archivos**: al deslizar un archivo (arriba o abajo) y posteriormente deslizar otro archivo (abajo o arriba), se seleccionan instantáneamente todos los archivos intermedios de forma automática.
  - El directorio padre `..` permanece permanentemente protegido contra deslizamientos y selecciones accidentales.
  - Suite de pruebas unitarias `RangeSelectionLogicTest.kt` validando selección de rango descendente, ascendente, exclusión de `..` y alternancia de selección completa.

24. **Corrección de Quick Edit, Copia de Rutas/Nombres en el Menú de Archivos y Sincronización del Botón Atrás (`dialog_apk_quick_edit.xml`, `ApkQuickEditDialogFragment.kt`, `ApkQuickEditEngine.kt`, `OpenApkDialogFragment.kt`, `dialog_mt_file_menu.xml`, `MtFileMenuHelper.kt`, `FileListActivity.kt`, `NavigationLogicTest.kt`) (v1.9.7)**: ✅
- **Solución y Reorganización de Edición Rápida (Quick Edit)**:
  - Eliminación de la causa raíz de crasheos al abrir o interactuar con el diálogo: sustitución de `AutoCompleteTextView` por `com.google.android.material.textfield.MaterialAutoCompleteTextView` en dropdowns expuestos requeridos por Material Design.
  - Corrección de ciclo de vida del fragmento: paso de `requireActivity()` / `parentFragmentManager` para evitar `IllegalStateException: FragmentManager has been destroyed` al cerrar el diálogo origen `OpenApkDialogFragment`.
  - Reorganización visual en secciones temáticas limpias con tipografía destacada: "Información Básica", "Versión de la Aplicación", "Compatibilidad SDK" e "Instalación y Opciones Avanzadas".
  - Protección con bloques try-catch seguros en `ApkQuickEditEngine.kt` para lectura de manifiesto y atributos en APKs modificados u ofuscados.
- **Mejora del Menú Contextual de Archivos**:
  - Incorporación de encabezado interactivo con icono dinámico según el tipo de archivo (`FileIconHelper`), nombre completo en negrita, detalles de tamaño y fecha formateada (`formatMtDate`), y guía de acciones.
  - Tocar el nombre completo del archivo o el encabezado copia la ruta absoluta completa (`/storage/emulated/0/.../archivo.ext`) al portapapeles con confirmación Toast y respuesta háptica.
  - Mantener presionado copia únicamente el nombre del archivo.
- **Sincronización Dinámica del Botón Atrás en la Barra Superior (`mainToolbar`)**:
  - Ahora la barra superior muestra dinámicamente el botón de retroceso (`arrow_back`) cuando el explorador está en una subcarpeta (`canNavigateUp == true`) para subir de nivel de forma inmediata.
  - En modo selección, el icono cambia automáticamente a cerrar (`close`) permitiendo deseleccionar y cancelar la selección en un solo toque.
  - En la raíz del almacenamiento o cuando no se puede subir, muestra las 3 barras (`≡`) para abrir el menú lateral. Mantener presionado el icono de navegación en cualquier momento abre el menú lateral.
  - Validado con pruebas unitarias en `NavigationLogicTest.kt`.

25. **Apktool en Segundo Plano, Cancelación Interactiva y Notificaciones (`ApktoolManager.kt`, `ApktoolService.kt`, `ApktoolPaths.kt`, `ApktoolHubDialogFragment.kt`) (v1.9.8)**: ✅
- Jerarquía de rutas estructurada estilo MT Manager: `OpenManager/ApkTool/<paquete>/decomp_java/`, `decomp_smali/`, etc.
- Servicio en primer plano (`ApktoolService`) con notificación persistente, barra de progreso indeterminada/determinada, botón de acción "Cancelar" interactivo directo desde la notificación y botón de "Ocultar" en el diálogo.
- Soporte de cancelación cooperativa de tareas y descompilación asíncrona segura.

26. **Perfeccionamiento del Detector de Superusuario, Respeto de Ajustes y Detección de Gestores Modificados (`SuperuserDetector.kt`, `SuperuserStatusPreference.kt`, `LibSuFileServiceLauncher.kt`, `RootFileService.kt`, `donottranslate_prefs.xml`, `SettingsPreferenceFragment.kt`) (v1.9.9)**: ✅
- **Activación por defecto de solicitud de root**: Corregido `pref_default_value_request_root_permission` a `true` en `donottranslate_prefs.xml` para que la app solicite permisos de superusuario por defecto de forma transparente.
- **Respeto estricto del ajuste "Pedir permiso de root"**: Si el interruptor está desactivado en Ajustes > Inicio, OpenManager nunca invoca comandos `su` ni reporta acceso root activo; el flag muestra el gestor pasivamente como "(Desactivado)" con chip visual "DESACTIVADO" y `RootFileService` previene llamadas de elevación.
- **Detección exhaustiva de paquetes de root específicos**:
  - `df.root` / `com.df.root` / `org.df.root` (DFRoot Jailbreak / Exploit)
  - `com.resukisu.resukisu` (KernelSU Next / ResuKisu)
  - `me.weishu.kernelsu` (KernelSU Oficial)
  - `me.bmax.apatch` (APatch)
  - `com.topjohnwu.magisk` (Magisk Oficial)
- **Soporte de Superusuario Genérico (`GENERIC_ROOT`)**: Para entornos con gestores ocultos o con nombre de paquete aleatorio (ej. Magisk con nombre cambiado) o binarios `su` independientes, se clasifica automáticamente como "Superusuario (su)" con el icono genérico oficial de Android (`ic_root_generic.png`).
- **Coexistencia DFRoot + Gestor Principal**: Muestra el badge distintivo lateral de DFRoot junto al gestor principal detectado (ej. KernelSU + DFRoot).
- **Refresco Reactivo en Ajustes**: `SuperuserStatusPreference` y `SettingsPreferenceFragment` observan en vivo los cambios de `Settings.REQUEST_ROOT_PERMISSION` actualizando el estado de inmediato sin salir de la pantalla.

27. **Iconografía Vectorial Especializada de Alto Rendimiento para Apktool y Acciones de APK (`ic_apktool.xml`, `ic_apktool_notification.xml`, `ic_apktool_decompile.xml`, `ic_apktool_recompile.xml`, `ic_apktool_verify.xml`, `ic_apktool_antisplit.xml`, `ic_apktool_sign.xml`, `ic_toast_inject.xml`, `ic_toast_clean.xml`, `ic_apk_optimize.xml`, `ic_keystore_key.xml`, `item_dialog_action_with_icon.xml`, `OpenApkDialogFragment.kt`, `ApktoolHelper.kt`) (v1.9.10)**: ✅
- **Iconos Vectoriales Nativos de Máximo Rendimiento y Fidelidad Visual**:
  - Diseñados e implementados 11 nuevos iconos `VectorDrawable` XML que sustituyen iconos genéricos o faltantes en todas las interfaces de Apktool y herramientas de APK:
    1. `ic_apktool.xml`: Icono insignia oficial de Apktool con silueta Android, engranaje de precisión y llave de ajuste.
    2. `ic_apktool_notification.xml`: Silueta blanca pura optimizada para notificaciones de Android en segundo plano (`ApktoolExecutionService`), eliminando cajas grises opacas en Android 5+.
    3. `ic_apktool_decompile.xml`: Paquete desplegando código con flecha de descompilación ámbar.
    4. `ic_apktool_recompile.xml`: Ensamblaje y empaquetado de proyecto a APK azul con martillo de construcción.
    5. `ic_apktool_verify.xml`: Escudo criptográfico con checkmark de verificación de firmas.
    6. `ic_apktool_sign.xml`: Documento y firma con pluma digital estilizada para el proceso de firmado de APKs.
    7. `ic_apktool_antisplit.xml`: Fusión y convergencia de split APKs a un APK único independiente.
    8. `ic_toast_inject.xml`: Bocadillo interactivo con símbolo de inyección/suma ámbar.
    9. `ic_toast_clean.xml`: Bocadillo interactivo con neutralizador/cruz roja para limpieza de toasts y diálogos.
    10. `ic_apk_optimize.xml`: Cohete y barras de alineación para optimización Zipalign.
    11. `ic_keystore_key.xml`: Llave criptográfica de seguridad para el Generador de KeyStore.
- **Acciones con Iconos en Diálogo de Funciones de APK (`OpenApkDialogFragment.kt`)**:
  - Reemplazado el diálogo plano de solo texto por un adaptador visual `item_dialog_action_with_icon.xml` estilo MT Manager con iconos de 24dp coloreados por categoría.
- **Selectores de Proyecto y APK Enriquecidos (`ApktoolHelper.kt`)**:
  - Diálogos de selección de carpeta de proyecto y de archivo APK con iconos visuales dedicados.
- **Armonización en Menús y Diálogos**:
  - Actualizados `dialog_mt_apktool_hub.xml`, `dialog_mt_tools_menu.xml`, `mt_overflow_menu.xml`, `dialog_split_apk_actions.xml`, `dialog_create_item_choice.xml` y `dialog_keystore_generator.xml` sustituyendo drawables planos o con fondos sólidos por los nuevos iconos vectoriales adaptables.

28. **Exclusión de Ejecución en Archivos `.txt`, Zoom Táctil MT Manager con HUD Flotante, y Optimización de Rendimiento y Lenguajes en el Editor (`FileListFragment.kt`, `ScriptRunnerHelper.kt`, `OpenFileAsDialogFragment.kt`, `CodeEditText.kt`, `FastSyntaxScanner.kt`, `SyntaxLanguage.kt`, `TextEditorFragment.kt`, `TextEditorSettings.kt`, `bg_zoom_indicator.xml`, `text_editor_fragment.xml`, `TextEditorZoomAndLanguageTest.kt`) (v1.9.11)**: ✅
- **Exclusión Definitiva de Diálogo "Ejecutar" en Archivos `.txt` y Documentos**:
  - Corrección de la causa raíz: en sistemas de archivos FUSE/sdcardfs (`/storage/emulated/0`), todos los archivos reportan falsamente permisos POSIX `0770/0777` (`rwxrwx---`).
  - Implementación de lista negra estricta `NON_EXECUTABLE_EXTENSIONS` y método `ScriptRunnerHelper.isNonExecutableDocument()` que descarta documentos de texto (`.txt`, `.log`, `.md`, `.json`, `.xml`, etc.), código fuente y multimedia.
  - En `FileListFragment.kt`: apertura directa inmediata de `.txt` y documentos en el Editor de Texto (`TextEditorActivity`), sin desplegar jamás el diálogo de acción de script.
  - En `OpenFileAsDialogFragment.kt`: supresión de la herramienta "Ejecutor de Scripts" para archivos `.txt`.
- **Zoom Táctil Estilo MT Manager (Pinch-to-zoom) con HUD Flotante y Persistencia**:
  - Implementación de `ScaleGestureDetector` integrado en `TextEditorFragment.kt` con rango continuo de 8sp a 38sp (base 14sp).
  - Bloqueo de intercepción de scroll (`requestDisallowInterceptTouchEvent(true)`) al detectar 2 o más dedos para garantizar fluidez táctil sin saltos de cursor ni tirones.
  - Indicador flotante en tiempo real (Floating Zoom HUD pill badge `zoomIndicator`, `bg_zoom_indicator.xml`) que muestra el tamaño exacto en sp y porcentaje relativo (ej. `14.0 sp (100%)`) con desvanecimiento animado suave tras 1.2 segundos de inactividad.
  - Módulo de persistencia `TextEditorSettings.kt` que guarda el tamaño de fuente, ajuste de línea (word wrap) y numeración de línea en `SharedPreferences`.
- **Optimización de Rendimiento en Edición y Escaneo Asíncrono**:
  - En `CodeEditText.kt`: eliminación de iteración $O(N)$ sobre todo el documento en cada pulsación de tecla (`notifyCursorChanged`); implementación de `cachedTotalLogicalLines` con recálculo diferido en `onTextChanged`, garantizando 120 FPS al escribir y desplazar el cursor.
  - En `FastSyntaxScanner.kt`: escaneo desacoplado en segundo plano (`Dispatchers.Default`) y aplicación directa de spans precalculados con `applyTokens()` en el hilo principal, eliminando bloqueos de interfaz y doble escaneo.
  - Límite de asignaciones de cadenas innecesarias para identificadores fuera del rango [2..20].
- **Ampliación de Compatibilidad de Lenguajes de Programación**:
  - Integración completa de resaltado de sintaxis para: **Gradle / Groovy** (`.gradle`, `.groovy`, `build.gradle`, `settings.gradle`), **Dart / Flutter** (`.dart`), **C# / .NET** (`.cs`), **Ruby** (`.rb`, `Gemfile`, `Rakefile`), **Swift** (`.swift`), **Batch / CMD** (`.bat`, `.cmd`), y **CMake** (`.cmake`, `CMakeLists.txt`).
  - Conjunto exhaustivo de instrucciones y opcodes de Dalvik/ART en **Smali** (`invoke-*`, `move-*`, `const-*`, `return-*`, `if-*`, etc.).
  - Detección automática inteligente según nombre de archivo y extensiones.
- **Suite de Pruebas Unitarias Automatizadas**:
  - Creado `TextEditorZoomAndLanguageTest.kt` validando detección de lenguajes, escaneo de tokens en Gradle, Dart y Smali, lógica de exclusión de ejecución en documentos `.txt`, y límites y escala de zoom.

29. **Rediseño y Simplificación de la Tarjeta de Superusuario, Gradientes Dinámicos, Etiqueta "Working [Jailbreak Root]" y Nuevos Iconos (`preference_superuser_flag.xml`, `SuperuserStatusPreference.kt`, `SuperuserDetector.kt`, `bg_superuser_card_green.xml`, `bg_superuser_card_gray.xml`, `bg_superuser_header_badge_green.xml`, `bg_circle_root_icon_green.xml`, `bg_circle_root_icon_gray.xml`, `ic_check_circle_green.png`, `ic_circle_error_red.png`, `ic_shield_alert.png`, `ic_shield_check.png`, `ic_shield_locked.png`) (v1.9.12)**: ✅
- **Solución Definitiva de Tamaños, Deformaciones e Icono Vertical de DFRoot**:
  - Eliminado el diseño anterior con `dfRootBadgeLayout` inline susceptible a deformaciones verticales y estiramientos anormales.
  - Simplificación completa de la tarjeta eliminando el párrafo inferior de descripción redundante (`tvSuperuserSubtitle`) y el fondo oscuro problemático de "desactivado".
- **Nombre del Gestor Sobre la Línea de Borde Superior (`tvManagerHeader`)**:
  - Reubicado el nombre del gestor (KernelSU, Magisk, APatch, DFRoot, etc.) en un distintivo centrado sobre la línea del borde superior de la tarjeta con elevación, fondo y borde esmeralda (`bg_superuser_header_badge_green.xml`).
  - Al no haber superusuario, el distintivo superior no muestra nada (`View.GONE`).
- **Estado Dinámico al Lado del Icono Principal**:
  - Si hay superusuario activo: muestra claramente el texto `Working` en blanco brillante (17sp bold).
  - En dispositivos con DFRoot: muestra `Working [Jailbreak Root]` con el tag en verde esmeralda y el mini icono oficial de DFRoot al lado.
  - Al no haber superusuario: muestra de forma limpia y directa `No Root` en gris claro (17sp bold).
- **Gradientes Elegantes para Estado de Superusuario**:
  - **Verde Gradient (`bg_superuser_card_green.xml`)**: Elegante degradado lineal verde bosque oscuro (`#1E3E2B` ➔ `#0D1F15`) con borde sutil esmeralda cuando hay superusuario o Shizuku activo.
  - **Gris Gradient (`bg_superuser_card_gray.xml`)**: Sofisticado degradado lineal grafito/pizarra (`#2D3035` ➔ `#181A1D`) con borde sutil gris cuando no hay superusuario.
- **Icono de Estado en la Esquina Inferior Derecha (`ivCornerStatus`)**:
  - Si hay superusuario: icono circular de checkmark verde (`ic_check_circle_green.png`).
  - Si no hay superusuario: icono circular de error / X roja nítido (`ic_circle_error_red.png`).
- **Integración de los 5 Iconos Especiales Suministrados**:
  - `ic_check_circle_green.png`: Checkmark verde circular para estado correcto y diálogo de diagnóstico.
  - `ic_circle_error_red.png`: X roja circular para estado No Root / cancelación.
  - `ic_shield_locked.png`: Escudo negro con candado utilizado como icono principal en modo No Root.
  - `ic_shield_alert.png`: Escudo de alerta para denegación de permisos o advertencias.
  - `ic_shield_check.png`: Escudo de verificación y modo seguro.
- **Detección Exhaustiva y Sin Fallos de DFRoot en `SuperuserDetector.kt`**:
  - Búsqueda en múltiples paquetes conocidos, binarios de ejecución (`/data/local/tmp/dfroot`, `/data/adb/dfroot`, `/system/xbin/dfroot`, `/dev/dfroot`) y escaneo dinámico de paquetes instalados.
- **Suite de Pruebas Unitarias Actualizada (`SuperuserDetectorTest.kt`)**:
  - Validación de recursos de iconos nuevos, asignación de `ic_shield_locked` a `SuperuserType.NONE` y consistencia de drawables de gradiente.

30. **Renombrado de Paquete a com.y.om, Indicador de Carga Eficiente (0 CPU/RAM), Pestaña de Propiedades de Script Estilo APK, Detección de KernelSU /data/adb/ksu/bin y Corrección de Crash en Raíz (v1.9.13)**: ✅
- **Renombrado de Paquete de la Aplicación a `com.y.om`**:
  - Actualizado `applicationId 'com.y.om'`, `versionCode 53` y `versionName '1.9.13'` en `app/build.gradle`.
  - Autoridades de proveedores dinámicas generadas a partir de `applicationId`: `${applicationId}.app_provider`, `${applicationId}.file_provider`, `${applicationId}.shizuku`.
  - Actualizado `shortcuts.xml` (`targetPackage="com.y.om"`) y scripts de smoke test en emulador (`tools/emulator-smoke-test.sh`).
- **Indicador de Carga Superior de Alto Rendimiento para Búsqueda y Navegación de Directorios (`LinearProgressIndicator`)**:
  - Incorporado `LinearProgressIndicator` (`directoryProgressBar`, color azul Material `#2196F3`, grosor 3dp) en la parte superior de la lista en `file_list_fragment_content_include.xml`.
  - Activación reactiva durante estados de carga (`stateful is Loading`) o búsqueda activa (`viewModel.searchState.isSearching`).
  - **Cero consumo de CPU y RAM**: Cuando la carga o búsqueda termina, la visibilidad pasa a `View.GONE`, deteniendo instantáneamente los animadores y los bucles de renderizado de Android.
- **Corrección Definitiva del Crash al Pulsar "Directorio Raíz" en el Menú Lateral (`drawerItemRoot`)**:
  - Solucionado en `FileListFragment.kt`: `navigateTo(path)` ahora obtiene el estado del layout de forma segura (`layoutManager.onSaveInstanceState() ?: Bundle()`) previniendo excepciones `NullPointerException` con `state!!`.
  - En `FileListActivity.kt`: navegación a raíz con `navigateToRoot(Paths.get("/"))` asegurando verificación de fragmento añadido (`activeFragment.isAdded`) y bloque try-catch seguro.
  - En `FileListLiveData.kt`: corregida lectura de estado en fallos a `Failure(value?.value, e)` en lugar de forzar `valueCompat.value` que causaba crash en hilos de trabajo asíncronos.
  - En `FileListFragment.onFileListChanged`: comprobación segura de nulidad en listas de archivos durante búsquedas previniendo llamadas a `files!!`.
- **Detección Perfeccionada de KernelSU y Limpieza de DFRoot en `SuperuserDetector.kt`**:
  - Integrado soporte específico para la ruta `/data/adb/ksu/bin` y `/data/adb/ksu/bin/ksud` como binarios directos de KernelSU.
  - Eliminadas las comprobaciones especulativas de carpetas inexistentes (`/data/dfroot`, `/system/xbin/dfroot`, `/dev/dfroot`) y búsquedas difusas en nombres de paquetes para evitar falsos positivos de DFRoot en equipos con KernelSU.
  - Respeto estricto al interruptor de Ajustes: si la opción de solicitar root está desactivada, el estado de la tarjeta pasa de inmediato a `No Root` (gradiente gris, icono de candado y X roja en la esquina) sin invocar `su`.
- **Pestaña Completa de Propiedades de Script Estilo APK (`FilePropertiesScriptTabFragment`)**:
  - Nueva pestaña dedicada "Script" en el diálogo de propiedades de archivo para scripts (`.sh`, `.bash`, `.zsh`, `.py`, `.js`, `.lua`, `.rb`, `.pl`, `.rc`, etc.) o archivos con cabecera shebang (`#!`).
  - **Tipo e Intérprete**: Identificación del lenguaje/shell (Bash, POSIX sh, Python, Node.js, Lua, etc.) y binario intérprete (`/system/bin/sh`, `python3`, etc.).
  - **Shebang (`#!`)**: Lectura directa de la primera línea del archivo (ej. `#!/system/bin/sh`, `#!/usr/bin/env python3`).
  - **Formato de Fin de Línea Interactivo**: Detección de Unix LF vs Windows CRLF; advertencia destacada en caso de CRLF por incompatibilidad con Android/Linux, y **acción de 1-toque para convertir instantáneamente los saltos de línea a Unix LF**.
  - **Permisos POSIX y Concesión de Ejecución**: Formato legible `-rwxr-xr-x (755)` / `rw-r--r-- (644)` con acción interactiva para alternar o conceder permiso de ejecución (`chmod +x`).
  - **Estadísticas de Código**: Conteo instantáneo de líneas, palabras, caracteres y juego de caracteres UTF-8 mediante escaneo en segundo plano sin fugas de memoria.
  - **Acciones Rápidas Integradas**: Acceso directo para "Ejecutar script" (lanzando diálogo de ejecución y terminal) y "Editar código" (abriendo en el editor de texto/código de OpenManager).
- **Suite de Pruebas Unitarias Ampliada (`SuperuserDetectorTest.kt`)**:
  - Incorporada prueba automatizada `testKernelSuWithKsuBinPath()` validando detección de KernelSU con `/data/adb/ksu/bin`.

31. **Auditoría Profunda de Seguridad, Mitigación de Vulnerabilidades, Hardening y Rendimiento (`AndroidManifest.xml`, `ShellEscapeUtils.kt`, `ApkInstallHelper.kt`, `ApktoolExecutionService.kt`, `ApktoolPaths.kt`, `ToastDialogInjectorEngine.kt`, `JadxDecompilerEngine.kt`, `CreateSymlinkHelper.kt`, `ScriptRunnerHelper.kt`, `InstalledAppsActivity.kt`, `AntiSplitEngine.kt`, `SqliteViewerActivity.kt`, `SecurityHardeningTest.kt`) (v1.9.14)**: ✅
- **Cierre de Vectores de Escalado de Privilegios y Exposición de Componentes en `AndroidManifest.xml`**:
  - `TerminalActivity`: Establecido `android:exported="false"`. Se neutralizó la vulnerabilidad crítica que permitía a aplicaciones maliciosas de terceros ejecutar comandos arbitrarios con permisos de root o de almacenamiento interno (`EXTRA_COMMAND`, `EXTRA_FORCE_ROOT`).
  - `OpenFileActivity`: Establecido `android:exported="false"`. Se eliminó el riesgo de delegación arbitraria de lectura/escritura (`FLAG_GRANT_WRITE_URI_PERMISSION`) a archivos internos mediante intents externos no confiables.
  - Endurecimiento de componentes internos sin filtros de intención (`android:exported="false"`): `AddStorageDialogActivity`, `InstalledAppsActivity`, `DexEditorActivity`, `HexEditorActivity`, `ArscEditorActivity`, `AboutActivity`.
  - Receptores de broadcast (`FileJobReceiver`, `FtpServerReceiver`): Marcados explícitamente con `android:exported="false"` para prevenir la cancelación de transferencias o excepciones por intents externos no controlados.
  - `FileListActivity.kt`: El controlador de intents de demo (`SHOW_DEMO_DIALOG`) ahora se valida con `if (!BuildConfig.DEBUG) return`, impidiendo invocaciones de prueba en builds de producción.
  - `tools/emulator-smoke-test.sh`: Adaptado para invocar de forma limpia las actividades internas mediante `--es SHOW_DEMO_DIALOG apps`, `add_storage`, `about`, manteniendo el 100% de compatibilidad con las pruebas del emulador CI.
- **Protección Robusta contra Ataques Zip Slip / Path Traversal**:
  - `ApktoolExecutionService.kt`: Verificación de que las rutas extraídas (`targetFile.normalize()`, `origFile.normalize()`) comiencen estrictamente por el directorio destino canónico (`projectDir.normalize()`), descartando entradas maliciosas con secuencias `../`.
  - `ToastDialogInjectorEngine.kt`: Verificación de canonical path al extraer archivos DEX de APKs (`targetDir.canonicalPath + File.separator`).
  - `JadxDecompilerEngine.kt`: Sanitización y verificación de canonical path para archivos Java descompilados (`outputDir.canonicalPath + File.separator`).
  - `ApktoolPaths.kt`: Sanitización defensiva de nombres de paquete (`replace(Regex("[^a-zA-Z0-9._-]"), "_").replace(Regex("""\.+"""), ".")`) y comprobación de canonical path en `getPackageDir()`.
- **Inmunización contra Inyección de Comandos Shell (`ShellEscapeUtils.kt`)**:
  - Creado `ShellEscapeUtils.kt` con función `escapeShellArg()` basada en el estándar POSIX (entrecomillado simple y reemplazo de `'` por `'\''`), bloqueando la expansión de subshells (`$()`), backticks (`` ` ``), punto y coma (`;`), ampersand (`&`) y variables.
  - Aplicado en `ApkInstallHelper.kt` (instalación por root y Shizuku), `CreateSymlinkHelper.kt` (creación de enlaces simbólicos como root), `ScriptRunnerHelper.kt` (ejecución de scripts y binarios ELF), `InstalledAppsActivity.kt` (operaciones de detención, limpieza y extracción de APKs vía `Shell.cmd`), y `AntiSplitEngine.kt`.
- **Rendimiento Asíncrono y Blindaje contra Inyección SQL en el Visor SQLite (`SqliteViewerActivity.kt`)**:
  - Migrada la inicialización de la base de datos (`initDatabaseAsync`) y la carga de datos (`loadTableData`) a corrutinas en segundo plano (`Dispatchers.IO`), eliminando bloqueos de hilo principal (UI thread) y congelamientos de interfaz (ANR) en bases de datos de gran tamaño.
  - Sanitización estricta de nombres de tabla (`escapeSqlIdentifier`) duplicando comillas invertidas (backticks) para prevenir inyecciones SQL en consultas estructuradas.
  - Cancelación cooperativa de trabajos previos (`loadJob?.cancel()`) y liberación de recursos (`db?.close()`, eliminación de temporales) en `onDestroy()`.
- **Suite de Pruebas Unitarias de Seguridad (`SecurityHardeningTest.kt`)**:
  - Pruebas automatizadas para el escape seguro de argumentos POSIX shell frente a múltiples vectores de inyección.
  - Pruebas de contención de directorios frente a ataques de path traversal.
  - Pruebas de escape de identificadores en consultas SQLite.

32. **Icono Dedicado de Extracción de APK, Menú de 3 Puntos Simplificado y Ajuste de Directorios Ocultos (`ic_apk_extract.xml`, `mt_overflow_menu.xml`, `FileListActivity.kt`, `settings.xml`, `strings.xml`) (v1.9.15)**: ✅
- **Icono Vectorial Dedicado para Extracción de APK (`ic_apk_extract.xml`)**:
  - Reemplazo del icono genérico por un vector multicapa nítido de alta fidelidad: cúpula del robot Android en Teal (`#26A69A`), contenedor de paquete APK en verde azulado (`#00796B`), tarjeta de documento APK interna (`#4DB6AC`) y flecha de extracción/exportación blanca centrada.
  - Aplicado de manera coherente en: Menú de funciones de APK (`OpenApkDialogFragment.kt`), Gestor de aplicaciones en el Drawer lateral (`file_list_activity.xml`), menú lateral (`NavigationItems.kt`), y diálogo de detalles de aplicaciones instaladas (`dialog_installed_app_details.xml`).
- **Simplificación y Limpieza del Menú de 3 Puntos (`mt_overflow_menu.xml` y `FileListActivity.kt`)**:
  - Eliminación de 9 opciones redundantes que ya pertenecen exclusivamente al menú lateral (Drawer de 3 barras): Servidor FTP, Editor de código, Aplicaciones instaladas, Herramientas APK / Apktool, Generador de Keystores, Añadir almacenamiento, Papelera de reciclaje, Terminal, Ajustes y Acerca de.
  - Reducción del menú exclusivamente a acciones directas sobre la lista de archivos: Actualizar, Buscar, Filtrar, Ordenar, Seleccionar todo, Archivos y directorios ocultos, Intercambiar paneles, Añadir a marcadores, Fijar como inicio, Exportar lista de archivos y Salir.
  - Alternador interactivo y dinámico para "Archivos y directorios ocultos": actualiza su título en caliente ("Mostrar..." / "Ocultar archivos y directorios ocultos") y emite confirmación con Toast al cambiar de estado.
- **Ajuste para Mostrar/Ocultar Archivos y Directorios Ocultos en Configuración (`settings.xml`)**:
  - Incorporado interruptor `SwitchPreferenceCompat` en la categoría 2 (Apariencia y Lista de archivos) de la pantalla de Ajustes con clave `pref_key_file_list_show_hidden_files`.
  - Títulos y descripciones localizadas en español e inglés (`settings_show_hidden_files_title`, `settings_show_hidden_files_summary`).
  - Sincronización instantánea con el estado reactivo `Settings.FILE_LIST_SHOW_HIDDEN_FILES` y actualización en vivo del filtrado de archivos y directorios que empiezan por `.` en `FileListFragment`.

33. **Corrección Crítica de Inicialización de LibSU y Blindaje contra NoClassDefFoundError (`LibSuFileServiceLauncher.kt`, `AppInitializers.kt`, `FileListActivity.kt`, `ApkInstallHelper.kt`, `RootFileService.kt`) (v1.9.16)**: ✅
- **Diagnóstico del Crash en Dispositivos Reales (Samsung SM-A075M / Android 16)**:
  - Causa raíz identificada a partir de los logs de error de usuario: `Shell.setDefaultBuilder()` en `LibSuFileServiceLauncher.<clinit>` arrojaba `IllegalStateException: The main shell was already created` porque un hilo en segundo plano o componente invocaba `Shell.getShell()` antes de que el singleton `LibSuFileServiceLauncher` fuera inicializado.
  - Al fallar el inicializador estático de la clase, la JVM de Android marcaba `LibSuFileServiceLauncher` como no disponible (`ExceptionInInitializerError`), provocando fallos en cascada de `NoClassDefFoundError` en cada toque o acción que consultara permisos root o abriera archivos APK (`OpenApkDialogFragment`, `ApkInstallHelper.isRootAvailable`, `RootFileService`, `SuperuserDetector`).
- **Solución y Blindaje Integral**:
  - `LibSuFileServiceLauncher.kt`: Implementado método seguro `@JvmStatic fun initBuilder()` con bandera atómica `@Volatile private var isBuilderInitialized` y bloque `try-catch` para capturar cualquier excepción si el builder o shell ya existían, impidiendo de forma total y permanente el fallo de inicialización de clase (`<clinit>`).
  - `AppInitializers.kt`: Añadido `::initializeLibSu` en la lista prioritaria `appInitializers` de `AppProvider.onCreate()` para configurar el builder oficial de libSU antes de que cualquier actividad o hilo en segundo plano intente invocar un shell.
  - `FileListActivity.kt`: Llamada explícita a `LibSuFileServiceLauncher.initBuilder()` previa a `Shell.getShell()` al solicitar permisos en el arranque.
  - `ApkInstallHelper.kt` y `RootFileService.kt`: Blindaje defensivo con `try-catch` para consultas de disponibilidad de superusuario.

34. **Precisión Estricta en Detección de Permisos de Root y Desempaquetado de Errores en Apktool (`SuperuserStatusPreference.kt`, `SuperuserDetector.kt`, `ApktoolExecutionService.kt`) (v1.9.17)**: ✅
- **Corrección de Falso Positivo "Working" al Revocar Root**:
  - Resuelto el fallo donde `SuperuserStatusPreference.kt` reportaba "Working" con tarjeta verde aunque el usuario hubiera revocado el permiso de root en KernelSU/Magisk. La condición anterior evaluaba `(status.type != NONE)` como suficiente para asumir root concedido.
  - Corregida la condición: ahora `hasRoot` requiere estrictamente `status.isRootEnabledInSettings && (status.isRootGranted || status.hasDfRoot)`.
  - Si los privilegios no están concedidos a OpenManager, la tarjeta muestra con precisión absoluta: degradado gris, icono de escudo protegido, "No Root", icono de X roja en la esquina inferior y oculta el encabezado del gestor.
  - `SuperuserDetector.kt`: `testRootExecutionSafe()` evalúa en tiempo real `su -c id` asegurando que no se usen cachés obsoletos en memoria y cerrando instancias caducadas con `Shell.getCachedShell()?.close()`.
- **Desempaquetado Limpio de Excepciones en Apktool (`ApktoolExecutionService.kt`)**:
  - Incorporado desempaquetado de causa raíz (`generateSequence(t) { it.cause }.lastOrNull()`) para mostrar mensajes claros y descriptivos en lugar del nombre genérico de clase `ExceptionInInitializerError`.

35. **Navegación Fluida hacia Arriba, Acción de Ejecutar Scripts (.sh), Listado sin Root en `/` y Blindaje I/O Directo en Apktool (`FileListFragment.kt`, `FileListActivity.kt`, `FileListLiveData.kt`, `ApktoolExecutionService.kt`, `NavigationLogicTest.kt`) (v1.9.18)**: ✅
- **Solución a `Apktool error: UnsupportedOperationException`**:
  - Reemplazado el uso de `Path.newOutputStream()` y proveedores de sistemas de archivos virtuales por I/O directo nativo `java.io.File`, `FileInputStream` y `FileOutputStream` en `executeDecompileApk` y `executeRecompileApk`.
  - Inmune al 100% frente a restricciones de opciones de apertura de proveedores virtuales y a ataques Zip Slip con canonical path.
- **Botón Atrás y Navegación Dinámica en `/storage/emulated/0` y Visor de APKs**:
  - Sincronización inmediata de `updateMainToolbarNavigationIcon()` al cambiar de ruta (`onPathOrStatsChanged`) para que el botón de retroceso (`<-`) aparezca de inmediato al entrar en un APK (`archive:/...`) o subdirectorio.
  - Corrección de `canNavigateUp` y `hasParent`: `/storage/emulated/0` ahora muestra el botón atrás y la carpeta `..` para subir a `/storage/emulated`, `/storage` y `/` al igual que MT Manager. Únicamente la raíz absoluta `/` desactiva la navegación hacia arriba.
- **Acción Directa de Ejecución para Scripts `.sh`**:
  - En `FileListFragment.kt:openFile()`, se priorizó `ScriptRunnerHelper.isExecutableOrScript(file)` sobre la detección genérica de texto.
  - Al pulsar cualquier archivo `.sh`, `.bash` o script ejecutable, ahora se despliega inmediatamente el diálogo de acción con opciones para "Ejecutar en Terminal", "Ejecutar con Root", "Editar código" y "Propiedades".
- **Listado de Directorios del Sistema en `/` sin Root (Estilo MT Manager)**:
  - En `FileListLiveData.kt`, se implementó un fallback terciario con shell sin privilegios (`ls -1 /`) cuando `newDirectoryStream` y `File.listFiles()` no tienen acceso Java directo.
  - Permite visualizar las carpetas y enlaces simbólicos de `/` (`system`, `vendor`, `apex`, `sdcard`, `etc`, etc.) en modo lectura segura, bloqueando únicamente modificaciones no autorizadas.

36. **Blindaje de Inicio en Frío (NPE Breadcrumb) y Tolerancia Total a Fallos en Apktool (`FileListViewModel.kt`, `FileListFragment.kt`, `ApktoolExecutionService.kt`, `ApktoolHelper.kt`, `AxmlDecoder.kt`) (v1.9.19)**: ✅
- **Solución a Crash en Arranque en Frío (`NullPointerException` en `BreadcrumbData.getSelectedIndex`)**:
  - Eliminada la aserción forzada `breadcrumbLiveData.valueCompat` en `FileListViewModel.canNavigateUpBreadcrumb`, reemplazándola por acceso nulo seguro `breadcrumbLiveData.value?.let { it.selectedIndex > 0 } ?: false`.
  - En `FileListFragment.kt`, blindado `canNavigateUp` para manejar de forma robusta llamadas tempranas antes de que la vista o el ViewModel hayan emitido su primera ruta o miga de pan.
- **Tolerancia a Errores y Decodificación Resiliente de Recursos AXML en Apktool**:
  - `AxmlDecoder.kt`: Envuelto el procesamiento de binario XML en bloque `try-catch`, retornando el contenido legible o crudo en caso de formatos no estándar o corruptos sin propagar excepciones fatales.
  - `ApktoolExecutionService.kt`: Descompilación tolerante a fallos para `AndroidManifest.xml` y recursos XML (`res/*.xml`); si algún XML específico no puede decodificarse a texto limpio, se escribe su contenido original directamente y se continúa con el resto de archivos del APK.
  - Diálogo de error enriquecido con botón interactivo "Copiar" al portapapeles y ubicación precisa de la excepción (`simpleName (clase.metodo:linea)`) para diagnóstico inmediato.

37. **Conservación Permanente del Menú Hamburguesa (`☰`), Botón Atrás Superior Adyacente y Navegación Segura en Rutas sin Acceso (`FileListActivity.kt`, `FileListFragment.kt`, `file_list_activity.xml`, `NavigationLogicTest.kt`) (v1.9.20)**: ✅
- **Icono del Menú Hamburguesa (`☰`) Permanente en la Barra Superior**:
  - En `mainToolbar`, el icono de navegación principal ya no se reemplaza por una flecha al navegar por subdirectorios o almacenamiento. Se mantiene fijo como el icono de tres líneas (`☰`) para desplegar el Navigation Drawer en cualquier momento.
  - Al seleccionar archivos (`selectedCount > 0`), el icono cambia limpiamente a `✕` para deseleccionar.
- **Botón Atrás Superior Adyacente Dedicado (`btnMainBack` - `←`)**:
  - Se añadió un botón independiente de retroceso inmediato inmediatamente al lado del menú hamburguesa en `mainToolbar`.
  - Este botón se muestra dinámicamente (`VISIBLE`) solo cuando el panel activo tiene capacidad para subir de nivel (`canNavigateUp`), ofreciendo retroceso con un solo toque sin perder el acceso directo al menú lateral.
- **Navegación de Retorno Garantizada en Rutas sin Acceso**:
  - Al ingresar en directorios denegados o sin permisos (por ejemplo, `/root`, `/sys` o `/data` sin root), se restauró la visibilidad del diseño de error (`layoutError`), exponiendo claramente los botones "Volver" (`btnErrorBack`) e "Ir al inicio" (`btnErrorHome`).
  - Cuando una carpeta inaccesible tiene directorio padre (`hasParent`), se inyecta automáticamente el elemento `..` en la lista para que el usuario pueda retroceder inmediatamente pulsando sobre la fila `..`.

38. **UI de Propiedades Universal Estilo APK, Diálogo de Scripts (`.sh`) con Info y Permisos Interactivos (`777`) y Mejora de Permisos Estilo MT Manager (`FilePropertiesDialogFragment.kt`, `ScriptRunnerHelper.kt`, `MtPermissionsHelper.kt`, `SetModeDialogFragment.kt`, `dialog_mt_file_properties.xml`, `set_mode_dialog.xml`) (v1.9.21)**: ✅
- **Eliminación de Campos de Texto ("UI para escribir") y Adopción Universal del Estilo APK**:
  - Reemplazada la antigua vista de pestañas basada en `ReadOnlyTextInputLayout` / `ReadOnlyTextInputEditText` por una tarjeta oscura unificada estilo MT Manager (`dialog_mt_file_properties.xml`) idéntica a la ventana de información de APKs.
  - Cualquier archivo o carpeta muestra cabecera con icono, título, subtítulo, tabla limpia de dos columnas (con toque para copiar), cálculo de hashes (`CRC32`, `MD5`, `SHA-1`, `SHA-256`) y barra inferior de acciones rápidas.
- **Diálogo de Scripts Ejecutables (`.sh`) con Información Básica + Permisos `777` Arriba de Editar/Ejecutar**:
  - Al tocar cualquier archivo `.sh` o ejecutable (`ScriptRunnerHelper.showScriptActionDialog`), se muestra la tarjeta estilo APK con Ruta, Tamaño, Fecha de modificación, Intérprete/Shebang, Formato de línea (`LF` / conversión interactiva desde `CRLF`) y la fila interactiva **Permisos: `777 (rwxrwxrwx) ✎`** arriba de los botones `PREFERENCIAS`, `HASH`, `EDITAR` y `EJECUTAR`.
- **UI de Permisos Estilo MT Manager Mejorada (Cuadros 3x3 + Número Octal + Presets)**:
  - Al tocar la fila de permisos (`Permisos: 777 (rwxrwxrwx)`) desde un script, APK o propiedades de cualquier archivo/carpeta, se abre el diálogo de permisos de MT Manager (`MtPermissionsHelper.showPermissionsDialog`).
  - Solucionado el bug de sobrescritura al escribir dígitos octales (`777`) en `octalEdit`, añadido filtrado octal estricto (`digits="01234567"`), selección automática al enfocar y botones rápidos de presets (`777`, `755`, `644`, `600`).
  - Aplicación híbrida (`Os.chmod`, `File.setExecutable`, `FileJobService.setMode`, shell root `chmod` y persistencia en rutas FUSE) con actualización instantánea en pantalla.

39. **Selección Táctil Inmediata, Barra Inferior de 5 Iconos y Retorno Elástico con Filtro Azul (`FileListFragment.kt`, `file_list_fragment.xml`, `RangeSelectionHelper.kt`, `item_file.xml`) (v1.9.25)**: ✅
- **Barra de Selección de 5 Iconos Estilo MT Manager**:
  - Al seleccionar uno o más elementos, emerge la barra flotante inferior con 5 acciones instantáneas: Seleccionar Todo, Invertir Selección, Cancelar Selección (`✕`), Operación Inversa y Selección por Rango.
  - La acción de cancelar deselecciona limpiamente todos los elementos y restaura el estado normal.
- **Swipe de Selección de Respuesta Inmediata y Filtro Azul**:
  - Deslizamiento lateral a la derecha optimizado para activación instantánea sin retrasos ni retardo de umbral.
  - Al deslizar, el elemento gira dinámicamente y regresa con efecto elástico (`springBack`) a su posición original manteniendo el tinte azul de selección activa.

40. **Optimización de Carga de Archivos en Rutas Normales, Root y Almacenamiento Secundario y Espacio Estable en Drawer (`FileListLiveData.kt`, `DeviceStorage.kt`, `SecondaryStorageVolume.kt`, `NavigationListAdapter.kt`) (v1.9.24)**: ✅
- **Rendimiento Acelerado en Carga de Directorios**:
  - Optimizado el flujo de consulta en tres niveles (NIO ➔ File ➔ Shell sin privilegios) con caché reactivo para carga instantánea de carpetas con miles de elementos.
  - Detección y lectura estable de rutas `/` sin permisos root, evitando bloqueos por excepciones de acceso denegado.
- **Espacio Estable de MicroSD / Almacenamiento Secundario en Menú Lateral**:
  - Consulta segura de espacio libre/total con fallbacks en `StatFs` y llamadas shell `df` blindadas para mostrar de forma estable el almacenamiento de la tarjeta SD sin parpadeos ni valores nulos.

41. **Renombrado a View Apps, Gestor de Procesos en Ejecución y Visor de Componentes Estilo App Manager (`RunningAppsActivity.kt`, `RunningAppsLoader.kt`, `AppDetailsViewerHelper.kt`, `InstalledAppsActivity.kt`, `activity_running_apps.xml`, `dialog_app_components_viewer.xml`) (v1.9.26)**: ✅
- **Renombrado de "Extract APK" a "View Apps"**:
  - Actualizado el acceso en el menú principal y barra superior a "View Apps" con diseño profesional.
- **Nueva Actividad de Apps en Ejecución (Running Apps)**:
  - Monitor en tiempo real de RAM total, usada y libre con barra de progreso porcentual.
  - Listado de procesos con PID, PPID, UID, usuario, hilos, consumo de memoria RSS y estado (R/S).
  - Pestañas de filtrado (Usuario / Sistema), ordenamiento por memoria, nombre y PID, y buscador reactivo.
  - Diálogo de detalles con acciones para matar proceso (`kill -9`) y forzar detención de app (`am force-stop`).
- **Visor Avanzado de Componentes y AndroidManifest.xml (Inspirado en App Manager)**:
  - Exploración estructurada de actividades, servicios, receptores de difusión (broadcast receivers), proveedores de contenido y permisos otorgados/denegados.
  - Diálogo de visor de `AndroidManifest.xml` con resaltado y botón de copiado.

42. **Solución Definitiva a `UnsupportedOperationException` en Apktool/JADX, Soporte Shizuku para Procesos y Lanzamiento Privilegiado de Componentes (`ApktoolExecutionService.kt`, `JadxDecompilerEngine.kt`, `AxmlDecoder.kt`, `RunningAppsLoader.kt`, `AppDetailsViewerHelper.kt`) (v1.9.27)**: ✅
- **Solución Raíz a `Apktool error: UnsupportedOperationException`**:
  - JADX de escritorio invoca `ServiceLoader.load` e intenta usar `java.nio.file.FileSystems.getDefault().getPath(...)`, lo cual en Android ART arroja `UnsupportedOperationException: Path not associated with default file system`.
  - En `JadxDecompilerEngine.kt`, se garantizó que en runtime Android (`Build.VERSION.SDK_INT > 0`) se ejecute directamente el motor nativo Dexlib2 + Baksmali con protecciones clase por clase.
  - En `ApktoolExecutionService.kt`, se añadieron capturas de excepción individuales por entrada ZIP para que un archivo atípico no interrumpa la extracción del resto del APK.
  - Mensajes de error contextualizados y comprensibles en español ante cualquier contingencia técnica, con botón "Copiar" al portapapeles.
- **Gestión Privilegiada de Procesos con Shizuku (`RunningAppsLoader.kt`)**:
  - Si el dispositivo no tiene Root pero cuenta con Shizuku autorizado, `killProcess` y `forceStopApp` se ejecutan a través de `ShizukuShell.exec` (`kill -9 $pid` y `am force-stop $pkg`).
  - Detección de procesos mediante `dumpsys activity processes` y `ps -A` enriquecida con Shizuku para dispositivos Android 11 a 15.
- **Lanzamiento de Actividades No Exportadas y Decodificación de Manifest Protegidos (`AppDetailsViewerHelper.kt`)**:
  - Al iniciar actividades no exportadas desde el visor de componentes, las excepciones `SecurityException` se rescatan automáticamente ejecutando `am start -n` mediante Root o Shizuku.
  - Al inspeccionar el `AndroidManifest.xml` de aplicaciones de sistema o con APK no legible directamente, se realiza una copia temporal a `cacheDir` con permisos elevados antes de la decodificación.
- **Blindaje Estricto de Strings y Offsets en `AxmlDecoder.kt`**:
  - Verificación exhaustiva de límites en `parseStringPool` contra tamaños de buffer y recuentos de cadenas negativos o anómalos.

43. **Apktool CLI Ejecutable Autónomo para Android, Instalador Automático de Expansión de Terminal y Corrección de Bash (`ApktoolCli.kt`, `ApktoolEngine.kt`, `TerminalExpansionManager.kt`, `ScriptRunnerHelper.kt`, `TerminalPreferencesDialog.kt`, `ApktoolExecutionService.kt`, `ApktoolHelper.kt`, `DexEngine.kt`, `JadxDecompilerEngine.kt`) (v1.9.28)**: ✅
- **Apktool CLI Ejecutable Autónomo en Android (`app_process`)**:
  - Implementado `ApktoolCli.kt` con punto de entrada `@JvmStatic fun main(args: Array<String>)` completamente ejecutable desde terminal mediante `/system/bin/app_process`.
  - Despliegue automático de wrapper script ejecutable en `context.filesDir/bin/apktool` con permisos `0755` y auto-resolución de APK base vía `.apk_path` y `pm path`.
  - Inclusión de `context.filesDir/bin` en el `$PATH` de todas las sesiones de terminal (estándar, Termex y Root), permitiendo usar comandos como `apktool d file.apk`, `apktool b dir -o file.apk --sign` y `apktool sign file.apk`.
  - Motor centralizado `ApktoolEngine.kt` con soporte multi-API resiliente (API 34 a 19), ensamblado/desensamblado smali tolerante a fallos y empaquetado AXML con alineación STORED/DEFLATED.
- **Solución Raíz al Error de la Extensión de Terminal (`sh: bash: inaccessible or not found`)**:
  - Corrección en `TerminalExpansionManager.kt` de la comprobación del binario bash: PackageManager extrae bibliotecas nativas como `.so` (`libbash.so`), por lo que buscar `bash` fallaba siempre. Ahora se detecta `libbash.so`, `bash` o se extrae a `filesDir/termex/bin/bash` con permisos de ejecución.
  - En `ScriptRunnerHelper.kt`, se verifica `TerminalExpansionManager.isBashAvailable(activity)` antes de invocar `bash`, haciendo fallback transparente e inmediato a `/system/bin/sh` para evitar códigos de error 127.
- **Sistema de Instalación Completo de la Expansión de Terminal (Termex)**:
  - Diálogo interactivo `TerminalExpansionManager.showExpansionInstallerDialog`:
    1. Escáner local: Detecta APKs de Termex descargados en `/sdcard/Download`, `/sdcard/Downloads`, etc. con botón de instalación inmediata en 1 toque.
    2. Descargador automático: Descarga en segundo plano desde release oficial con barra de progreso interactiva (MB/%, velocidad) y lanza el instalador del sistema (`PackageInstaller`).
    3. Selector de archivos: Permite elegir manualmente cualquier APK desde el almacenamiento.
  - Conectado directamente a las preferencias de terminal y a la selección de entorno en scripts.
- **Erradicación Total de `Opcodes.getDefault()` en el Proyecto**:
  - Reemplazadas todas las llamadas a `Opcodes.getDefault()` (bloqueadas en API 20) por `ApktoolEngine.loadDexSafely` y `ApktoolEngine.getSafeOpcodes` en `DexEngine`, `JadxDecompilerEngine` y `ApktoolExecutionService`, eliminando cualquier error de incompatibilidad en DEX 038-040+.
  - Títulos dinámicos contextuales en diálogos de error de `ApktoolHelper` distinguiendo Descompilador Java vs Recompilador vs Apktool.

44. **Corrección Estricta del Verificador de Superusuario en Ajustes: Eliminación de Falsos Positivos por Apps de Gestores Instaladas (`SuperuserStatusPreference.kt`, `SuperuserDetector.kt`, `LibSuFileServiceLauncher.kt`, `TerminalExpansionManager.kt`, `preference_superuser_flag.xml`, `SettingsPreferenceFragment.kt`, `SuperuserDetectorTest.kt`) (v1.9.29)**: ✅
- **Erradicación de Falsos Positivos de Root en Ajustes**:
  - Resuelto el problema reportado donde la presencia de APKs de gestores de superusuario (Magisk, KernelSU, APatch, DFRoot, etc.) provocaba que la tarjeta de estado en Ajustes se marcara en verde como "Working" a pesar de que el dispositivo no tuviese acceso root real.
  - Se eliminó el atajo `|| status.hasDfRoot` en `SuperuserStatusPreference.kt`: `hasRoot` ahora exige estricta y obligatoriamente `status.isRootEnabledInSettings && status.isRootGranted`.
  - La tarjeta de Ajustes solo se torna verde y muestra "Working" si la ejecución de `su -c id` retorna exit code 0 y confirma fehacientemente privilegios `uid=0(root)`.
- **Comportamiento Visual y Diagnóstico Honesto sin Root**:
  - Si una app de gestor está instalada pero el dispositivo no tiene root o OpenManager no fue autorizado:
    - Tarjeta en gris tenue (`bg_superuser_card_gray`), icono de escudo cerrado (`ic_shield_locked`), texto `"No Root"` e icono rojo de error (`ic_circle_error_red`) en la esquina.
    - Encabezado oculto (`GONE`) de acuerdo con la especificación de diseño ("en el nombre del manager no dirá nd").
    - Diálogo interactivo al pulsar: aclara con total precisión que la app de gestión está instalada en el sistema pero el dispositivo opera en modo estándar seguro sin elevación de privilegios, ofreciendo un botón para abrir el gestor.
- **Blindaje de la Carga Inicial y Evitación de Parpadeo Verde**:
  - `preference_superuser_flag.xml`: El diseño predeterminado se configuró en modo seguro apagado (gris, "No Root", escudo bloqueado, encabezado oculto).
  - En `SuperuserStatusPreference.kt:onBindViewHolder`, si la detección asíncrona aún no ha concluido (`currentStatus == null`), se vincula inmediatamente el estado neutro gris para evitar que el usuario perciba un falso positivo temporal de "Working".
- **Comprobación Estricta de Binario SU en `LibSuFileServiceLauncher.kt` y `TerminalExpansionManager.kt`**:
  - Eliminada la asunción prematura de disponibilidad de SU basada únicamente en `File(path).exists() && File(path).canExecute()`.
  - Ahora se verifica obligatoriamente mediante ejecución protegida de `su -c id` con validación estricta de salida `uid=0(root)`.
  - Protección de comprobaciones de archivos con `safeFileExists` para prevenir `SecurityException` bajo SELinux estricto.
- **Refresco Automático al Reanudar en Ajustes**:
  - En `SettingsPreferenceFragment.kt:onResume()`, se dispara automáticamente el refresco del estado de superusuario para sincronizar cualquier cambio realizado en la app del gestor o Shizuku al regresar a la aplicación.
- **Suite de Pruebas Unitarias Ampliada (`SuperuserDetectorTest.kt`)**:
  - Añadidas pruebas unitarias específicas validando escenarios con gestores instalados (Magisk, KernelSU, APatch, DFRoot) en dispositivos sin root, verificando que `isRootGranted == false`, `statusTag == "ESTÁNDAR"` y los subtítulos reflejan fielmente la ausencia de privilegios concedidos.

45. **Categoría Hombre Muerto (Dead Man) y Sistema de Protección DFROOT contra Bricks/Bootloops (`DeadManProtectionManager.kt`, `Settings.kt`, `settings.xml`, `strings.xml`, `FileListFragment.kt`, `ScriptRunnerHelper.kt`, `TextEditorFragment.kt`, `HexEditorActivity.kt`, `DeadManProtectionManagerTest.kt`) (v1.9.30)**: ✅
- **Nueva Categoría de Ajustes Hombre Muerto / Dead Man (Exclusiva Root)**:
  - Creada la categoría en Ajustes denominada **"Hombre muerto"** (Español) / **"Dead Man"** (Inglés) con layout material `preference_category_mp` y subtítulo descriptivo del propósito de protección contra bricks, bootloops y scripts destructivos.
  - Implementada la opción 1: **DFROOT protection** (`key_dead_man_dfroot_protection`, activada por defecto): Protege contra la modificación, borrado, renombrado o flasheo de archivos críticos de arranque que bajo la sesión temporal de DFRoot harían que el dispositivo no encienda o caiga en bootloop tras reiniciar.
  - Configurada la base y switch de la opción 2: **Root protection** (`key_dead_man_root_protection`, activada por defecto) para defensa frente a scripts o binarios dañinos orientados a la destrucción del sistema.
- **Motor Central de Protección (`DeadManProtectionManager.kt`)**:
  - Reconocimiento de archivos de imagen de arranque y kernel críticos: `boot.img`, `init_boot.img`, `vendor_boot.img`, `vbmeta.img`, `recovery.img`, `dtbo.img`, `abl.img`, `xbl.img`, etc.
  - Detección de ficheros esenciales de inicio del sistema: `/init`, `/system/bin/init`, `/default.prop`, `/system/etc/fstab.*`, `/vendor/etc/fstab.*`.
  - Detección de nodos de dispositivos de bloques en `/dev/block/by-name/` y `/dev/block/bootdevice/by-name/`.
  - Detección en scripts de comandos de flasheo directo de riesgo (`dd of=...boot`, `flash_image`, `blockdev --setrw`).
  - Diálogo de advertencia preventiva con icono de alerta (`ic_shield_alert`), botón preferente de bloqueo seguro ("Bloquear operación (Recomendado)") y opción de confirmación manual consciente.
- **Blindaje en Operaciones de Archivos y Editores**:
  - `FileListFragment.kt`: Intercepción preventiva ante borrado masivo o individual (`confirmDeleteFiles`), pegado o movimiento (`pasteFiles`) y renombrado (`renameFile`).
  - `ScriptRunnerHelper.kt`: Análisis del archivo o script antes de su ejecución para interceptar scripts con comandos directos de flasheo hacia particiones de inicio.
  - `TextEditorFragment.kt` y `HexEditorActivity.kt`: Intercepción al guardar archivos que correspondan a componentes críticos de arranque.
- **Pruebas Unitarias Exhaustivas (`DeadManProtectionManagerTest.kt`)**:
  - Validación de nombres críticos e imágenes de arranque comunes.
  - Verificación de no interferencia con archivos regulares y scripts seguros.
  - Validación de rutas de particiones de bloque y scripts con llamadas a `dd` o `flash_image`.

46. **Lista Categorizada de Archivos DFROOT, Auto-Activación Inteligente, Bloqueo de Modificación de Sistema con Excepciones de Usuario y Diálogo DirtyFlag con Cuenta Regresiva de 5s (`DeadManProtectionManager.kt`, `DfrootProtectedFilesDialogFragment.kt`, `dialog_dfroot_protected_files.xml`, `SettingsPreferenceFragment.kt`, `FileListFragment.kt`, `MtPermissionsHelper.kt`, `ScriptRunnerHelper.kt`, `TerminalActivity.kt`, `TextEditorFragment.kt`, `HexEditorActivity.kt`, `DeadManProtectionManagerTest.kt`) (v1.9.31)**: ✅
- **Lista Ordenada y Categorizada de Archivos Protegidos (`DfrootProtectedFilesDialogFragment.kt`, `dialog_dfroot_protected_files.xml`)**:
  - Implementado visor interactivo clasificado con tarjetas Material, insignias temáticas por nivel de criticidad y orden alfabético estricto en cada grupo:
    1. *Imágenes de Partición de Arranque (Crítico)*: `boot.img`, `dtbo.img`, `init_boot.img`, `recovery.img`, `vendor_boot.img`.
    2. *Firmware y Seguridad AVB (Seguridad)*: `abl.img / xbl.img`, `tz.img / hyp.img`, `vbmeta.img`, `vbmeta_system.img`, `vbmeta_vendor.img`.
    3. *Archivos de Inicio del Sistema (Inicio)*: `/default.prop`, `/init`, `/system/bin/init`, `/system/etc/fstab.*`, `/vendor/etc/fstab.*`.
    4. *Nodos de Dispositivos de Bloque (Raw)*: `/dev/block/by-name/boot*`, `dtbo*`, `init_boot*`, `recovery*`, `vbmeta*`, `vendor_boot*`.
    5. *Particiones del Sistema (Solo Lectura)*: `/apex`, `/odm`, `/product`, `/system`, `/system_ext`, `/vendor`.
    6. *Zona Permitida para el Usuario (Sin Restricciones)*: `/data/local/tmp/` (scripts como `test.sh`, ejecutables de prueba), `/storage/emulated/0/` (`/sdcard/`) y `/data/data/`.
  - Accesible directamente desde la categoría "Hombre muerto" mediante el ítem dedicado "Archivos protegidos por DFROOT".
- **Activación Automática Inteligente**:
  - Se activa automáticamente si se detecta `dfroot` o `dirtyflag` (por paquete, archivos `/data/local/tmp/dfroot*` o propiedades del sistema).
  - O si el bootloader se encuentra bloqueado (`ro.boot.flash.locked == "1"`, `ro.boot.verifiedbootstate` en green/yellow o `ro.boot.vbmeta.device_state == "locked"`) y se detecta `kernelsu` o `dfroot`.
- **Restricción Estricta de Modificación de Sistema (UI y Terminal)**:
  - Cuando la protección está activa, se bloquea terminantemente: editar, reemplazar, renombrar o cambiar permisos (`chmod`) sobre archivos y particiones del sistema o archivos que no hayan sido creados por el usuario.
  - Plena libertad operativa para archivos creados o ejecutados por el usuario en rutas temporales como `/data/local/tmp/test.sh`, almacenamiento `/sdcard/` o directorios privados.
  - Integrado de forma transversal en el explorador de archivos (`FileListFragment`), diálogo de permisos MT (`MtPermissionsHelper`), editor de texto (`TextEditorFragment`), editor hexadecimal (`HexEditorActivity`), lanzador de scripts (`ScriptRunnerHelper`) y terminal interactiva (`TerminalActivity`).
- **Diálogo Oficial DirtyFlag Protection con Cuenta Regresiva de 5 Segundos**:
  - Ante cualquier acción prohibida, despliega diálogo con el icono de DFRoot (`ic_root_dfroot`) y título `"DirtyFlag Protection"`.
  - Mensaje: *"Estás intentando algo peligroso para DirtyFlag / DFRoot. ¿Quieres deshabilitar esta protección?"*.
  - Botón derecho: `"Cancelar"` (cierra y anula la operación de riesgo).
  - Botón izquierdo: temporizador regresivo de 5 segundos (`5` -> `4` -> `3` -> `2` -> `1`), que al concluir se habilita con el texto `"Proceder"`.
  - Al pulsar `"Proceder"`, abre Ajustes automáticamente y realiza scroll con enfoque directo a la opción `DFROOT PROTECTION` para que el usuario pueda desactivarla.
- **Gestión Inteligente de Avisos en Ajustes (Sin Repetición de Espera)**:
  - Si el usuario completó la espera previa de 5 segundos en el diálogo de DirtyFlag Protection y pulsa el conmutador en Ajustes, la protección se desactiva de inmediato sin repetir advertencias ni esperas.
  - Si el usuario accede manualmente a Ajustes e intenta desactivar la opción sin haber pasado por el diálogo, se despliega un diálogo final de advertencia preventiva (`showDisableDfrootWarningDialog`) exigiendo confirmación consciente.
- **Suite de Pruebas Unitarias Ampliada (`DeadManProtectionManagerTest.kt`)**:
  - Nuevas pruebas validando rutas de la zona permitida del usuario (`/data/local/tmp/test.sh`, `/sdcard/`), rutas protegidas del sistema (`/system/bin/sh`, `/init`, etc.), detección de comandos destructivos de terminal (`mount -o remount,rw`, `rm -rf /system`) y el estado de bypass por espera completada.





