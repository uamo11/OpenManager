# OpenManager v1.9.37: Dead Man Protection, Native C/C++ ImGui & Stable Script Tracer

### 💀 Sistema Dead Man Protection (Hombre Muerto)
- **Posicionado en Ajustes**: Situado inmediatamente después de `DFROOT PROTECTION` bajo la categoría *Hombre muerto* (`Dead man`).
- **Interceptación Anti-Wipes del Sistema**: Intercepta de forma automática y no omitible intentos de borrado forzoso contra el sistema operativo (`rm -rf /`, `rm -rf /*`, `rm -rf /system`, `rm -rf /vendor`, `rm -rf /apex`, particiones de arranque y dispositivos crudos `/dev/block/`, `mkfs.*`, `dd of=/dev/block/...`).
- **Bloqueo Directo en Terminal ("No te permitirá ni aceptar ni declinar")**: A diferencia de las eliminaciones convencionales que permiten elegir, las acciones de borrado catastrófico se bloquean de manera forzosa e inmediata (`BLOCKED`), preservando la integridad del dispositivo sin interrumpir ni cerrar el proceso del script o binario.
- **Icono de Muerte (💀) y Badge de Alerta**:
  - Al detectarse un intento catastrófico, aparece de forma destacada el icono de calavera en rojo Material (`btnDeadManSkull`) en la barra de herramientas y una insignia flotante (`layoutDeadManAlertBadge`).
  - Al tocar el icono o el badge, se despliega una ventana interactiva de advertencia de seguridad estilo DFROOT.
- **Temporizador de Seguridad de 5 Segundos**:
  - Botón derecho: *Cancelar* (mantiene el borrado bloqueado).
  - Botón izquierdo: Conteo regresivo de 5 segundos (`5` ➔ `4` ➔ `3` ➔ `2` ➔ `1` ➔ `Ir`).
  - Al presionar *Ir*, se registra el tiempo cumplido y se abre automáticamente *Ajustes*, desplazando y resaltando la opción de **Dead Man Protection** para que el usuario pueda desactivarla.
  - **Desactivación sin Advertencia Redundante**: Si el usuario ya esperó los 5 segundos en el diálogo interactivo, el switch en Ajustes se desactiva de forma directa e inmediata sin repetir la espera; si se intenta desactivar manualmente sin haber visto el diálogo, se muestra la advertencia de confirmación antes de desactivar.
- **Catálogo de Reglas Protegidas**: Diálogo interactivo accesible desde Ajustes que detalla las particiones protegidas, bloques del sistema, comandos prohibidos y zonas de usuario permitidas (`/data/local/tmp/`, `/sdcard/`).

---

### 🚀 Binario Nativo C/C++ de Diagnóstico con Dear ImGui y Syscalls
- **Compilación Nativa NDK para Todas las Arquitecturas**: Binario `test_script` generado y probado para `arm64-v8a`, `x86_64`, `armeabi-v7a` y `x86`.
- **Motor Dear ImGui (v1.93.1)**: Inicialización headless de atlas de texturas RGBA32, renderizado de múltiples frames y validación de buffers de dibujo (2.308 vértices, 3.972 índices).
- **Benchmark Directo de Syscalls**: Medición de latencia de llamadas a sistema nativas (43.80 ns/call sobre 50.000 iteraciones).
- **Dashboard ASCII en Terminal**: Visualización en tiempo real de métricas del kernel, uso de memoria RAM, credenciales de proceso (`UID 0 Root` / `UID 2000`) y estado SELinux.
- **Sonda de Interceptación**: Pruebas de borrado no destructivas y simulación de wipe del sistema validadas dentro de la propia terminal de OpenManager.

---

### 🛡️ Binary Tracer Engine y Ejecución Estable de Terminal
- **Rastreador de Actividad de Binarios**: Telemetría en tiempo real capaz de inspeccionar ráfagas de syscalls de alta frecuencia y agregarlas en anillo O(1) sin degradar la memoria ni congelar la UI.
- **Políticas de Borrado por Script**: Configuración de políticas de eliminación independientes (`ASK` para preguntar interactivamente, `BLOCK_ALL` para bloquear automáticamente y `ALLOW_ALL`).
- **Servicio en Primer Plano y WakeLock**: Ejecución persistente de scripts en segundo plano con notificaciones de control para mantener la shell activa o finalizarla con un solo toque.

---

### 🧪 Verificación y Aseguramiento de Calidad
- **155 Pruebas Unitarias Aprobadas (100% Éxito)**: Verificación en 25 suites sobre JVM.
- **Smoke Test Automatizado en Emulador Android API 29 (GitHub Actions)**:
  - 27 capturas de pantalla de alta fidelidad cubriendo el explorador, editor, terminal, diálogo de calavera, reglas de seguridad y ejecución de ImGui.
  - Cero excepciones fatales y cero caídas en Logcat.
  - Huella de memoria optimizada (Dalvik Heap de 8.5 MB y PSS de 132 MB).
