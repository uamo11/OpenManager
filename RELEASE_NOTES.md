# OpenManager v1.9.40: Terminal Deletion Fix & High-Performance Explorer

### ⚡ Corrección Definitiva: Borrado de Scripts en Terminal (`rm: Permission denied`)
- **Resolución de Conflicto en `$PATH`**:
  - Se corrigió el orden de búsqueda en el `$PATH` del terminal (`$EXTENDED_SYSTEM_PATH` ahora precede a `${binDir}`).
  - Esto garantiza que comandos fundamentales como `rm`, `rmdir`, `ls`, `mkdir`, `cp` se resuelvan siempre al binario nativo del sistema en `/system/bin/`, eliminando por completo el fallo `Permission denied` causado por restricciones SELinux W^X en Android 10+ (`app_data_file`).
- **Purgado Profundo de Shims Residuales**:
  - Detección y eliminación garantizada de scripts shim antiguos (`rm`, `rmdir`, `om_tracer`) en `context.filesDir/bin`.
  - Renombrado atómico y limpieza forzada por Root (si está disponible) para asegurar que ningún archivo huérfano intercepte las llamadas.
- **Funciones Interceptoras Robustas en `om_shell_rc.sh`**:
  - Los interceptores shell de `rm()` y `rmdir()` ahora invocan directamente `/system/bin/rm` y `/system/bin/rmdir` en lugar de delegar en búsqueda ambigua de `$PATH`.

---

### 🚀 Optimización Extrema de Rendimiento y Estabilidad del Explorador
- **Caché de Syscall `StatFs` en Hilo Principal**:
  - `FileListActivity.kt` ahora almacena en caché las estadísticas de almacenamiento libre/total con un TTL de 4 segundos.
  - Se eliminan las llamadas bloqueantes a `StatFs` en el hilo UI durante eventos de selección rápida por deslizamiento (*swipe selection*) y navegación de pestañas duales, asegurando 120 FPS sin caídas de frames.
- **Eliminación de Parpadeos y Reconstrucción de Vistas en `ListDiffer`**:
  - Se incrementó el umbral de cálculo de diferencias a 1.000 elementos.
  - Se reemplazó el vaciado forzado (`onRemoved` + `onInserted`) por actualizaciones inteligentes en el lugar (`onChanged`) y deltas diferenciales, evitando que el RecyclerView destruya y recree los ViewHolders en carpetas con muchos archivos (Descargas, DCIM, Sistema).
- **Ordenación Fluida de Archivos**:
  - Se eliminó el reseteo innecesario con lista vacía (`replace(..., clear=true)`) al alternar criterios de ordenación en `FileListAdapter.kt`.

---

### 🛡️ Seguridad Previa (v1.9.39)
- Mitigaciones contra desbordamiento de búfer en encabezados TAR PAX (CVE-2026-15028) y doble liberación en RAR5 (CVE-2026-14164) en `libarchive`.

---

### 🧪 Suite de Pruebas
- 161 pruebas unitarias aprobadas (100% éxito) en la suite de pruebas automatizadas.
