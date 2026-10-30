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
