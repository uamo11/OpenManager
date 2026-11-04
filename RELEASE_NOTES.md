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
