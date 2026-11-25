# OpenManager v2.0.27: Atrás que se Detiene en el Almacenamiento, VPS que se Desconecta y Ordenar con Marca

### ⬅️ Atrás
- **Sin root**, Atrás se detiene en el almacenamiento interno (o en la raíz de la tarjeta SD/USB) en lugar de subir a `/storage/emulated`, `/storage` y `/`, carpetas que una app sin root no puede leer (pantallas vacías). Allí, Atrás de nuevo ofrece salir de la app.
- **Con root**, Atrás sigue subiendo hasta `/`.
- En ambos casos `/` sigue a un toque desde el menú lateral (Raíz).

### 🖥️ Archivos del VPS: salida limpia
- **Atrás** en la raíz del VPS sale al almacenamiento del teléfono.
- **Inicio** lleva primero a la carpeta de inicio del VPS; si ya estás en ella, sale al almacenamiento del teléfono.
- Al salir (o al cerrar el explorador), la conexión de archivos con ese servidor **se cierra** y se borran sus listados en caché, así no queda nada consumiendo red ni memoria. La sesión SSH de la terminal sigue abierta.

### 🔌 SSH caído en segundo plano
- Si la conexión SSH/Telnet se cae mientras la app está en segundo plano, al volver ya no se queda la pantalla congelada con «[Connection closed - press Enter]»: aparece **«Conexión perdida»** con **Reconectar** o **Lista de servidores**. La sesión muerta se descarta y libera su memoria.

### 📝 Editor de código
- **Barra de símbolos rediseñada:** ahora se ve como la barra inferior del explorador: mismo color (gris, negro en AMOLED, claro en el tema claro), teclas planas con efecto al pulsar, iconos vectoriales del mismo tamaño (flechas, tabulación, sangría, nueva línea, deshacer/rehacer, buscar, pegar, seleccionar todo) y separadores entre grupos. Mantén pulsada una tecla para ver su nombre. Se acabaron las cajitas oscuras con glifos sueltos, que en el tema claro eran invisibles.
- **Menú ⋮ con iconos:** Guardar como, Posición anterior/siguiente, Sintaxis, Ir a línea, Ajuste de línea, Números de línea, Solo lectura, Tema, Estadísticas, Fin de línea, Codificación, Preferencias y Recargar.
- **C y C++ por separado:** cada uno con sus palabras clave (C++ añade clases, plantillas, `nullptr`, conversiones…). `.c`/`.h` abren como C; `.cpp`, `.hpp`, `.cc`, `.ino`… como C++.
- **Cambiar la sintaxis recolorea el texto** al momento (comprobado: de Texto plano a Java en el emulador).

### ⚙️ Ajustes
- Los últimos ajustes (Idioma…) quedaban medio tapados por la barra de navegación del sistema y costaba tocarlos: la lista ahora deja ese espacio y se pueden subir por encima.

### ✔️ Ordenar y vista
- El criterio activo (Nombre, Tipo, Tamaño o Fecha) lleva una **marca ✓** y va en negrita; también se marcan «Invertir orden» y «Carpetas primero» cuando están activos.

# OpenManager v2.0.26: Nuevo Editor de Código

### 🚀 Motor nuevo, rápido con archivos enormes
- El editor de texto se ha reescrito sobre un motor de código (sora-editor) que **solo dibuja las líneas que se ven**. Abrir, desplazarse y escribir en archivos de cientos de miles de líneas ya no congela la pantalla.
- El coloreado de sintaxis se calcula **en segundo plano**, nunca en el hilo de la interfaz, y se actualiza mientras escribes.
- **Deshacer/Rehacer** con historial completo, que se conserva al girar la pantalla (antes se podían perder las ediciones al rotar).

### ✍️ Más cómodo
- Selección con asas y lupa, menú flotante de copiar/cortar/pegar, y doble toque para seleccionar palabras.
- **Zoom con dos dedos** suave; el tamaño se recuerda.
- Resaltado de la pareja de **llaves y paréntesis** y líneas guía de bloques.
- Sangría automática al pulsar Intro (un nivel más tras `{`, `(`, `[` o `:`) y cierre automático de llaves y comillas.
- El teclado no autocorrige ni sugiere palabras dentro del código.
- Buscar y reemplazar (texto, palabra completa o expresión regular) en segundo plano, con el contador de coincidencias.
- Barra de símbolos: los botones de sangría actúan sobre todas las líneas seleccionadas; las llaves y comillas envuelven la selección.

### 🧹 Menú ⋮ más corto
- Quitados **Actualizar** (desliza hacia abajo en la lista para recargar), **Añadir marcador** (se hace desde **Marcadores** → «Añadir esta carpeta a marcadores») y **Salir** (el botón atrás cierra la app).

### 🎨 Tema claro
- En el menú ⋮, los iconos de Seleccionar todo, Archivos ocultos, Intercambiar paneles, Marcadores, Añadir marcador, Establecer como inicio y Salir eran blancos y desaparecían sobre el menú blanco. Ahora usan el color de los controles del tema (se ven en claro, oscuro, AMOLED y Material 3).
- El ajuste de línea funciona: el editor viejo lo ignoraba (un JSON de una sola línea no se partía aunque la opción estuviera marcada).

### 🧩 Se mantiene todo lo anterior
- Temas, codificaciones, CRLF/LF, ir a línea, historial de posiciones, solo lectura, «Guardar como», documentos nuevos desde la barra lateral, aviso de cambios externos y protección DeadMan.
- Las estadísticas del archivo ahora están traducidas.

# OpenManager v2.0.25: Editor de Texto Más Rápido, Estable y Abierto desde la Barra

### 📝 «Editor de texto» desde la barra lateral
- Antes se abría y se cerraba al instante (el editor exigía un archivo). Ahora abre un **documento nuevo «Sin título»**.
- Al guardar aparece **«Guardar como…»**: nombre (ya seleccionado para escribir encima) y carpeta, con la del panel abierto propuesta. Avisa si el archivo ya existe.
- Si sales sin guardar un documento con texto, pregunta antes de descartarlo.
- **«Guardar como…»** también está en el menú para cualquier archivo, para guardar una copia con otro nombre o en otra carpeta.

### ⚡ Rendimiento con archivos grandes
- El número de línea, la columna y los números del margen ya no recuentan todo el archivo en cada tecla ni en cada fotograma del scroll: se actualizan solo con lo que cambia. En archivos de miles de líneas escribir y desplazarse ya no da tirones.
- En textos largos, el coloreado de sintaxis repinta solo la zona visible.
- Intro y Retroceso ya no copian el archivo entero.
- La búsqueda del paréntesis o llave pareja tiene un límite.
- Los archivos remotos (SFTP, SMB…) ya no se consultan al servidor cada 2,5 s para ver si cambiaron; solo al volver al editor.

### 🛡️ Estabilidad
- Pulsar **Guardar** dos veces seguidas podía cerrar la app. Ya no.
- El coloreado de sintaxis leía el texto mientras escribías desde otro hilo; podía fallar o colorear caracteres equivocados. Ahora trabaja sobre una copia y descarta resultados viejos.
- Errores al abrir en lenguaje claro: un archivo demasiado grande dice su tamaño y el límite, y sugiere el editor HEX.

### 🎨 Interfaz
- Con el tema **claro**, la barra de símbolos, la de búsqueda y la de estado tenían texto casi blanco sobre fondo claro (ilegible). Ahora usan los colores del tema.
- La columna se cuenta dentro de la línea real también con ajuste de línea, y «Sel: N» está traducido.

# OpenManager v2.0.24: Vídeos Panorámicos en la Galería y Menú Más Limpio

### 🖼️ Galería
- Los **vídeos** ocupan ahora **dos casillas** a lo ancho: una miniatura panorámica, como el vídeo, en vez de un cuadrado recortado. Tienen la misma altura que las fotos, así las filas quedan parejas.
- Las fotos siguen igual.

### 🧹 Menú ⋮ más limpio
- Se quitó el botón «Vista galería» del menú ⋮ (había demasiados botones).
- **Ordenar** pasa a llamarse **«Ordenar y vista»**. Su primera opción, **«Vista: lista, cuadrícula o galería…»**, permite elegir la vista y aplicarla:
  - a **solo esta carpeta**, o
  - a **todas las carpetas** como predeterminada.

# OpenManager v2.0.23: Sin Cierres al Navegar por Fotos y Vídeos

### 🛠️ Corregido un cierre en Android 14–16 (Samsung y otros)
- La app podía cerrarse sola **al navegar por carpetas de vídeos o fotos** («stableCount < 0» en `MediaScannerConnection`).
- **Causa:** cada vez que la app abría un archivo, **incluso solo para leerlo** (miniaturas, visores), avisaba dos veces al escáner de medios de Android. En una carpeta con muchos vídeos eran decenas de avisos seguidos, y el propio escáner de Android se equivocaba al cerrar su conexión y tumbaba la app desde un hilo del sistema.
- **Ahora:**
  - solo se avisa al escáner cuando un archivo **se escribe** (crear, copiar, mover, borrar), porque leer no cambia nada para la galería;
  - los avisos se agrupan y se envían en un solo lote cada medio segundo, nunca dos a la vez;
  - si aun así Android lanzara ese fallo concreto suyo, se anota en el registro y la app sigue abierta.
- **Más fluido:** al no avisar al escáner por cada lectura, navegar por carpetas grandes de fotos y vídeos gasta menos batería y menos CPU.


# OpenManager v2.0.22: PDF Completo y Contador de Páginas Visible

### 📄 Lector de PDF (aviso de un usuario)
- **Corregido: el PDF solo se veía a medias y no se podía ampliar ni desplazar** (v2.0.19–v2.0.21). Desde la v2.0.19 se amplía el documento entero en lugar de cada página. Para eso se quitó el zoom propio de cada página, y desde entonces la imagen ya no se ajustaba a su marco: la página, que se dibuja más grande que la pantalla para que el texto sea nítido, salía a tamaño real desde la esquina. Ahora cada página llena su marco y el zoom y el desplazamiento vuelven a funcionar. En modo paginado cada página sigue teniendo su propio zoom.
- **Corregido: el contador «Página X de Y» salía cortado por la mitad.** La barra superior medía 44 dp y no cabían el título y el número de página. Ahora mide 56 dp.


# OpenManager v2.0.21: Apps en Ejecución Renovado y «¿Qué Calienta el Móvil?»

### 🌡️ ¿Qué calienta el móvil? (solo root)
- Nueva tarjeta ligera en **Apps en ejecución** con:
  - temperatura de la CPU y de la batería, con un nivel de color (fresco, templado o caliente);
  - las causas principales, como mucho tres: apps que más CPU usan, **brillo de pantalla** (el nivel real del panel), carga y su corriente, y uso de la GPU.
- Lee todo con **un solo comando root** por refresco, sin procesos extra.
- Sin root la tarjeta no aparece.

### 📊 Sin root: RAM real de OpenManager y de tus scripts
- Sin root ni Shizuku, Android solo deja ver los procesos de la propia app. Ahora esa vista es útil:
  - cada proceso (la app y los scripts que ejecuta) muestra su **memoria real (PSS)** en vez de RSS, que contaba entero cada recurso compartido y la inflaba;
  - arriba aparece un resumen: «OpenManager: X de RAM real · app Y · scripts (N) Z»;
  - las pestañas se ocultan, porque solo había unos pocos procesos propios que repartir.
- **Más ligero:** sin root ya no se lanza un proceso `ps` en cada refresco (cada 2,5 s), porque no aportaba nada.

### 🌐 Idiomas
- Los estados de los procesos («RUNNING», «ACTIVE», «ZOMBIE»…) salían siempre en inglés. Ahora salen en tu idioma.


# OpenManager v2.0.20: Miniaturas en Ultra RAM, Vista Galería y APK Grandes al Instante

### ⚡ Ultra RAM
- **Miniaturas en modo ligero.** Antes el modo Ultra RAM las apagaba aunque eligieras verlas. Ahora:
  - al activar Ultra RAM empiezan apagadas (solo iconos);
  - si las activas, se cargan en modo ligero: al tamaño del icono, en 16 bits, solo cuando la lista se detiene y con una pequeña caché en disco (16 MB máx.) para no volver a extraer fotogramas de vídeo, iconos de APK o páginas de PDF.
  - Al desactivar Ultra RAM vuelve tu elección anterior.
- **Material 3 queda bloqueado** mientras Ultra RAM está activo, con una nota que explica por qué. Antes se podía activar dentro del modo y deshacía parte del ahorro. Si lo tenías activado así, se apaga al actualizar.

### 🖼️ Vista galería (por carpeta)
- Nueva opción en ⋮: **«Vista galería (esta carpeta)»**. Muestra fotos y vídeos en cuadrados grandes, sin nombres, para apreciar las miniaturas.
- Una etiqueta pequeña indica la resolución (**8K, 4K, 2K, 1080p, 720p**) sin tapar la imagen. Se lee solo la cabecera del archivo, en segundo plano.
- Solo afecta a esa carpeta. Las carpetas y otros archivos conservan su nombre.

### 📦 APK grandes
- Al tocar un APK pesado (1 GB) el diálogo se abre **al instante**. Antes la app comprobaba la firma leyendo el APK entero antes de mostrar nada.
- Nombre, icono, versión y SDK salen enseguida; la firma muestra «Comprobando…» y se completa cuando termina en segundo plano.

### 👆 Seleccionar archivos
- **Tocar el icono ya no selecciona**: abre el archivo, como tocar su nombre.
- Para seleccionar:
  - **desliza el dedo de lado sobre el icono**;
  - sigue arrastrando arriba o abajo para seleccionar varios, y la lista avanza sola en los bordes;
  - o **mantén pulsado el icono**.
- Mantener pulsado el resto de la fila sigue abriendo el menú.

### ↩️ El «..» actuaba en el otro panel
- Tras borrar archivos, el «..», abrir o seleccionar podía actuar en el panel contrario. Los dos paneles comparten las filas recicladas para ahorrar memoria, y cada fila recordaba el panel que la creó. Ahora cada toque actúa siempre en el panel donde está la fila.


# OpenManager v2.0.19: Lector de PDF Mejorado y «Atrás» Siempre en el Panel Correcto

### 📄 Lector de PDF
- **Zoom de todo el documento.** Al pellizcar se ampliaba solo una página. Ahora se amplía el documento entero, como en un lector de verdad:
  - el zoom sigue a los dedos;
  - te puedes mover a los lados y seguir bajando con todas las páginas ampliadas;
  - doble toque amplía o vuelve al tamaño normal.
- **Texto nítido al ampliar.** Cuando el zoom se detiene, las páginas que ves se renderizan de nuevo a esa resolución, en lugar de estirar la imagen. Al volver al tamaño normal se libera esa memoria. En móviles con poca memoria o Android 6–7 el detalle máximo es menor, para no cerrarse nunca.
- **Mantener la pantalla encendida**: nueva opción en el menú ⋮ del lector. Se recuerda para la próxima vez.
- **Recuerda la última página de cada PDF** (hasta 300 documentos). Al reabrirlo continúas donde lo dejaste, con un botón «Desde el principio». Si el archivo cambia, empieza desde la página 1.
- En modo paginado cada página sigue teniendo su propio zoom.

### ↩️ «Atrás» en el panel correcto
- A veces el botón Atrás actuaba sobre el **otro** panel: por ejemplo, quitaba la selección o la búsqueda del panel de al lado en vez de subir de carpeta en el que estabas usando. Ahora Atrás actúa **solo** sobre el panel activo.

### 🌐 Idioma en Android 12 y anteriores
- Se comprueba en un emulador Android 12 que elegir el chino desde Ajustes → Idioma cambia toda la app (ajustes, pantalla principal y menú lateral), también tras cerrarla y volver a abrirla.


# OpenManager v2.0.18: Marcadores en la Pantalla de Inicio, Botón Adelante y Todo en tu Idioma

### 🔖 Marcadores
- **Añadir a la pantalla de inicio** (petición de la issue #3, como en MT Manager): mantén pulsado un marcador en el panel de marcadores o en el menú lateral. Aparece su menú con «Añadir a la pantalla de inicio», que crea un icono que abre esa carpeta directamente.
- En el panel de marcadores:
  - mantener pulsado sin moverse abre el menú del marcador;
  - mantener pulsado y arrastrar sigue sirviendo para ordenar.
- El menú del marcador es igual en el panel y en el menú lateral: abrir en el otro panel, añadir a la pantalla de inicio, editar y quitar (con deshacer).

### ➡️ Botón Adelante
- El botón **›** de la barra inferior no hacía nada. Ahora vuelve a la carpeta de la que saliste con **‹**, en el mismo punto de la lista.
- Cuando no hay adonde avanzar, el botón se ve atenuado.

### 🌐 Toda la app en tu idioma
- Unos 150 textos estaban escritos a mano en español o en inglés y no salían en tu idioma. Ahora están en los 7 idiomas (inglés, español, chino, ruso, portugués, indonesio y vietnamita), entre ellos:
  - el diálogo de permiso del primer arranque;
  - los nombres por defecto al crear o renombrar;
  - los avisos de seguridad de particiones;
  - el instalador de APK: errores de instalación, firma diferente, targetSdk bajo;
  - el progreso de Apktool, la edición rápida y el inyector;
  - la búsqueda de texto y de duplicados;
  - los editores ARSC, DEX y HEX;
  - los detalles de apps;
  - las notificaciones de scripts;
  - el entorno Linux de la terminal;
  - las etiquetas de información del APK;
  - el texto de privacidad.
- Los motivos de fallo de instalación de APK antes salían solo en español o en inglés. Ahora salen en cualquiera de los 7 idiomas.


# OpenManager v2.0.17: Panel de Marcadores

### 🔖 Marcadores (issue #3)
- **Los marcadores ya se pueden ver y abrir.** Antes se podían añadir, pero no aparecían en ningún sitio: el panel que prometía Ajustes no existía.
- **Panel de marcadores al estilo MT Manager**, con tres formas de abrirlo:
  - deslizando hacia arriba sobre la barra inferior;
  - desde ⋮ → **Marcadores**;
  - manteniendo pulsado el botón de inicio.
- En el panel:
  - toca un marcador para abrirlo;
  - mantén pulsado y arrastra para ordenarlos;
  - con ⋮ puedes **abrirlo en el otro panel**, editarlo (nombre y carpeta) o quitarlo, con opción de **deshacer**.
  - La primera fila añade o quita la carpeta en la que estás.
- **Menú lateral:** nueva sección **Marcadores**. Toca para abrir; mantén pulsado para gestionarlos.
- Con **«Abrir marcadores en el lado del gesto»** (activado por defecto), deslizar sobre la mitad izquierda o derecha de la barra abre el marcador en ese panel.
- **«Añadir nuevos marcadores arriba»** ahora funciona. Antes el ajuste existía, pero no hacía nada.
- Una carpeta ya guardada no se duplica. Al añadir un marcador, el aviso explica dónde encontrarlo.
- Los textos de Ajustes ya no hablan de «grupos» de marcadores que no existen.

### 🌐 Idiomas
- La opción de archivos ocultos del menú ⋮ salía siempre en español. Ahora usa tu idioma y muestra una casilla con su estado.
- Tres textos salían siempre en español o en inglés, fuera cual fuera tu idioma; ahora salen en el tuyo:
  - el subtítulo de la lista («Carpetas: 14  Archivos: 0  Disk: …»);
  - el espacio en disco;
  - el contenido de una carpeta en Propiedades.


# OpenManager v2.0.16: La VPS Aguanta los Cortes de Red

### 🖥️ Archivos de la VPS tras un corte de red
- **Corregido el error `src.length=1 srcPos=0 dst.length=256 dstPos=-4`.** Venía de un fallo de la librería SSH (sshj): si un envío fallaba a mitad (cambio de Wi-Fi a datos, red caída), el canal de archivos quedaba roto por dentro y la app lo seguía usando, así que cada operación devolvía ese error.
- **Por qué se cerraba también la terminal:** al cerrar ese canal roto, la librería fallaba en su hilo de lectura y tumbaba la conexión SSH entera. Ahora el canal roto se cierra de forma segura, en segundo plano y sin bloquear la lista.
- **Recuperación automática:** un canal que falla no se vuelve a usar. Las lecturas (listar, ver propiedades, abrir) se repiten una vez en un canal nuevo, y si la conexión de la terminal murió, en una propia. Los cambios (crear, borrar, renombrar) no se repiten si pudieron llegar al servidor, para no hacerlos dos veces.
- Si no se puede recuperar, el mensaje es claro y en tu idioma ("Se perdió la conexión con Mi VPS…") en vez del error interno.
- Salir de una carpeta mientras carga ya no se confunde con un corte y no provoca reconexiones.
- **Terminal:** si la conexión muere al enviar una tecla, la sesión se marca como terminada (con su opción de reconectar) en vez de quedarse congelada sin responder.


# OpenManager v2.0.15: Archivos de la VPS Arreglados, Apariencia sin Fallos y APK más Ligero

### 🖥️ Archivos de la VPS: ver, crear y sin lag
- **Corregido: no se podía crear, pegar, mover, renombrar ni borrar en la VPS** (ni en SMB, FTP o WebDAV). La app comprobaba los permisos mirando la carpeta con ese nombre **en el móvil** (por ejemplo `/root`), que nunca es escribible, y bloqueaba la operación. Ahora en un servidor se intenta la operación y, si el servidor la rechaza, se muestra su motivo real.
- **Misma conexión que la terminal**: los archivos se abren como un canal SFTP sobre la sesión SSH ya iniciada. No hay un segundo inicio de sesión, la app no vuelve a pedir la contraseña ni la clave del servidor, y funciona también en servidores que solo aceptan la contraseña por *keyboard-interactive* (como Ubuntu en la nube), donde antes fallaba.
- Si esa conexión cae, se abre una propia que ahora también prueba *keyboard-interactive*.
- **`/root` ya no parece vacía**: si una carpeta solo tiene archivos ocultos (`.bashrc`, `.ssh`…), lo indica y con un toque los muestra.
- **Menos lag**:
  - Las carpetas grandes con enlaces (como `/usr/bin`) se resuelven con muchas peticiones en paralelo.
  - Las miniaturas respetan el límite de tamaño y "Solo Wi-Fi" de Ajustes; antes descargaban imágenes enteras de cualquier tamaño.
- Los errores en la VPS explican lo ocurrido (sin permiso en el servidor o conexión perdida) y tienen un botón **Reintentar** que reconecta, en vez de ofrecer Root o Shizuku del móvil, que allí no sirven.
- La protección DFRoot del móvil ya no bloquea carpetas de la VPS como `/system` o `/data`.
- El panel de la VPS ya no muestra el espacio libre del móvil.


### 🎨 Apariencia
- **AMOLED (y los demás temas rápidos) siempre responde**: si la pantalla ya se veía así (por ejemplo "Sistema" con el móvil en oscuro y el fondo negro activado), al tocar AMOLED no se marcaba y parecía que no hacía nada.
- **Color de tema con Material 3**: antes se ignoraba y Material 3 usaba los colores del fondo de pantalla. Ahora el color elegido se aplica, con tonos para día y noche. El color por defecto sigue usando los del fondo de pantalla.
- **Modo Ultra RAM** activa también el tema clásico (sin Material 3) y el **fondo uniforme** de los paneles. Al desactivarlo vuelven tus elecciones anteriores (salvo las que cambiaste mientras estaba activo).

### 🗜️ Archivos comprimidos sin opciones que asusten
- Desaparece la sección con interruptores de CVE. La protección es automática y no se puede desactivar por error:
  - La limpieza de TAR (CVE-2026-15028, aún sin corregir en libarchive 3.8.8) está **siempre activa**.
  - Los **RAR5 se abren con normalidad**: el fallo CVE-2026-14164 ya está corregido en la libarchive 3.8.8 que lleva la app, y además se comprueba la cabecera antes de abrirlos.
- Un RAR dañado muestra un mensaje claro ("está dañado, no se abrió por seguridad") en vez de un código CVE.

### 🪶 Más ligero y más fácil de diagnosticar
- Imágenes en WebP sin pérdida (idénticas píxel a píxel): unos 460 KB menos.
- Las librerías ya no incluyen textos en ~50 idiomas que la app no tiene.
- En Android 11+, si el sistema cerró la app (bloqueo, fallo nativo o falta de memoria), se guarda un informe en la carpeta de logs al volver a abrirla.

---

# OpenManager v2.0.14: Ruso, Portugués, Indonesio y Vietnamita

### 🌍 Cuatro idiomas nuevos completos
- **Ruso**, **portugués (Brasil)**, **indonesio** y **vietnamita**: los ~2.200 textos propios de OpenManager (ajustes, herramientas, terminal, conexiones, visores, Dead Man…) ya están traducidos, igual que el chino simplificado en la v2.0.13.
- El vietnamita recibe además ~75 textos de Material Files que tenía sin traducir.
- Idiomas completos: inglés, español, chino simplificado, ruso, portugués (Brasil), indonesio y vietnamita.

### 🐞 Arreglo
- En español, el número de permisos de un APK con **un** permiso decía «No se requieren permisos».

### ✅ Verificado
- Un test impide que falte cualquier texto visible en alguno de esos idiomas o que tenga marcadores distintos.
- En el emulador se pone la app en cada idioma y se comprueba la pantalla de Ajustes.

---

# OpenManager v2.0.13: App en Chino Simplificado

### 🇨🇳 Chino simplificado completo (issue #2)
- Los ~2.200 textos propios de OpenManager (ajustes, herramientas, terminal, conexiones, visores, Dead Man…) solo existían en inglés y español. Con la app en chino simplificado casi todo seguía en inglés y parecía que el cambio de idioma no funcionaba. Ahora están todos traducidos.
- El estado de root de Ajustes ("No Root" / "Working") ya no está fijo en inglés.
- Un test unitario impide que vuelva a quedar un texto sin traducir al chino o con marcadores distintos; en el emulador se pone la app en chino y se comprueban Ajustes y el menú lateral.

### 🐞 Otros arreglos
- El editor ARSC mostraba sus textos en español aunque la app estuviera en inglés.
- El resumen al comparar archivos binarios perdía los saltos de línea.

---

# OpenManager v2.0.12: Fotos y Vídeo 4K Inteligentes

### 🖼️ Fotos grandes sin gastar memoria de más
- Las fotos claramente más grandes que la pantalla (fotos de 12 MP o más, capturas 4K) se muestran **por teselas**: una imagen base del tamaño de la pantalla y, al hacer zoom, solo la zona ampliada a resolución completa. Antes se cargaba la foto entera (33–48 MB cada una, más las vecinas).
- Las fotos de cámara con **rotación EXIF** se muestran derechas también en este modo.
- Los formatos que no admiten teselas (HEIC, AVIF…) se cargan como máximo al doble de la pantalla: siguen nítidos con zoom sin guardar el original completo.
- Si el decodificador por teselas falla, la foto se abre igualmente de la forma normal.

### 🎬 Vídeo 4K más fiable
- **Corregido**: en móviles con poca memoria para Java, un 4K podía cortarse a los pocos segundos por falta de memoria. El búfer ahora se ajusta a la memoria disponible.
- Al **arrastrar la barra** se ve el fotograma en tiempo real (fotogramas clave, rápidos incluso en 4K); al soltar, va al punto exacto.
- Los vídeos de más de 3 minutos **continúan donde los dejaste**.
- Si el móvil decodifica un vídeo grande por software y no da abasto, lo avisa una vez en vez de ir a saltos sin explicación.
- Las estadísticas indican si el decodificador es por hardware o por software.

### ✅ Verificado en el emulador
- 4K H.264 y 1080p HEVC se reproducen los 20 s completos, con fotogramas mostrados y sin errores.
- Foto de 12 MP con EXIF girado: teselas y orientación correcta; foto pequeña: carga normal.
- El APK no crece: sin librerías nuevas.

---

# OpenManager v2.0.11: Gestión desde el Navegador, Renombrado por Lotes y Comparar Archivos

### 🌐 Acceso desde el navegador
- Gestiona los archivos del móvil **desde el navegador de un PC**, una tablet u otro móvil conectado a la misma Wi-Fi. Está en **Conexiones → Este dispositivo → Acceso desde el navegador**.
- Desde la web puedes:
  - navegar por las carpetas;
  - ver fotos y vídeos (con avance y retroceso);
  - descargar uno o varios archivos;
  - **subir arrastrando archivos a la página**, con barra de progreso;
  - crear carpetas, renombrar y borrar.
- **Seguridad:**
  - cada vez que se inicia se genera un **código de 8 caracteres**, y 10 intentos fallidos bloquean el acceso;
  - solo se accede al almacenamiento del móvil;
  - lo que se sube nunca sobrescribe nada sin preguntar;
  - una página HTML subida no se ejecuta en el navegador.
- La notificación muestra la dirección y el código, con un botón **Detener**.
- No añade librerías: es un servidor propio muy ligero.

### ✏️ Renombrado por lotes
- Selecciona varios archivos y elige **Más → Renombrar por lotes**.
- **Plantilla** con `{name}` (nombre actual), `{n}` (número con los dígitos que quieras) y `{ext}` (extensión). Por ejemplo, `Viaje {n}` da `Viaje 01.jpg`, `Viaje 02.jpg`…
- **Buscar y reemplazar**, con expresiones regulares opcionales.
- **Mayúsculas y minúsculas:** igual, minúsculas, MAYÚSCULAS o Tipo Título.
- **Vista previa en vivo** de cada nombre, antiguo → nuevo, que marca los nombres vacíos, repetidos o que ya existen antes de renombrar nada.
- Se pueden intercambiar o desplazar nombres (`1.jpg` → `2.jpg` → `3.jpg`) sin pisar ningún archivo. Si algo falla, todo vuelve a como estaba.

### 🔍 Comparar archivos
- Selecciona dos archivos y elige **Más → Comparar**. También puedes elegir **Comparar con el otro panel** si en el otro panel hay un archivo con el mismo nombre.
- **Texto:** se muestran las líneas quitadas (rojo) y añadidas (verde) con contexto, y se pliegan los tramos sin cambios. Los botones **Cambio anterior / siguiente** saltan entre diferencias.
- Si solo cambian los finales de línea (CRLF/LF), lo indica.
- **Binarios:** tamaños, posición del primer byte distinto, número de bytes distintos y SHA-256 de ambos archivos.

### 🧪 Pruebas
- Los cierres de la herramienta de pruebas `uiautomator` ya no se cuentan como cierres de la app.

### ✅ Verificación
- En el emulador:
  - el renombrado por lotes convirtió `IMG_00x.jpg` en `Viaje 0x.jpg` en orden y dejó intacto `keep.txt`;
  - la comparación mostró `−1 +2` con la línea cambiada;
  - desde el runner, sin código la app solo mostró el inicio de sesión;
  - con el código se subió un archivo a `Download`, se listó, se descargó idéntico y se borró;
  - una ruta fuera del almacenamiento devolvió 403.

---

# OpenManager v2.0.10: Archivos de la VPS más Claros y Fiables

### 🗂️ Archivos de la VPS
- El panel de la VPS muestra **su nombre** delante de la ruta, por ejemplo `Mi VPS:/root/`, para no confundirlo con la raíz del móvil. En la barra de ruta, la raíz del servidor también aparece con ese nombre.
- **Solo se abre si la sesión sigue conectada**. Si no lo está, se ofrece **Reconectar** en lugar de un error.
- Antes de abrir el explorador se comprueba que la carpeta **se puede leer**.
- Si la conexión quedó colgada (cambio de Wi-Fi a datos, reinicio del servidor), la app abre una conexión nueva automáticamente. Antes reutilizaba la conexión muerta y salía un error interno como `src.length=1 … dstPos=-4`.
- Los errores se explican con claridad: contraseña incorrecta, servidor que no responde, sin permiso para la carpeta o conexión perdida.
- Si escribes la contraseña otra vez, se usa la nueva, no una anterior equivocada.

### 🏠 Botón de inicio
- En la barra inferior, la flecha "subir" pasa a ser una **casa** que lleva al inicio **del panel activo**:
  - en el móvil, a su carpeta de inicio (el almacenamiento, salvo que la cambies en ajustes);
  - en la VPS, a la carpeta personal del usuario en el servidor.
- Para subir una carpeta sigue estando la flecha de la barra superior.

### ✅ Verificación
- En el emulador, contra un OpenSSH real:
  - el panel mostró `CI OpenSSH:/home/omtest/probe/`;
  - la casa llevó a `/home/omtest`;
  - la foto de la VPS se abrió en el visor;
  - con dos "atrás" se volvió a la sesión.

---

# OpenManager v2.0.9: Seguridad Root más Sólida y Avisos de Actualización Fiables

### 🔔 Actualizaciones automáticas
- El aviso de nueva versión ahora aparece **cada vez que abres la app**, también cuando Android la tenía en memoria. Antes solo se comprobaba al abrirla desde cero.
- **Funciona también con el modo Ultra RAM**, que antes desactivaba la comprobación por completo.
- La pausa entre comprobaciones bajó de 2 horas a 15 minutos. Cada comprobación es una petición mínima a GitHub.
- Si la versión acaba de publicarse y su APK aún se está subiendo, la app espera al archivo en lugar de ofrecer una descarga que fallaría, y vuelve a mirar la próxima vez que la abras.
- Si el aviso se cierra porque giraste la pantalla o Android cerró la app en segundo plano, vuelve a mostrarse.
- "Más tarde" se respeta: no vuelve a preguntar al momento.

### 🛡️ Sin root: sin interrupciones
- Ya no aparece ninguna confirmación al ejecutar scripts sin root, tampoco para rutas como `/data/data`: Android ya las protege.
- Solo se pide confirmación ante un **borrado masivo**: todo el almacenamiento, una carpeta principal (DCIM, Download, WhatsApp…) o más de 5.000 archivos.

### 🔐 Con root
- **DFRoot** bloquea cualquier cambio en el sistema o el arranque, no solo los borrados: `mv`, `cp`, `chmod`, `chown`, `chcon`, `ln -f`, `truncate` y remontar `/` o `/system` con escritura.
- **Dead Man** bloquea acciones catastróficas como `chmod -R 777 /` o mover `/system/bin`.
- Los scripts con root ya no preguntan en cada `rm`, salvo que elijas "Preguntar" para ese script.

### 🧩 Módulos del kernel (`.ko`)
- Antes de cargarlos se muestra qué son: nombre, autor, para qué kernel están compilados y si están firmados.
- Se bloquean si son de otro kernel u otra CPU, también con `insmod -f`.
- Con DFRoot se bloquean además si acceden a las particiones.

### 🧱 Binarios ELF y scripts como root
- Con Dead Man o DFRoot activos se ejecutan **aislados**: sin acceso a las particiones y con el sistema en solo lectura, solo para ese programa. El resto del teléfono no se ve afectado.
- Si el aislamiento no se puede garantizar, no se monta nada.
- Con DFRoot, un binario que carga código en el kernel no se ejecuta, porque ningún aislamiento lo contiene.

### 🔒 Otros
- Los comandos de prueba internos ya no se registran en la versión publicada.

### ✅ Verificación
- En el emulador, la app antigua ofreció la actualización con el modo Ultra RAM activado, respetó "Más tarde" y volvió a ofrecerla al reabrirla desde memoria. Después se instaló encima conservando los datos.
- Sin root, un borrado normal no pidió nada; uno de 5.200 archivos pidió confirmación y "Bloquear" los conservó.
- El aislamiento se probó con root real en el kernel de Android: dentro no había particiones y `/system` estaba en solo lectura; fuera, todo siguió igual.

---

# OpenManager v2.0.8: Archivos de la VPS junto a los del Móvil

### 🗂️ Explorador de la VPS desde la terminal
- En una sesión SSH, **toca el chip de carpeta** (el que muestra `usuario@servidor: ~/carpeta`) o elige **Archivos de la VPS** en el menú ⋮.
- Se abre el explorador de doble panel: **la VPS en un lado**, empezando en la carpeta donde está la terminal, y **los archivos del móvil en el otro**, para copiar o mover en ambos sentidos.
- **Fotos, música, vídeo y texto** de la VPS se abren con los visores de la app.
- Usa la clave o la contraseña del perfil. Si la contraseña no está guardada, se pide una vez y solo se guarda en memoria.
- La identidad del servidor se verifica igual que en la terminal.
- **Atrás** desde la carpeta inicial vuelve directamente a la sesión SSH.
- Mantener pulsado el chip sigue copiando la ruta.

### 📦 Peso
- No añade ninguna librería: reutiliza el cliente SFTP que ya incluía la app.

### ✅ Verificación
- En el emulador, contra un OpenSSH real:
  - el chip abre `~/probe` del servidor;
  - el otro panel muestra el almacenamiento del móvil;
  - una foto de la VPS se ve en el visor;
  - con dos "atrás" se vuelve a la sesión.

---

# OpenManager v2.0.7: Abrir Archivos desde Otras Apps con un Toque

### 📂 Abrir desde otras apps
- OpenManager aparece **una sola vez** en "Abrir con", con el nombre de lo que hará: **Ver PDF**, **Ver imagen**, **Reproducir vídeo**, **Reproducir audio**, **Abrir texto** o **Ver documento**. Antes salían dos entradas (por ejemplo "OpenManager" y "Visor PDF").
- El archivo se abre **directamente** en su visor, sin pasos intermedios.
- **Atrás te deja en el explorador con el archivo seleccionado**, listo para guardarlo, copiarlo o moverlo al otro panel.
- Comprimidos, APK y otros tipos aparecen como **Abrir en OpenManager** y van al explorador, donde están sus herramientas.
- Si el texto tiene cambios sin guardar, se pregunta antes de salir, como siempre.

### 🗂️ Archivos recibidos sin ruta (WhatsApp, Telegram, Gmail…)
- Se copian a una carpeta **Recibidos** de la app en lugar de llenar `Descargas`, y las copias se borran solas a los 7 días. Un aviso indica que la copia es temporal: cópiala o muévela para conservarla.

### 🖼️ Explorador
- Las fotos abiertas desde el explorador van directas al visor integrado, deslizando entre las de la carpeta.
- "Abrir con otra app" desde el visor de PDF ya no ofrece al propio OpenManager.

### ✅ Verificación
- En el emulador: PDF, imagen y texto aparecen una sola vez en "Abrir con", abren en su visor y al volver atrás se muestran seleccionados en el explorador.

---

# OpenManager v2.0.6: Varias Sesiones a la Vez, Claves SSH en un Toque, SFTP Verificado y Arranque más Rápido

### 🔑 Claves SSH sin salir del móvil (algo que MT Manager no tiene)
- **Generar clave Ed25519** en el editor de perfiles, en formato OpenSSH estándar (compatible con `ssh-keygen`, PuTTYgen y Termux) y guardada cifrada con el Keystore de Android.
- **Ver clave pública** con botones para copiarla o copiar el comando de instalación.
- **Entrar con clave (sin contraseña)**, desde el menú de una sesión SSH abierta: instala la clave en `~/.ssh/authorized_keys` del servidor con los permisos correctos y cambia el perfil a clave. La contraseña guardada queda de respaldo.

### 🧵 Varias sesiones y scripts a la vez
- Lanzar un script, una shell o una conexión SSH ya **no termina la que estaba en marcha**.
- La cápsula de sesión (`SH ▾`) lista todas las sesiones (● en marcha, ○ terminada) para cambiar entre ellas o **Cerrar esta sesión**.
- Al lanzar un script con otro en marcha se ofrece **Ejecutar a la vez**.
- La notificación sigue mientras quede alguna sesión activa.

### ⌨️ Terminal
- **Barra de escritura** (⋮ en la barra de teclas): escribes la línea en el móvil, con las sugerencias del teclado y sin esperar a la red, y la envías entera. El texto de varias líneas llega como un solo bloque a Claude, vim, etc.
- **Historial estable con wifi lento**: leer el historial ya no salta al final con cada trozo de salida que llega, ni da saltos enormes hacia arriba al deslizar. Al escribir vuelve sola al prompt.

### 🔐 Seguridad y estabilidad
- **SFTP verifica la identidad del servidor**: antes aceptaba cualquier clave, de modo que un equipo de la misma red podía hacerse pasar por el servidor. Ahora usa la misma comprobación de huella que la terminal SSH, y el aviso aparece en cualquier pantalla.
- **Autoactualización arreglada**: la actualización descargada se abre directamente en el instalador del sistema. Antes Android preguntaba "Abrir con" y, si se elegía OpenManager, no se instalaba. *Al actualizar desde la v2.0.5 o anterior todavía puede aparecer: elige "Instalador de paquetes".*

### ⚡ Rendimiento
- **Arranque más rápido**: el registro de BouncyCastle (unos 230–1.000 ms en el emulador) ya no bloquea la primera pantalla. Los pasos de inicio del hilo principal suman ahora 70–170 ms.

### ✅ Verificación
- En el emulador contra un OpenSSH real: la clave se generó, se instaló desde la sesión y el servidor registró **"Accepted publickey"** en la reconexión.
- Dos scripts avanzando a la vez y la barra de escritura enviando comandos.
- La app compilada como versión antigua se actualizó sola a la última release.
- Tests unitarios del generador de claves: la clave producida la lee el mismo parser que inicia sesión.

---

# OpenManager v2.0.5: Apps en Ejecución más Ligera y Agrupada, y Barra de la Terminal de VPS Rediseñada

### 📊 Apps en ejecución
- **Mucho menos consumo**: la lista de apps instaladas y sus nombres se guardan en caché durante 60 s, en lugar de pedírselos al sistema en cada refresco (cada 2,5 s). Ese coste era lo que hacía que OpenManager se marcara a sí mismo con más del 50% de CPU. Al refrescar a mano se actualiza al momento.
- **Una fila por app**: los procesos de una misma app (por ejemplo `com.y.om` y `com.y.om:root:0`) se agrupan con la memoria sumada y el número de procesos, y "Finalizar" termina todos. Se puede desactivar en ⋮ › *Agrupar procesos por app*.
- **Más seguro**: aviso antes de finalizar el propio OpenManager o un proceso del sistema que podría reiniciar el dispositivo. Con Shizuku ya no se informa de un éxito que no se puede comprobar.
- **Sin root ni Shizuku**: con el permiso *Acceso a datos de uso* aparecen las apps activas en los últimos 30 minutos (etiqueta **RECIENTE**), con acceso directo a su ficha para forzar la detención.

### 🔐 Terminal de VPS (SSH / Telnet)
- El escudo de auditoría de scripts se sustituye por **Conexión** (candado): protocolo, servidor, software del servidor, método de inicio de sesión, clave y huella del servidor (verificada o nueva), algoritmos negociados (cifrado, intercambio de claves, MAC), tiempo conectado y latencia estimada. Incluye *Copiar huella* y *Desconectar*.
- El botón rojo ahora es una **✕ que desconecta y cierra la terminal de un toque**, sin menú de Ctrl+C.
- **Copiar** pasa al menú ⋮ (también en la terminal local).

### ✅ Verificación
- 28 comprobaciones de punta a punta en el emulador contra un servidor OpenSSH real, incluidos el diálogo de conexión y el cierre de un toque, y tests nuevos de agrupación de procesos.

---

# OpenManager v2.0.4: Protección Real de Comandos, Dead Man y DFRoot Reconstruidos y Nueva Auditoría

### 🛡️ Los borrados se deciden ANTES de ejecutarse
- **"Preguntar" ahora espera de verdad**: antes, el `rm` vigilado borraba igualmente y el diálogo aparecía cuando el archivo ya no existía. Ahora el comando se detiene hasta que respondes **Permitir**, **Bloquear** o **Permitir todo (sesión)**. Si la terminal no está en pantalla, llega una notificación con los mismos botones; sin respuesta en 90 s, se bloquea.
- **Más comandos vigilados**: `rm`, `rmdir`, `unlink`, `find -delete`/`-exec rm`, `busybox`/`toybox rm`, `dd` hacia dispositivos y herramientas de formateo (`mkfs`, `mke2fs`, `wipefs`, `blkdiscard`, `flash_image`).
- **Los scripts quedan protegidos**: antes, un script lanzado con `sh script.sh` no cargaba la vigilancia. Ahora sí, y también las sesiones root, aunque `su` borre las variables de entorno.
- **Tu terminal queda limpia**: ya no aparecen marcas `[OM_TRACER:...]`; solo se muestra un mensaje cuando algo se bloquea.
- Lo que escribes tú a mano y los scripts internos de `pkg`/`apt` no te preguntan; Dead Man y DFRoot siguen protegiendo siempre.

### ☠️ Dead Man y DFRoot sin huecos ni falsos positivos
- Cada ruta se resuelve con la carpeta actual del shell, `..`, comodines y enlaces simbólicos, y se compara por segmentos completos:
  - **Antes no se detectaba**: `cd / && rm -rf *`, `rm -rf ../../system`, `busybox rm -rf /vendor`, `find / -exec rm` o un enlace en `/sdcard` que apunta a `/system`. Ahora se bloquean.
  - **Antes se bloqueaba sin motivo**: `rm -rf /sdcard/tmp`, o archivos tuyos dentro de carpetas llamadas `system` o `vendor`, o llamados `init` o `fstab.txt`. Ahora se permiten.
- El explorador usa el mismo clasificador para proteger edición, renombrado y permisos.
- Cuando algo se bloquea, la terminal muestra la insignia de calavera sin ventanas que interrumpan; al tocarla se abre la auditoría.

### 📋 Auditoría nueva (cómoda y útil)
- Hoja inferior que respeta el tema claro, oscuro y AMOLED, con:
  - Estado de Dead Man y DFRoot.
  - La opción **Preguntar / Bloquear / Permitir** del script, que ahora se aplica al momento, incluso con el script en marcha.
  - La **actividad real en vivo**: comando, rutas afectadas y por qué se permitió o bloqueó.
- Análisis estático del script y muestreo de llamadas al sistema en secciones plegables.
- Se eliminó la telemetría inventada (falsas ráfagas de syscalls) y el muestreo que vaciaba el registro en segundos.

### ✅ Verificación
- 26 comprobaciones de punta a punta en el emulador: el archivo sigue existiendo mientras se pregunta, Bloquear lo conserva y el script continúa, Permitir lo borra, Dead Man bloquea una ruta relativa a `/system` sin preguntar, y no hay crashes.
- Más de 350 tests unitarios, entre ellos los nuevos del clasificador de rutas.

---

# OpenManager v2.0.3: Corrección del Botón Atrás en Doble Panel, Ruta de la VPS al Reabrir y Barra de Teclas Configurable

### 🐞 Correcciones
- **El botón Atrás actuaba en el panel equivocado**: ahora, al tocar cualquier parte de un panel (ruta superior, carpeta vacía, pantalla de error de permisos y sus botones), ese panel pasa a ser el activo antes de procesar el toque. Antes solo ocurría al tocar la lista de archivos.
- **La sesión SSH mostraba `IP:puerto` al volver desde la notificación**: el chip de ruta conserva ahora la ubicación que envía el shell (`usuario@host: ~/carpeta`).

### ⌨️ Terminal más cómoda
- Se quita el control `− 14sp +`: el zoom se hace con dos dedos o con las teclas de volumen.
- Nuevo botón **⋮** en la barra de teclas para elegir entre **completa** (3 filas), **compacta** (1 fila con Esc, Tab, Ctrl, Alt, flechas, símbolos, ^C y ⌫) u **oculta** (con un ⋮ flotante para recuperarla). La elección queda guardada.

### ✅ Verificación
- 21 comprobaciones de punta a punta en CI contra servidores OpenSSH y Telnet reales, sin crashes.

---

# OpenManager v2.0.2: Escritura Instantánea con Eco Predictivo, Centro de Conexiones y Nuevo Reproductor de Vídeo 4K

### ⌨️ Escritura sin retardo en la terminal
- **Escritura instantánea (activada por defecto)**: cada tecla se envía al momento, sin que el teclado (Samsung, Gboard…) retenga la palabra hasta confirmarla. También vale cuando abres `ssh` a mano desde la terminal local.
- **Eco predictivo en SSH/Telnet (estilo mosh)**: lo que escribes aparece al instante, subrayado, mientras llega la respuesta del servidor, que luego lo confirma. Ideal para usar Claude, vim o la shell en un VPS lejano.
  - **Seguro con contraseñas**: solo se activa después de que el servidor haya devuelto la primera tecla de la línea, así que en los prompts sin eco (`sudo`, `passwd`) nunca se muestra nada.
  - En CI, con 150 ms de latencia añadida, se mostraron por adelantado 43 teclas y el servidor confirmó todas.
- Los dos interruptores están en **Ajustes de la terminal**.

### 🌐 Centro de Conexiones (sustituye a "FTP" en el menú lateral)
- Nueva entrada **Conexiones** debajo de Terminal, que reúne perfiles SSH/Telnet, servidores FTP, SFTP, SMB y WebDAV, y el servidor FTP de este dispositivo.
- El botón **+** abre un selector por categorías con iconos: *Terminal remoto* (SSH, Telnet), *Archivos remotos* (FTP, SFTP, SMB, WebDAV) y *Este dispositivo* (Servidor FTP).
- En el editor de perfiles, **Comandos al iniciar sesión** y **Barra de accesos directos** son ahora barras plegables: muestran un resumen y se abren al tocarlas.

### 🎬 Reproductor de vídeo nuevo (Media3 / ExoPlayer)
- Decodificador **por hardware**, con cambio automático a otro decodificador si el principal no acepta el vídeo (la causa típica de que un 4K "no se reproduzca").
- Salida por `SurfaceView` (del decodificador a la pantalla sin copias extra por GPU) y descarte de fotogramas tardíos para que el audio siga fluido en equipos justos de potencia.
- Los archivos locales y los `content://` se reproducen **sin copiarlos antes a la caché**.
- Búfer limitado: con un 4K, el heap Java bajó de 44 a 28 MB.
- Doble toque a los lados para ±10 s, velocidad de 0,5× a 2×, giro automático a horizontal, modo ajustar/llenar y panel de estadísticas (decodificador, resolución, FPS y fotogramas perdidos).

---

# OpenManager v2.0.1: Terminal SSH / Telnet con Perfiles, Modo Ultra RAM (< 20 MB por Pantalla) y Serie 2.0 Completa

### 🔐 Terminal SSH y Telnet con Perfiles
- **Mantén pulsado el botón Terminal** (panel lateral o menú de navegación) para elegir entre la terminal local, tus conexiones recientes, el gestor de perfiles o **+ Nueva conexión**.
- **Perfiles completos**: nombre, host/IP (IPv4/IPv6), puerto, usuario, contraseña o clave privada (OpenSSH / PEM / PuTTY, pegada o importada desde archivo) con frase opcional, comandos al iniciar sesión (p. ej. `tmux new -A -s main` y `cd ~/OpenManager`) y keepalive configurable.
- **Seguridad**: contraseñas, claves y frases se guardan cifradas con AES-256-GCM y una clave no exportable del **Android Keystore**. SSH verifica la huella del servidor (`SHA256:` como `ssh-keygen -lf`) en la primera conexión, y si la clave **cambia** muestra un aviso de posible ataque *man-in-the-middle* sin botón de aceptar con un toque.
- **Telnet seguro dentro de lo posible**: aviso claro de que viaja sin cifrar, confirmación para hosts fuera de la red local y usuario/contraseña escritos **solo** cuando el servidor muestra `login:` / `Password:`.
- **Colores reales**: PTY `xterm-256color` con soporte de 16, 256 colores y *truecolor*.
- **Escritura sin espera**: cada tecla se envía al instante (`TCP_NODELAY`, *flush* por pulsación y teclado en modo carácter a carácter). En la prueba automática, el comando escrito llegó al servidor en ~250 ms, contando adb.
- **Barra de accesos directos**: botones de un toque sobre las teclas extra (`tmux`, `htop`, `cd OpenManager`, `git status`…) definidos por perfil.
- Reconexión rápida desde la cápsula de sesión (`SSH ▾` / `TELNET ▾`), sesión activa en segundo plano y redimensionado de ventana remoto.
- Verificado en CI contra servidores **OpenSSH** y **Telnet** reales: login, huella, reconexión sin volver a preguntar, bloqueo de clave cambiada, accesos directos, escritura y auto-login de Telnet.

### ⚡ Modo de Alto Rendimiento: Ultra RAM
- Con el modo activado, ninguna pantalla supera **20 MB** de memoria propia de la app (Java + nativa): explorador ~19 MB, ajustes ~17 MB, editor ~20 MB, apps instaladas ~12 MB, terminal ~14 MB.
- Arranque en frío del explorador ~2 veces más rápido (~0,9 s frente a ~1,8 s).
- Las filas de archivos usan vistas de icono ligeras, BouncyCastle se carga solo al usar SFTP/SSH/BKS, las listas de charsets e idiomas se construyen solo al abrirlas, los logos del panel Root/Shizuku se decodifican al tamaño visible y el explorador oculto libera sus filas al quedar tapado.
- Con el modo **apagado**, la app se comporta y se ve exactamente igual que antes.

## Incluido también en la serie 2.0

### 📦 Instalador, Firmado y Verificación Nativa para `.apks`, `.xapk` y `.apkm`
- **Instalación Directa sin Root (`PackageInstaller.Session`) y por Root / Shizuku (`pm install-create`)**:
  - Ahora al pulsar **Instalar** sobre un paquete `.apks`, `.xapk` o `.apkm` en `SplitApkDialogFragment` (o desde la lista de archivos), OpenManager extrae y transmite todos los splits APK en orden (`base.apk` primero) dentro de una sesión multi-APK real de Android.
  - Nuevo `SplitApkInstallReceiver` con diagnóstico claro en español e inglés para conflictos de firma, incompatibilidad de arquitectura/SDK, almacenamiento o bloqueo del sistema, y tematización completa del diálogo de splits para **Modo Claro / Oscuro / AMOLED** mostrando el recuento real de splits detectados.
- **Firmado y Verificación Multi-APK (`ApkSignerHelper`)**:
  - Al pulsar **Firmar** o **Verificar firma** en `.apks`, `.xapk` o `.apkm`, firma todos los splits internos conservando metadatos/OBB intactos (con nombre de salida `<nombre>_signed.<ext>`) y valida que todos los splits estén firmados coherentemente con el mismo certificado.

### 🤖 Compatibilidad Android 16 en APKs Clonados y AntiSplit
- **Saneado Avanzado de Manifest (`ApkQuickEditEngine`)**:
  - Al clonar un paquete ahora reescribe automáticamente `<provider android:authorities>`, `<permission>`, `<uses-permission>` propios, `taskAffinity`, nombres de clase relativos y elimina `sharedUserId`, evitando los bloqueos típicos de `"La app no se instaló"` en Android 15/16.
- **AntiSplit Alineado y Firmado Limpio (`AntiSplitEngine` + `ApkSignerHelper`)**:
  - Alineado ZIP a **16 KB (`16384`)** para `.so` sin comprimir cuando `extractNativeLibs="false"`, `resources.arsc` sin comprimir a 4 bytes, limpieza de `META-INF`/`stamp-cert-sha256` y re-firmado V1/V2/V3 con avisos de firma diferente o `targetSdk < 24` y flag `--bypass-low-target-sdk-block` en Root/Shizuku.

### 🐧 Expansión Linux Termex Propia y Limpieza Segura Post-Update
- **Constructor Propio de Termex (`TerminalExpansionManager`)**:
  - Descarga el bootstrap oficial de Termux y construye automáticamente un APK nativo firmado (`com.y.om.termex`) compatible con Android moderno, normalizando `.so.<ver>` y resolviendo enlaces simbólicos sin depender de servidores externos caídos.
- **Limpieza Automática de APKs de Actualización**:
  - `UpdateCleanupReceiver` (`MY_PACKAGE_REPLACED`) e inicio en frío eliminan de forma segura únicamente el `.apk` de actualización ya instalado (verificando firma y versión) e invalidan la caché de directorio.

### 🔄 Sistema de Actualizaciones Mejorado (Auto-Updates Activas por Defecto)
- **Activadas por Defecto y Apagables al Instante**:
  - Las auto-comprobaciones vienen habilitadas tras actualizar, con interruptor directo tanto en **Ajustes** como dentro del propio diálogo de nueva versión y botón **Omitir versión**.
- **Motor Resiliente con Caché `ETag` y Descargas Reanudables (`Range 206`)**:
  - Diferimiento de 2.5 s al iniciar, guarda atómica de concurrencia, velocidad en tiempo real/ETA, verificación mágica ZIP (`PK`) y del paquete antes de instalar, e instalación sin redescarga si el APK ya está completo en disco.

### 💻 Rediseño Profesional del Terminal HUD y Teclas Extra (`v2.0.75`)
- **Cápsula Interactiva de Sesión y Barra HUD Workstation (`44dp`)**:
  - La antigua burbuja plana `SHELL` ahora es una cápsula de estado viva (`SH ▾` / `ROOT # ▾` / `BASH ▾`) con punto e halo de estado en tiempo real, cambio rápido entre entorno **Sistema** y **Termex**, chip de ruta actual tocable para copiar al portapapeles (`CWD`), control segmentado de zoom (`- | 14sp | +`), contador en vivo de `PID · mm:ss` en la barra superior y botón dedicado de señal de parada (`SIGINT` / `SIGKILL`).
- **Teclas Extra 3D Táctiles y Modificadores Inteligentes (`TerminalExtraKeysView`)**:
  - Superficies biseladas con ripple acotado, borde superior de `1dp`, 3 filas ergonómicas con tecla `⌨` para mostrar/ocultar el teclado del sistema, toque simple (*one-shot*) y pulsación larga para bloqueo fijo (`LOCKED`) en `Ctrl`/`Alt`, repetición continua segura por umbral de desplazamiento (`scaledTouchSlop`) y adaptación automática al tema Claro / Oscuro / AMOLED.
- **Estabilidad de Renderizado VSYNC e Insets IME (`DetachableSessionClient`)**:
  - Consolidación de repintado por frame `postOnAnimation` con `AtomicBoolean`, lectura acotada de trazas (16 KB) para evitar fugas de memoria o bloqueos en ráfagas rápidas de salida, transición edge-to-edge con ajuste preciso al teclado (`ime()`), nuevo icono vectorial transparente `>_` (`ic_terminal_notification_24dp`) para barra de estado/notificaciones del terminal y diálogo de preferencias del terminal tematizado con `OpenManagerPalette`.

### 🎨 Tematización Global (Claro / Oscuro / AMOLED), Iconografía y Rendimiento
- **Rediseño Total de Ajustes (`SettingsFragment` + `SettingsUiLogic` + `AppearancePerformanceHubPreference`)**:
  - **Barra de Búsqueda Instantánea y Saltos Rápidos**: Busca cualquier ajuste al instante por título, descripción o sinónimos en español/inglés (`miniaturas`, `AMOLED`, `vectoriales`, `root`, `APK`…) con cápsulas de acceso directo por categoría (`⚡ Rendimiento`, `🎨 Apariencia`, `🛡️ Root y Seguridad`, `📁 Archivos`, `📦 APK`, `🌐 Red y Medios`, `ℹ️ Sistema`).
  - **Hub Interactivo de Apariencia y Rendimiento**: Selector directo de 1 toque para tema **Claro / Oscuro / AMOLED / Sistema** + **Color de acento**, con sección dedicada **⚡ Rendimiento Visual y Miniaturas** para alternar entre **Completo**, **Optimizado (pausar al deslizar)** y **Solo iconos vectoriales** más el modo UI fluido de bajo consumo.
  - **Iconos Semánticos en Cápsula y Vector Monocromo de Shizuku (`SettingsIconHelper` + `ic_shizuku`)**: Cada preferencia muestra un icono vectorial en tarjeta adaptada al tema activo (incluyendo nuevo vector limpio de Shizuku tipo silueta monocroma tintada correctamente en Claro/Oscuro/AMOLED), y los menús de selección se abren en tarjetas interactivas con iconos y descripciones claras.
  - **Optimización de RAM, Caches y Hilo UI (`CoilInitializer` + `AppInitializers` + `FileListActivity`/`Fragment` + `InstalledApps`/`RunningApps`)**: Límite de caché Coil al 15 % con decodificación `RGB_565`, recorte escalonado en `onTrimMemory`, eliminación de fuga de listener Shizuku al recrear vistas, estadísticas `StatFs` asíncronas en segundo plano y guardas para evitar re-aplicaciones redundantes de tema y barra de herramientas.
  - **Optimización del Motor de Lista y Directorios Grandes (`FileListEngine` + `FileListLiveData` + `FileListAdapter` + `FileListFragment`)**: Carga paralela en trozos para carpetas de gran tamaño (`ITEM_LOADER_EXECUTOR`), ordenación y *DiffUtil* en segundo plano (`Dispatchers.Default`), caché de instantáneas acotada por elementos totales (`DirectorySnapshotCache`), búsqueda de iconos `O(1)` (`FileIconHelper`), resolución de etiquetas de apps sin bloqueo en hilo UI (`AppMetadataCache`), formateo compacto sin ICU y caché por fila de estilos, fechas, descripciones y miniaturas con tamaño acotado.
- **`OpenManagerPalette.applyToDialogSurface` en el Explorador y Visor SQLite**:
  - Corregido el texto blanco ilegible sobre fondo blanco en listas desplegables (`item_dialog_action_with_icon`) al usar **Modo Claro**.
  - Adaptados automáticamente el menú contextual de archivos (`MtFileMenuHelper`), menú de herramientas, hoja inferior de creación (`+`), diálogo APK (`OpenApkDialogFragment`), propiedades de archivo, permisos, firma de APK y el **Visor SQLite** (con soporte de apertura desde `content://` y tablas adaptadas a Claro/Oscuro/AMOLED).

### 🛡️ Estabilidad en Visores, Cifrado y Herramientas de Archivo
- **Corrección de 4 Cierres en Visor PDF y DOCX**: Reemplazado `androidx.core.content.FileProvider` (no registrado) por `Paths.get(...).fileProviderUri` al compartir o abrir externamente documentos locales o temporales.
- **Cifrado/Descifrado AES-256 Seguro (`FileEncryptDecryptHelper`)**: Escritura en archivo temporal atómico antes de reemplazar el destino (evita perder un archivo existente si se introduce una contraseña incorrecta) y borrado en memoria de claves/contraseñas.
- **Rutas SAF/Root/Archivo y Prevención de ANRs/Fugas**: Migración de `FileSplitMergeHelper`, `FileSwapNamesHelper`, `ExportFileListHelper`, `CreateSymlinkHelper`, `FilePropertiesScriptTabFragment`, `OpenApkDialogFragment` (inspección de manifiesto/componentes dentro de ZIP/Root), `ApkSignerHelper` y `FileListActivity` a corrutinas en segundo plano con guardas de ciclo de vida (`bindToLifecycle` / `isUiAlive`), cancelación de alcance en `SuperuserStatusPreference` y sin hilos sueltos en el hilo principal.

---

# OpenManager v1.9.73: Apertura Inteligente de Archivos, Ubicaciones Complejas y Auditor Avanzado de Scripts/ELF v2

### 📂 Apertura Inteligente de Archivos y Ubicaciones Complejas
- **Enrutamiento Inteligente según Tipo de Archivo (`SmartOpenActivity`)**:
  - **PDF**: Al abrir o tocar un PDF (detectado por MIME, extensión `.pdf` o cabecera mágica `%PDF-`, incluso sin extensión o como `application/octet-stream`), se abre inmediatamente en el Lector de PDF integrado, incorporando además **Abrir ubicación** en su menú.
  - **Vídeos**: Muestra un diálogo para elegir entre **Ver video** (visor integrado con soporte directo de URIs `content://` y botón superior **Abrir ubicación**) o **Abrir ubicación**.
  - **Archivos comprimidos (`.zip`, `.rar`, `.7z`, etc.) y otros archivos**: Al abrir desde otras apps ya no entra al ZIP como carpeta sin retorno; abre la **carpeta contenedora** en el Panel A (preservando el Panel B intacto), desplaza y resalta el archivo visualmente. Al entrar manualmente a un ZIP y pulsar Atrás o `..`, regresa a la carpeta padre resaltando ese mismo archivo.
- **Resolución Multi-Estrategia de Ubicaciones Externas (`SmartUriPathResolver` & `SmartFileOpenEngine`)**:
  - Resuelve `file://`, URIs SAF (`ExternalStorageProvider`, `DownloadsProvider`, `MediaDocumentsProvider`), columna `_data`, descriptores del kernel (`/proc/self/fd/<fd>` normalizando `/mnt/user`, `/mnt/pass_through`, `/mnt/runtime`, `/data/media`, `/sdcard` y `/mnt/media_rw`) y prefijos de `FileProvider`. Si la ruta padre es inaccesible sin Root, importa de forma idempotente en `Download/OpenManager_Recibidos`.

### 🛡️ Auditor de Scripts y Binarios ELF de Kernel v2
- **Inspección de Código Máquina en ELF Stripped/Estáticos (`ElfInspector` + `SyscallResolver`)**:
  - Desensamblado rápido de llamadas directas al kernel en **AArch64 (`svc #0` + `movz x8/w8, #NR`)** y **x86_64 (`syscall` + `mov eax/rax, #NR`)**, ampliando la tabla de syscalls del kernel (`bpf`, `init_module`, `finit_module`, `delete_module`, `chroot`, `pivot_root`, `setns`, `unshare`, `memfd_create`, `capset`, `prctl`, `seccomp`).
  - Detección en una sola pasada $O(N)$ sin compilación repetitiva de regex, detección de segmentos **RWX / pila ejecutable**, empaquetado **UPX!**, binarios sin símbolos (`stripped`) y selección correcta de `/system/bin/linker` vs `linker64` según arquitectura 32/64-bit o estático.
- **Desofuscación y Endurecimiento del Auditor de Scripts (`ScriptRunnerHelper`)**:
  - Unión de líneas continuadas con `\`, desofuscación de comillas vacías/intercaladas (`r'm'`), escapes (`\rm`) e `$IFS`, bloqueo de condición de carrera en ejecución anticipada, lectura acotada y tematización completa para **Modo Claro / Oscuro / AMOLED**.

---

# OpenManager v1.9.72: Temas Globales (Claro, Oscuro y AMOLED), Rendimiento Visual y Mejoras en Apps en Ejecución

### 🎨 Apariencia Global y Temas (Claro, Oscuro por Defecto y AMOLED)
- **Tematización Completa en Toda la App**:
  - Se incorporó `OpenManagerPalette` y soporte global para **Modo claro**, **Modo oscuro** (conservado como predeterminado) y **Modo AMOLED** (`#000000` puro), aplicando automáticamente colores, barras del sistema, menús emergentes y colores de acento en el Explorador de doble panel, Cajón lateral, Diálogo de ruta rápida, Gestor de Apps (`InstalledAppsActivity`) y Apps en Ejecución (`RunningAppsActivity`).
- **Opciones Visuales de Rendimiento y Estabilidad**:
  - Nuevos ajustes configurables: **Contraste entre paneles** (estándar, alto o uniforme), **Modo visual fluido / bajo consumo** (desactiva animaciones/marquees pesados y amplía la caché de vistas), **Carga de miniaturas e iconos** (completa, pausada al deslizar rápido o solo iconos) y **Divisores en la lista de archivos**.
  - Enlazados también el escalado de filas/iconos/texto (`FILE_LIST_SIZE`), visualización de permisos POSIX en rutas fuera de almacenamiento compartido y formatos configurables de fecha y hora.

### ⚙️ Apps en Ejecución en Tiempo Real
- **Pestaña `SH`, CPU por Intervalo y Seguridad al Cerrar**:
  - Pestaña renombrada a **`SH`** con ruta completa visible al tocar scripts ejecutables, medición real de CPU (%) por intervalo en tiempo real con refresco automático estable y confirmación con espera de **3 segundos** antes de finalizar procesos/scripts `SH`.

---

# OpenManager v1.9.71: Utilidades Visuales de Estabilidad, Auditoría de Scripts/ELF, Gestor de Apps y Visores Endurecidos

### 🛡️ Nuevas Utilidades Visuales y Estabilidad Global
- **`UiStabilityUtils` y Ciclo de Vida Seguro**:
  - Se incorporaron helpers reutilizables (`isUiAlive`, `dismissSafely`, `bindToLifecycle`, `showSafeDialog`, `runWithProgressDialog`, `resolveThemeColor` y `applyEdgeToEdgeInsets`) para prevenir fugas de ventana (`WindowLeaked`), fallos al cerrar o rotar pantalla y superposiciones con barras del sistema.
  - Optimización de memoria y rendimiento general con limpieza automática de caché en `onTrimMemory`, precarga de metadatos en segundo plano, cierre seguro de descriptores (`ZipFile`, `ProviderFtpFile`) y cancelación de trabajos en `ViewHolder`.

### ⚙️ Apps en Ejecución, Gestor de Apps y Diálogo de APK
- **Apps en Ejecución**:
  - Corregido el icono del menú lateral, añadidos iconos diferenciados para binarios ELF/SH, medición y ordenamiento por uso de CPU (%) y pestaña/filtro exclusivo para scripts ejecutables (`SH/ELF`).
  - Rediseño más limpio en títulos, pestañas, barra de RAM y persistencia al descartar el banner de permisos.
- **Gestor de Apps y Diálogo APK**:
  - Menú organizado por categorías con acceso rápido a ubicaciones de APK y datos (`/data/data`, `Android/data`) junto a doble confirmación en Deshabilitar/Desinstalar.
  - Diálogo de APK simplificado sin desplazamiento horizontal: botón único de instalación inteligente (auto-root / Shizuku / sistema) y menú de opciones en 3 puntos.

### 🔍 Auditoría de Seguridad y Visores Endurecidos
- **Auditoría de Scripts y Binarios ELF**:
  - Corregido el diálogo de auditoría en blanco y reforzado el detector de scripts y binarios ELF de kernel para mostrar avisos claros y fiables.
- **Visores y Editores**:
  - Endurecimiento y mejoras visuales Day/Night e I/O en segundo plano en visores rápidos y dedicados (DEX, Hexadecimal, ARSC, SQLite, PDF, Imágenes, Audio/Vídeo, Editor de texto con Undo por lotes y DOCX sin solapamiento con la barra superior).

---

# OpenManager v1.9.70: Internacionalización Completa (EN/ES), Estabilidad del Editor e Icono Adaptativo Ajustado

### 🌍 Soporte Multi-Idioma Completo (Inglés por Defecto y Español)
- **Migración Integral de Interfaz y Motores**:
  - Se migraron todos los layouts XML, menús, diálogos, visores y helpers a recursos localizables (`932` claves `om_*` sincronizadas entre `values/strings.xml` en inglés y `values-es/strings.xml` en español).
  - Motores internos y analizadores (`SyscallResolver`, `ElfInspector`, `SuperuserDetector`, `ScriptRunnerHelper`, `AxmlEngine`, `ArscEngine`, entre otros) traducidos al inglés manteniendo compatibilidad completa con pruebas JVM.
  - Añadido `README_en.md` junto al selector de idioma integrado (`LocalePreference` y soporte por aplicación en Android 13+).

### 🛡️ Estabilidad y Corrección de Errores
- **Editor de Texto (`TextEditorFragment`)**:
  - Corregido el crash `UninitializedPropertyAccessException: lateinit property argsFile has not been initialized` al inicializar y validar `extraPath` en `onCreate()` antes de registrar `launchWhenStarted`.
- **Verificación Interactiva**:
  - Probado en dispositivo/emulador con navegación entre pantallas, apertura de editor e inyección de 200 eventos (`monkey`) sin cierres inesperados.

### 🎨 Ajuste Visual del Icono de Escritorio
- **Margen Seguro en Icono Adaptativo**:
  - Aumentado el `android:inset` de `launcher_icon_foreground_inset.xml` de `14%` a `22%` para evitar que el zoom y máscara de launchers modernos recorten los bordes del icono en el escritorio.

---

# OpenManager v1.9.69: Renovación Visual de Iconos (Sin Fondo Blanco y Diseño Variado por Pantalla)

### 🎨 Nuevos Iconos de Alta Definición con Fondo Transparente
- **Icono Principal de la App (Launcher / Escritorio)**:
  - Se adoptó la variante **Full Moderno** con efecto 3D, profundidad metálica, tarjeta translúcida y letras `OM` en relieve, adaptada a todas las densidades Android (`mdpi` a `xxxhdpi`) y al formato adaptativo.
- **Barra Lateral / Menú de Navegación (32dp)**:
  - Se integró la variante **Ultra Simple** en el encabezado del menú lateral para mantener máxima nitidez y legibilidad en tamaño compacto.
- **Pantalla "Acerca de"**:
  - Se incorporó la variante **Simple Variado** en alta resolución para el logotipo principal dentro de la vista *Acerca de*.
- **Eliminación Total de Fondos Blancos**:
  - Limpieza precisa de bordes y halos blancos con anti-aliasing suave para integrarse limpiamente sobre temas oscuros, claros y fondos del sistema.

---

# OpenManager v1.9.68: Limpieza y Filtrado Estricto de Notas de Actualización (Solo Versión Más Reciente y Cero Textos Largos de Versiones Antiguas)

### 🧹 Información Exclusiva de la Versión Más Reciente
- **Supresión Total de Versiones Antiguas Acumuladas**:
  - Al abrir el diálogo de actualización disponible, ahora se muestran únicamente las novedades de la versión más reciente que se va a instalar.
  - Se eliminó por completo la concatenación de versiones intermedias y notas pasadas, evitando paredes interminables de texto de 65 KB.
- **Formato Limpio y Legible (Sin Basura de Markdown)**:
  - Eliminación automática de hashes crudos (`#`, `##`, `###`), negritas con asteriscos (`**`) y comillas invertidas de código (`` ` ``).
  - Encabezados claros (`Título:`) y viñetas elegantes (`•` y sub-viñetas `▫`), preservando intactos los iconos de navegación `[<] [>] [+] [⇆] [↑]`.
  - Supresión del encabezado duplicado con el nombre de versión dentro del registro de cambios.

### ⚙️ Automatización en CI
- **Publicación Aislada por Tag**:
  - El flujo de trabajo en GitHub Actions ahora extrae de forma automática y estricta únicamente la sección de la versión específica (`$TAG`), garantizando que los releases oficiales contengan únicamente sus notas correspondientes.

---

# OpenManager v1.9.67: Perfeccionamiento de Escala, Adaptación Dinámica a Gestos / 3 Botones, Barra Superior Compacta (44dp) y Compatibilidad de Recortes (Notch/Cutout)

### 📐 Adaptación Dinámica de la Barra Inferior a Botones y Gestos del Sistema
- **Cero Superposición en Navegación Clásica (3 Botones)**:
  - Se eliminó por completo el problema donde la barra de navegación del sistema (atrás, inicio, recientes) cubría los botones inferiores de OpenManager (`[<] [>] [+] [⇆] [↑]` y la barra de selección múltiple).
  - La barra inferior ahora calcula e incorpora en tiempo real el valor exacto de `navigationBars().bottom`, elevando los botones a una zona completamente libre y extendiendo el fondo oscuro (`#202020`) de forma continua hasta el borde inferior de la pantalla.
- **Transición Fluida entre Gestos y 3 Botones**:
  - Detección inmediata mediante `OnApplyWindowInsetsListener`: si el usuario cambia el estilo de navegación en los ajustes del sistema o rota la pantalla, la interfaz se reconfigura al instante sin necesidad de reiniciar la aplicación.
  - El ajuste manual «Agregar margen inferior» en Configuración funciona ahora como un incremento personalizado opcional, sin romper la geometría nativa del dispositivo.
  - Cierre automático de márgenes cuando se despliega el teclado virtual (IME).

### 📏 Optimización y Compactación de la Barra Superior ("Barra de arriba muy grande")
- **Diseño Compacto MT-Style de 44dp**:
  - Se redujo la altura de la barra superior principal (`mainToolbar`) de 56dp a 44dp (ahorrando 12dp de espacio vertical muerto y aumentando el área útil para los listados de archivos).
  - Los botones de navegación (`Atrás` y `Más opciones`) se rediseñaron a 38dp con iconos nítidos centrados.
  - La tipografía de la ruta (`13.5sp` en negrita) y subtítulo de estadísticas (`10sp`) se calibraron para máxima legibilidad sin desperdiciar altura.
  - La cabecera del cajón lateral (`layoutDrawerHeader`) adopta también la escala compacta de 44dp, corrigiendo el padding duplicado en capas de personalización OEM.

### 📱 Pantalla Completa, Visores y Diálogos
- **Visor DOCX / Word**:
  - Barra de herramientas compacta de 44dp.
  - Insets de recorte (punch-hole / notch) y barra de navegación aplicados a la vista de lectura web para que los párrafos finales nunca queden tapados.
- **Visor PDF**:
  - Barra de herramientas compacta de 44dp.
  - Ajuste dinámico de elevación del indicador de página flotante (`pageBadge`) sobre la barra de navegación de 3 botones.
- **Editor de Texto**:
  - Barra superior de 44dp y tira de estado MT a 26dp, maximizando el espacio de código y protegiendo los bordes horizontales contra notch en orientación horizontal.
- **Hoja de Opciones de Creación («Crear nuevo»)**:
  - Insets inferiores aplicados al BottomSheet para garantizar que todas las opciones (Carpeta, Archivo, ZIP y Keystore) sean 100% accesibles.

---

# OpenManager v1.9.66: Sistema de Actualización Inteligente Multi-Versión, Bypass de Rate Limit y Changelog Acumulativo

### 🚀 Detección Multi-Versión Inteligente (Sin Depender de la Etiqueta "Latest")
- **Consulta de Releases Múltiples**:
  - Se optimizó la consulta a la API de GitHub para recuperar el listado completo de versiones recientes (`/repos/uamo11/OpenManager/releases?per_page=20`) en lugar de limitarse al endpoint singular `/releases/latest`.
  - El sistema compara semánticamente cada versión devuelta (`isVersionNewer`), seleccionando con precisión matemática la versión más alta y moderna disponible, evitando que versiones intermedias queden ocultas si GitHub no las marcó como "latest".
  - Filtro automático de pre-lanzamientos y borradores («drafts»).

### 🛡️ Resiliencia Total: Bypass de Rate Limit de GitHub (HTTP 403) con Feed Atom
- **Tolerancia a Fallos y Mecanismo de Respaldo Zero-Rate-Limit**:
  - Si la API REST de GitHub devuelve error de límite de peticiones (HTTP 403 Rate Limit Exceeded) o fallo de red, el actualizador cambia instantáneamente y sin intervención del usuario al feed público Atom (`https://github.com/uamo11/OpenManager/releases.atom`).
  - El feed Atom no tiene límites de peticiones anónimas y se sirve desde la CDN global de GitHub, garantizando que el usuario siempre pueda comprobar y descargar actualizaciones.
  - Extracción y reconstrucción garantizada de enlaces directos a las APKs oficiales (`OpenManager-{version}-release.apk` y `OpenManager-{version}-debug.apk`).

### 📋 Historial de Cambios Acumulativo (Multi-Release Changelog)
- **Visualización Completa de Todas las Novedades Intermedias**:
  - Al actualizar desde versiones más antiguas (por ejemplo, desde v1.9.55 hacia v1.9.66), el diálogo compila y concatena cronológicamente las notas de versión de todas las versiones intermedias.
  - El usuario puede leer exactamente qué funciones, correcciones y novedades se han agregado en cada entrega desde su versión actual.

### 🎨 Perfeccionamiento Estético del Diálogo de Actualización
- **Botón de Descarga Destacado y A Ancho Completo**:
  - El botón principal «Descargar e instalar» se presenta en un contenedor Material prominente a ancho completo con icono y fondo verde de alta visibilidad, impidiendo que el texto se comprima o corte en cualquier tamaño de pantalla.
  - Botones secundarios «Más tarde» y «Ver en GitHub» dispuestos de manera simétrica en la barra inferior.
  - Desplazamiento fluido y lectura limpia del registro de cambios.

---

# OpenManager v1.9.65: Lector Nativo de Documentos Word (.docx y .doc), Temas de Lectura, Búsqueda y Zoom

### 📄 Motor de Lectura Nativo de Archivos Word (.docx)
- **Descompresión y Renderizado Rápido sin Dependencias Externas**:
  - Implementado motor propio de lectura OOXML (`DocxDocumentEngine.kt`) que procesa `word/document.xml`, relaciones (`_rels`) y recursos multimedia (`word/media/*`).
  - Soporte completo para párrafos, jerarquías de encabezados (Título 1, Título 2, Título 3), listas numeradas y con viñetas, tablas estructuradas con bordes estilizados, bloques de citas, texto enriquecido (negrita, cursiva, subrayado, tachado, colores RGB y resaltados).
  - Extracción e incrustación de imágenes del documento (`png`, `jpeg`, `gif`, `bmp`, `webp`) adaptadas al ancho de pantalla.
  - Compatibilidad de lectura con documentos binarios heredados de Microsoft Word 97-2003 (`.doc`) mediante extractor de texto OLE2.

### 🌓 Temas de Lectura Personalizados y Escalado de Texto
- **Modos Día, Noche y Sepia**:
  - Modo Día: fondo blanco con tipografía oscura clásica de alto contraste.
  - Modo Noche: fondo negro profundo OLED `#121212` con texto gris claro suave `#E0E0E0` para mínima fatiga ocular.
  - Modo Sepia: fondo cálido `#FBF0D9` con tipografía café `#5F4B32` emulando papel de libro.
- **Control de Tamaño de Letra (80% a 150%)**:
  - Ajuste fluido de escala tipográfica con preajustes rápidos (Compacto 85%, Normal 100%, Grande 120%, Enorme 140%) y persistencia en preferencias.

### 🔍 Búsqueda Interactiva, Exportación a PDF y Herramientas
- **Búsqueda en Tiempo Real con Conteo de Coincidencias**:
  - Barra superior de búsqueda con resaltado interactivo, navegación anterior/siguiente y contador de coincidencias en vivo (ej. «1 de 4»).
- **Exportación e Impresión a PDF**:
  - Integración nativa con el servicio de impresión de Android (`PrintManager`) para exportar o imprimir documentos directamente a PDF.
- **Copia de Texto y Ficha de Propiedades**:
  - Acceso directo para copiar todo el contenido al portapapeles y diálogo con estadísticas del documento (palabras, caracteres, párrafos, tablas e imágenes).

---

# OpenManager v1.9.64: Visor Nativo de Archivos PDF con Zoom Vectorial, Temas de Lectura y Navegación Rápida

### 📑 Renderizado Vectorial de Alta Calidad y Gestos Multitáctil
- **Motor Basado en `PdfRenderer`**:
  - Renderizado nítido a resolución nativa de cada página con eliminación de pixelación.
  - Zoom fluido de 1.0x a 5.0x mediante gestos táctiles de pellizco (`ScaleGestureDetector`) y doble toque.
- **Modos de Visualización Día, Noche y Sepia**:
  - Inversión cromática de alta fidelidad para documentos PDF en modo Noche y Sepia.
- **Navegación Rápida y Salto a Página**:
  - Diálogo de salto numérico directo a cualquier página («Ir a página X de N»).
  - Desplazamiento vertical continuo y modo inmersivo a pantalla completa.
  - Impresión y exportación nativa a través del framework de impresión del sistema.

---

# OpenManager v1.9.63: Blindaje de Seguridad Deadman para Particiones Críticas y Resiliencia de Insets

### 🛡️ Protección de Particiones y Detección de Bloques Críticos
- **Salvaguardas para `/dev/block` y Particiones de Arranque**:
  - Prevención de modificaciones accidentales en particiones vitales del dispositivo (`boot`, `recovery`, `system`, `vendor`, `super`, etc.).
  - Inspección segura de cabeceras de partición y bypass de bloqueos SELinux.
- **Resiliencia de Insets de Teclado**:
  - Adaptación fluida ante apertura y cierre de teclado virtual en Android 10 a 15 sin parpadeos ni solapamientos.

---

# OpenManager v1.9.62: Extracción y Volcado Seguro de Particiones a Archivos .img

### 💾 Extracción Directa de Particiones a Almacenamiento
- **Operaciones de Superusuario en `/dev/block/by-name`**:
  - Corrección de `AccessDeniedException` al copiar bloques de particiones protegidas (como `boot_b`, `boot_a`, `init_boot`).
  - Enrutamiento por comandos elevados de superusuario (`su dd / cat`) para volcar particiones directamente a archivos `.img` en `/sdcard` o tarjetas SD externas.
  - Verificación previa de espacio libre disponible antes de iniciar la extracción.

---

# OpenManager v1.9.61: Refinamiento de Botones y Diseño del Diálogo de Actualización

### 🎨 Corrección de Espaciado y Botones en Pantallas Angostas
- Eliminado el problema donde el botón de actualización se comprimía verticalmente mostrando letras apiladas.
- Calibración de márgenes, padding y peso tipográfico en diálogos de actualización.

---

# OpenManager v1.9.60: Corrección de Insets de Barra Superior y Rediseño de Acciones

### 📱 Optimización de Barra de Estado y Header Dual-Panel
- Adaptación precisa al área segura de la barra de estado y cámara frontal (notch / cutout) en modo de panel doble.
- Organización jerárquica de botones de acción en actualizaciones.

---

# OpenManager v1.9.59: Optimización de Rendimiento de Inicio y Ajustes de Detalles

### ⚡ Corrección de Retardos de Apertura y Formateo
- Reducción del retardo inicial al listar carpetas voluminosas.
- Formato limpio y sin truncamiento en el texto del encabezado MT Manager y tamaños de disco.

---

# OpenManager v1.9.58: Control en Ajustes para Mover a la Papelera al Eliminar (OFF por Defecto) y Ocultamiento de la Barra Lateral

### 🗑️ Supresión de la Papelera al Borrar por Defecto
- **Eliminación Directa y Limpia sin Avisos de Papelera**:
  - Se solucionó el comportamiento por el cual al borrar archivos se preguntaba si se deseaban mover a la papelera de reciclaje.
  - Se añadió la opción **«Mover a la papelera al eliminar archivos»** en Ajustes (`Ajustes > 4. Papelera de Reciclaje`), la cual viene **desactivada por defecto (OFF)**.
  - Al estar desactivada (estado predeterminado), el diálogo de eliminación muestra una confirmación directa y limpia (`¿Eliminar “archivo”?` o `¿Eliminar X archivos?`) sin casillas, avisos ni menciones de papelera de reciclaje, borrando los archivos directamente.

### 🚫 Ocultamiento Completo de la Papelera en la Barra Lateral
- **No Aparece a Menos que se Active en Ajustes**:
  - La herramienta `4. Papelera de Reciclaje` en el menú lateral (drawer) ahora se oculta de forma automática (`View.GONE`) cuando la opción está desactivada en Ajustes.
  - No aparece en la barra lateral ni molesta al usuario a menos que éste decida activarla explícitamente desde Ajustes.

### ⚙️ Habilitación Manual y Sub-Opciones Dependientes
- **Control Total para el Usuario**:
  - Únicamente si el usuario entra a `Ajustes > 4. Papelera de Reciclaje` y activa manualmente el interruptor:
    1. Se hace visible el elemento `Papelera de Reciclaje` en la barra lateral con navegación a su directorio protegido.
    2. En el diálogo de eliminación aparece la casilla `[ ] Mover a la papelera de reciclaje`.
    3. Se habilitan las opciones dependientes: marcar por defecto y auto-limpieza programada (a los 7 o 30 días).

---

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
