# TestCompiledScript – OpenManager Native C / C++ & Dear ImGui Test Suite

Módulo de pruebas nativas compiladas en C y C++ para Android utilizando Android NDK y la librería gráfica **Dear ImGui**.

## Contenido y Arquitectura

1. **Módulo en C (`src/syscall_probe.c` & `src/syscall_probe.h`)**:
   - Invocación directa de llamadas al sistema Linux (`syscall(__NR_...)`):
     - `getpid` / `getppid`: Identificadores de proceso y padre.
     - `getuid` / `geteuid` / `getgid` / `getegid`: Credenciales del proceso.
     - `uname`: Versión del kernel, nombre del sistema y arquitectura de máquina.
     - `sysinfo`: Memoria RAM total/libre, tiempo de actividad (*uptime*) y procesos activos.
   - Detección de privilegios de **Superusuario / Root** (`UID == 0` o `EUID == 0`).
   - Lectura directa de `/proc/version`.
   - Inspección del estado de **SELinux** (`/sys/fs/selinux/enforce`: Enforcing, Permissive o Deshabilitado).
   - Detección de binarios de superusuario (`/system/bin/su`, `/data/adb/ksu/bin/su`, `/data/adb/magisk/su`, `ksud`, etc.).
   - Micro-benchmark de latencia de syscalls directas (medido en nanosegundos por llamada con 50,000 iteraciones).

2. **Módulo en C++ (`src/main.cpp`)**:
   - Integración completa del motor de interfaz **Dear ImGui** (v1.92+) en modo autónomo (*Headless*).
   - Inicialización del contexto ImGui (`ImGui::CreateContext()`) con resolución de pantalla táctil Android (1080x1920) y estilo oscuro.
   - Generación y verificación del atlas de textura de fuentes (`io.Fonts->GetTexDataAsRGBA32`).
   - Construcción de ventanas dinámicas, árboles colapsables, tablas, barras de progreso y controles con los datos telemétricos obtenidos por el módulo C.
   - Renderizado completo (`ImGui::Render()`) e inspección exhaustiva de la estructura de dibujo (`ImDrawData`, `ImDrawList`, vértices, índices y búfer de comandos).
   - Renderizado de un cuadro de mandos visual interactivo en modo texto/ASCII directamente en la terminal.

3. **Arquitecturas Soportadas**:
   - `arm64-v8a` (Dispositivos físicos modernos de 64 bits).
   - `x86_64` (Emuladores Android AVD x86_64).
   - `armeabi-v7a` (Dispositivos físicos de 32 bits).
   - `x86` (Emuladores Android de 32 bits).

## Compilación con NDK

Para compilar en la PC utilizando el NDK instalado:

```powershell
powershell -ExecutionPolicy Bypass -File build.ps1
```

O utilizando CMake:

```bash
mkdir build && cd build
cmake -DCMAKE_TOOLCHAIN_FILE=$ANDROID_NDK/build/cmake/android.toolchain.cmake -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-30 ..
cmake --build .
```

## Ejecución en Emulador o Dispositivo Android

```bash
bash run_emulator_test.sh
```

O manualmente vía ADB:

```bash
adb push bin/x86_64/test_script /data/local/tmp/test_script
adb shell chmod 755 /data/local/tmp/test_script
adb shell /data/local/tmp/test_script
# Para ejecutar con permisos Root:
adb shell su 0 /data/local/tmp/test_script
```
