// ==========================================================================
// OpenManager Web - High Performance Script with i18n & Category Filter
// ==========================================================================

const i18nData = {
  es: {
    nav_features: "Características",
    nav_screenshots: "Capturas",
    nav_comparison: "Comparativa",
    nav_build: "Compilar",
    nav_releases: "Releases",
    hero_badge: "Versión <strong id=\"latestTag\">v1.9.69</strong> • Alternativa Open-Source a MT Manager",
    hero_title: "Gestión Dual-Panel & <br><span class=\"gradient-text\">Suite de Modding</span> en tu Móvil",
    hero_subtitle: "El administrador de archivos definitivo para Android. Totalmente <strong>código abierto</strong>, sin suscripciones de pago y sin anuncios. Descompila con JADX, edita Smali/DEX en tiempo real, inspecciona con Data Inspector, fusiona Split APKs y opera con Shizuku o Root.",
    btn_releases: "Ver Versiones en GitHub",
    btn_github: "Explorar Repositorio",
    metric_1: "Código Abierto (GPLv3)",
    metric_2: "Lenguajes con Sintaxis",
    metric_3: "Sin Suscripciones VIP",
    metric_4: "Firma Digital & AntiSplit",
    features_title: "Potencia Profesional, Control Total",
    features_subtitle: "Explora los 4 pilares centrales de OpenManager diseñados para usuarios avanzados, modders y desarrolladores.",
    cat_all: "Todos los Módulos",
    cat_explorer: "🗂️ Explorador & Red",
    cat_re: "🛠️ Ingeniería Inversa",
    cat_apk: "📦 Modding & APKs",
    cat_root: "🛡️ Root & Seguridad",
    feat_1_title: "Doble Panel Simultáneo (Estilo MT)",
    feat_1_desc: "Navegación dividida en dos paneles A/B independientes. Copia, mueve y sincroniza carpetas al instante con historial breadcrumbs y estadísticas de disco en tiempo real.",
    feat_2_title: "Editor DEX & DEX Plus (Baksmali)",
    feat_2_desc: "Desensambla y ensambla Smali en tiempo real. Explora paquetes, clases, métodos, campos y tabla de cadenas. Guarda cambios atómicos directamente en el archivo .dex.",
    feat_3_title: "Descompilador JADX Integrado",
    feat_3_desc: "Inspecciona el código fuente en Java legible y limpio de cualquier APK o archivo DEX instalado o descargado en segundos, sin requerir una PC.",
    feat_4_title: "Editor HEX con Data Inspector",
    feat_4_desc: "Visor virtualizado de alto rendimiento (16 bytes/fila). Inspector de tipos en vivo (Int8/16/32 LE, Float32, Binario, ASCII), búsqueda hex/texto y hashes MD5, SHA-1 y SHA-256.",
    feat_5_title: "AntiSplit: Fusión de Split APKs",
    feat_5_desc: "Detecta y fusiona paquetes divididos (.apks, .xapk o splits) en un único archivo APK universal, optimizado y autofirmado para su instalación limpia.",
    feat_6_title: "Firmador APK & Keystore Generator",
    feat_6_desc: "Firma con esquemas V1, V2 y V3. Genera tus propias claves criptográficas RSA 2048/4096 (.jks, .bks, .pk8+.pem) en la propia app con BouncyCastle sin keytool.",
    feat_7_title: "DFROOT \"Hombre Muerto\" (Dead Man)",
    feat_7_desc: "Escudo exclusivo contra brickeos para dispositivos con DFRoot / DirtyFlag. Bloquea escrituras destructivas en /dev/block/ o particiones críticas con aviso de riesgo de 5s.",
    feat_8_title: "Shizuku Nativo & Root Elevado",
    feat_8_desc: "Soporte total para Magisk, KernelSU y APatch. Además, conéctate a Shizuku mediante Binder para acceder a /Android/data y /Android/obb en Android 11 a 15 sin Root.",
    feat_9_title: "Editor de Código O(N)",
    feat_9_desc: "Tokenizador lineal para 39 lenguajes. Barra de atajos de teclado (flechas, TAB, sangría), alternador interactivo LF ⇄ CRLF, codificaciones y búsqueda con Regex.",
    feat_10_title: "Monitor de RAM & Procesos",
    feat_10_desc: "Supervisa la memoria física real (PSS, VmRSS, Java Heap) de cada app en ejecución. Cierre forzoso seguro con insignias de elevación 🛡️ ROOT y ⚡ SHIZUKU.",
    feat_11_title: "Terminal Integrada con Soporte Root",
    feat_11_desc: "Shell interactiva con teclado extendido (Esc, Tab, Ctrl, Alt). Ejecuta scripts en /data/local/tmp, inspecciona syscalls, revisa códigos de salida (EXIT 0) o vincula Termux.",
    feat_12_title: "Servidor FTP & Reproductor Flotante",
    feat_12_desc: "Comparte archivos inalámbricamente con tu PC con el servidor FTP integrado. Conéctate a SFTP, SMB y WebDAV, y escucha música con el reproductor flotante sin detenerte.",
    gallery_title: "Galería de Capturas en Vivo",
    gallery_subtitle: "Haz clic en cualquier imagen para verla en resolución completa.",
    cap_1: "Doble Panel & Audio MP3",
    cap_2: "Menú de Acciones MT",
    cap_3: "Editor de Código O(N)",
    cap_4: "Selector de 39 Sintaxis",
    cap_5: "Suite APK, JADX & Split",
    cap_6: "Monitor RAM & Procesos",
    cap_7: "Terminal Root (#)",
    cap_8: "Ejecución de Scripts (EXIT 0)",
    comp_title: "OpenManager frente a MT Manager",
    comp_subtitle: "Libertad total, privacidad garantizada y todas las herramientas avanzadas sin muros de pago.",
    th_feature: "Característica / Módulo",
    th_mt: "MT Manager (Original)",
    th_om: "OpenManager",
    row_1_name: "Licencia & Código",
    row_1_mt: "Propietario / Código Cerrado",
    row_1_om: "100% Open Source (GPLv3)",
    row_2_name: "Publicidad & Telemetría",
    row_2_mt: "Contiene anuncios y rastreadores",
    row_2_om: "Cero Anuncios / 100% Privado",
    row_3_name: "Doble Panel Simultáneo",
    row_3_mt: "✅ Sí",
    row_3_om: "✅ Paridad Completa",
    row_4_name: "Edición DEX & Smali",
    row_4_mt: "Requiere VIP (Pago)",
    row_4_om: "✅ Gratis para Siempre",
    row_5_name: "Descompilador JADX",
    row_5_mt: "Requiere VIP (Pago)",
    row_5_om: "✅ Gratis para Siempre",
    row_6_name: "Editor HEX & Data Inspector",
    row_6_mt: "✅ Sí",
    row_6_om: "✅ Virtualizado & Checksums",
    row_7_name: "Editor ARSC & AXML Binario",
    row_7_mt: "Requiere VIP (Pago)",
    row_7_om: "✅ Gratis para Siempre",
    row_8_name: "Unificador AntiSplit (Split APKs)",
    row_8_mt: "✅ Sí",
    row_8_om: "✅ Integrado con Autofirma",
    row_9_name: "Integración Nativa con Shizuku",
    row_9_mt: "Limitada / Sin Binder nativo",
    row_9_om: "✅ Android 11–15 Nativo",
    row_10_name: "Blindaje DFROOT \"Hombre Muerto\"",
    row_10_mt: "❌ No disponible",
    row_10_om: "✅ Protección Anti-Brick",
    row_11_name: "Generador de Keystores en App",
    row_11_mt: "Limitado",
    row_11_om: "✅ RSA 2048/4096 (JKS, BKS)",
    build_title: "Compilar desde el Código Fuente",
    build_subtitle: "Requiere JDK 21 y Android SDK configurado.",
    btn_copy: "Copiar",
    btn_copied: "¡Copiado!",
    cta_title: "¿Listo para probar OpenManager?",
    cta_subtitle: "Explora las versiones oficiales, notas de cambios y archivos publicados directamente en GitHub Releases.",
    cta_btn_rel: "Ir a GitHub Releases",
    cta_btn_star: "Dar una Estrella ⭐",
    footer_desc: "Gestor de archivos de doble panel y suite de ingeniería inversa para Android.",
    foot_proj: "Proyecto",
    foot_issues: "Reportar Fallo",
    foot_sec: "Seguridad",
    foot_priv: "Privacidad",
    foot_contact: "Contacto"
  },
  en: {
    nav_features: "Features",
    nav_screenshots: "Screenshots",
    nav_comparison: "Comparison",
    nav_build: "Build",
    nav_releases: "Releases",
    hero_badge: "Version <strong id=\"latestTag\">v1.9.69</strong> • Open-Source Alternative to MT Manager",
    hero_title: "Dual-Panel Explorer & <br><span class=\"gradient-text\">Modding Suite</span> on Android",
    hero_subtitle: "The ultimate Android file manager. Fully <strong>open-source</strong>, zero paid VIP subscriptions, and no ads. Decompile with JADX, edit Smali/DEX live, inspect in HEX with Data Inspector, merge Split APKs, and operate with Shizuku or Root.",
    btn_releases: "View Releases on GitHub",
    btn_github: "Explore Repository",
    metric_1: "Open Source (GPLv3)",
    metric_2: "Languages with Syntax",
    metric_3: "No VIP Subscriptions",
    metric_4: "Digital Signing & AntiSplit",
    features_title: "Professional Power, Total Control",
    features_subtitle: "Explore OpenManager's 4 core pillars engineered for power users, modders, and developers.",
    cat_all: "All Modules",
    cat_explorer: "🗂️ Explorer & Network",
    cat_re: "🛠️ Reverse Engineering",
    cat_apk: "📦 Modding & APKs",
    cat_root: "🛡️ Root & Security",
    feat_1_title: "Dual-Panel Explorer (MT-Style)",
    feat_1_desc: "Simultaneous split browsing with two independent A/B panels. Copy, move, and sync folders instantly with breadcrumbs history and live disk stats.",
    feat_2_title: "DEX & DEX Plus Editor (Baksmali)",
    feat_2_desc: "Real-time Smali disassembly and assembly. Browse packages, classes, methods, fields, and string table. Save changes directly back into the .dex file.",
    feat_3_title: "Integrated JADX Decompiler",
    feat_3_desc: "Inspect clean, readable Java source code from any installed or downloaded APK/DEX in seconds, without a computer.",
    feat_4_title: "HEX Editor with Data Inspector",
    feat_4_desc: "High-performance virtualized 16 bytes/row viewer. Live data type inspector (Int8/16/32 LE, Float32, Binary, ASCII), hex/text search, and MD5/SHA-1/SHA-256.",
    feat_5_title: "AntiSplit: Split APK Merger",
    feat_5_desc: "Detect and merge split packages (.apks, .xapk, or split sets) into a single standalone, optimized, and auto-signed universal APK.",
    feat_6_title: "APK Signer & Keystore Generator",
    feat_6_desc: "Sign with schemes V1, V2, and V3. Generate custom RSA 2048/4096-bit cryptographic keys (.jks, .bks, .pk8+.pem) in-app with BouncyCastle.",
    feat_7_title: "DFROOT \"Dead Man\" Boot Shield",
    feat_7_desc: "Exclusive anti-brick protection for DFRoot / DirtyFlag devices. Blocks destructive writes to /dev/block/ or system partitions with a 5s warning dialog.",
    feat_8_title: "Native Shizuku & Elevated Root",
    feat_8_desc: "Full support for Magisk, KernelSU, and APatch. Connect to Shizuku via Binder for rootless access to /Android/data and /Android/obb on Android 11 to 15.",
    feat_9_title: "High-Speed O(N) Code Editor",
    feat_9_desc: "Linear background tokenizer supporting 39 languages. Keyboard shortcut bar (arrows, TAB, indentation), LF ⇄ CRLF toggle, encodings, and Regex search.",
    feat_10_title: "Live RAM & Process Monitor",
    feat_10_desc: "Monitor real physical memory (PSS, VmRSS, Java Heap) for every active process. Safe force-stop with elevation badges 🛡️ ROOT and ⚡ SHIZUKU.",
    feat_11_title: "Built-in Terminal with Root Support",
    feat_11_desc: "Interactive shell with expanded keyboard (Esc, Tab, Ctrl, Alt). Execute scripts in /data/local/tmp, check syscalls, return codes (EXIT 0), or hook Termux.",
    feat_12_title: "FTP Server & Floating Music Player",
    feat_12_desc: "Share files wirelessly with your PC via built-in FTP server. Connect to SFTP, SMB, and WebDAV, and enjoy audio with the floating modal player.",
    gallery_title: "Real Device Screenshots",
    gallery_subtitle: "Click any image to view in full resolution.",
    cap_1: "Dual Panel & MP3 Audio",
    cap_2: "MT Actions Menu",
    cap_3: "O(N) Code Editor",
    cap_4: "39 Syntax Selector",
    cap_5: "APK Suite, JADX & Split",
    cap_6: "RAM & Process Monitor",
    cap_7: "Root Shell Terminal (#)",
    cap_8: "Script Runner (EXIT 0)",
    comp_title: "OpenManager vs MT Manager",
    comp_subtitle: "Total freedom, guaranteed privacy, and all advanced tools without paywalls.",
    th_feature: "Feature / Module",
    th_mt: "MT Manager (Original)",
    th_om: "OpenManager",
    row_1_name: "License & Code",
    row_1_mt: "Proprietary / Closed Source",
    row_1_om: "100% Open Source (GPLv3)",
    row_2_name: "Ads & Telemetry",
    row_2_mt: "Contains ads and trackers",
    row_2_om: "Zero Ads / 100% Private",
    row_3_name: "Simultaneous Dual-Panel",
    row_3_mt: "✅ Yes",
    row_3_om: "✅ Full Parity",
    row_4_name: "DEX & Smali Editing",
    row_4_mt: "Requires VIP (Paid)",
    row_4_om: "✅ Free Forever",
    row_5_name: "JADX Decompiler",
    row_5_mt: "Requires VIP (Paid)",
    row_5_om: "✅ Free Forever",
    row_6_name: "HEX Editor & Data Inspector",
    row_6_mt: "✅ Yes",
    row_6_om: "✅ Virtualized & Checksums",
    row_7_name: "ARSC & Binary AXML Editor",
    row_7_mt: "Requires VIP (Paid)",
    row_7_om: "✅ Free Forever",
    row_8_name: "AntiSplit Merger",
    row_8_mt: "✅ Yes",
    row_8_om: "✅ Built-in & Auto-Signed",
    row_9_name: "Native Shizuku Integration",
    row_9_mt: "Limited / No native Binder",
    row_9_om: "✅ Android 11–15 Native",
    row_10_name: "DFROOT Boot Shield",
    row_10_mt: "❌ Not Available",
    row_10_om: "✅ Anti-Brick Protection",
    row_11_name: "In-App Keystore Generator",
    row_11_mt: "Limited",
    row_11_om: "✅ RSA 2048/4096 (JKS, BKS)",
    build_title: "Build from Source",
    build_subtitle: "Requires JDK 21 and configured Android SDK.",
    btn_copy: "Copy",
    btn_copied: "Copied!",
    cta_title: "Ready to experience OpenManager?",
    cta_subtitle: "Browse official releases, changelogs, and published packages directly on GitHub Releases.",
    cta_btn_rel: "Go to GitHub Releases",
    cta_btn_star: "Star on GitHub ⭐",
    footer_desc: "Dual-panel file manager & reverse engineering suite for Android.",
    foot_proj: "Project",
    foot_issues: "Report Issue",
    foot_sec: "Security",
    foot_priv: "Privacy",
    foot_contact: "Contact"
  },
  zh: {
    nav_features: "核心功能",
    nav_screenshots: "界面截图",
    nav_comparison: "对比分析",
    nav_build: "源码编译",
    nav_releases: "发布版本",
    hero_badge: "版本 <strong id=\"latestTag\">v1.9.69</strong> • MT 管理器开源全功能替代",
    hero_title: "移动端双面板浏览 & <br><span class=\"gradient-text\">逆向工程套件</span>",
    hero_subtitle: "适用于 Android 的终极文件管理器。完全<strong>开源免费</strong>，无 VIP 付费订阅，零广告。集成 JADX 源码查看、Smali/DEX 实时编辑、带数据检查器的十六进制编辑器、AntiSplit 拆包合并与 Shizuku/Root 特权支持。",
    btn_releases: "前往 GitHub 查看版本",
    btn_github: "浏览源码仓库",
    metric_1: "开源协议 (GPLv3)",
    metric_2: "编程语言语法高亮",
    metric_3: "无 VIP / 永久免费",
    metric_4: "全方案数字签名 & AntiSplit",
    features_title: "极致掌控，强大易用",
    features_subtitle: "专为 Android 高级玩家、Modder 与开发者打造的 4 大核心支柱。",
    cat_all: "全部模块",
    cat_explorer: "🗂️ 文件管理与网络",
    cat_re: "🛠️ 逆向工程套件",
    cat_apk: "📦 APK 工具与修改",
    cat_root: "🛡️ Root 与安全防护",
    feat_1_title: "双面板并行文件浏览（MT 风格）",
    feat_1_desc: "A/B 双面板独立浏览与并行操作。左右面板间一键极速复制、移动与同步，支持完整面包屑历史与实时磁盘统计。",
    feat_2_title: "DEX & DEX Plus 编辑器（Baksmali）",
    feat_2_desc: "集成 Baksmali 实时反汇编与回编译 Smali。层级浏览类、方法、字段及全局字符串池，原子性直接写回 .dex 文件。",
    feat_3_title: "集成 JADX 反编译引擎",
    feat_3_desc: "在手机上直接将 APK/DEX 反编译为整洁可读的 Java 源代码，无需 PC 即可秒级阅读分析逻辑。",
    feat_4_title: "HEX 虚拟化编辑器与数据检查器",
    feat_4_desc: "每行 16 字节流畅滚动。内置数据类型实时解析器（Int8/16/32 LE、Float32、二进制、ASCII），支持十六进制搜索与校验和计算。",
    feat_5_title: "AntiSplit：拆分包合并引擎",
    feat_5_desc: "智能检测并将分包（.apks、.xapk 或 splits 集合）自动融合成独立、自签名的通用单 APK 安装包。",
    feat_6_title: "APK 签名器与密钥生成器",
    feat_6_desc: "全面支持 V1、V2 与 V3 签名方案。基于 BouncyCastle 在手机端直接生成 RSA 2048/4096 密钥（.jks、.bks、.pk8+.pem）。",
    feat_7_title: "DFROOT \"死人开关\"防变砖盾牌",
    feat_7_desc: "专为 DFRoot / DirtyFlag 研发的系统防护机制。智能拦截危险底层命令，并在涉及核心分区时弹出 5 秒倒计时安全警告。",
    feat_8_title: "原生 Shizuku 与 Root 提权",
    feat_8_desc: "全面兼容 Magisk、KernelSU 与 APatch。免 Root 借助 Shizuku Binder 访问 Android 11 至 15 的 /Android/data 与 /Android/obb。",
    feat_9_title: "O(N) 高性能代码编辑器",
    feat_9_desc: "支持 39 种语言的高亮分析。配备键盘快捷键栏（方向键、TAB、缩进），一键切换 LF ⇄ CRLF 与正则查找替换。",
    feat_10_title: "实时 RAM 与进程监控",
    feat_10_desc: "监控每个进程的物理内存占用（PSS、VmRSS、Java Heap）。安全强行停止并带有 🛡️ ROOT 与 ⚡ SHIZUKU 提权徽章。",
    feat_11_title: "内置 Root 终端仿真器",
    feat_11_desc: "配备扩展虚拟按键的交互式终端（Esc、Tab、Ctrl、Alt）。运行 /data/local/tmp 脚本，检查返回值（EXIT 0）或调用 Termux。",
    feat_12_title: "FTP 无线传输与悬浮播放器",
    feat_12_desc: "内置 FTP 服务器支持手机与电脑无线互传文件，支持 SFTP/SMB/WebDAV 客户端，并配备独立悬浮音乐播放器。",
    gallery_title: "真实设备操作截图",
    gallery_subtitle: "点击任意图片即可全屏查看高清大图。",
    cap_1: "双面板与悬浮音频",
    cap_2: "MT 快捷管理菜单",
    cap_3: "O(N) 高敏代码编辑器",
    cap_4: "39 种语法高亮选择",
    cap_5: "APK 工具、JADX 与拆包",
    cap_6: "实时内存与 Root 进程",
    cap_7: "Root 终端仿真器 (#)",
    cap_8: "脚本交互运行 (EXIT 0)",
    comp_title: "OpenManager 对比 MT 管理器",
    comp_subtitle: "彻底自由，无隐私泄露，去除所有 VIP 付费限制。",
    th_feature: "核心功能 / 模块",
    th_mt: "MT 管理器 (MT Manager)",
    th_om: "OpenManager",
    row_1_name: "代码与协议",
    row_1_mt: "闭源商业软件",
    row_1_om: "100% 开源 (GPLv3)",
    row_2_name: "广告与隐私追踪",
    row_2_mt: "包含商业广告与追踪",
    row_2_om: "零广告 / 零隐私收集",
    row_3_name: "双面板并行管理",
    row_3_mt: "✅ 支持",
    row_3_om: "✅ 完整对标",
    row_4_name: "DEX & Smali 实时编辑",
    row_4_mt: "需开通 VIP 付费",
    row_4_om: "✅ 永久免费",
    row_5_name: "JADX Java 源码查看",
    row_5_mt: "需开通 VIP 付费",
    row_5_om: "✅ 永久免费",
    row_6_name: "HEX 编辑器与数据检查器",
    row_6_mt: "✅ 支持",
    row_6_om: "✅ 极速虚拟化与校验和",
    row_7_name: "ARSC 与二进制 AXML 编辑",
    row_7_mt: "需开通 VIP 付费",
    row_7_om: "✅ 永久免费",
    row_8_name: "AntiSplit 拆包合并",
    row_8_mt: "✅ 支持",
    row_8_om: "✅ 内置并支持自动签名",
    row_9_name: "原生 Shizuku 免 Root 适配",
    row_9_mt: "有限 / 非深度集成",
    row_9_om: "✅ Android 11–15 原生直连",
    row_10_name: "DFROOT 防变砖保护系统",
    row_10_mt: "❌ 无",
    row_10_om: "✅ 系统级安全护盾",
    row_11_name: "手机端 Keystore 密钥生成",
    row_11_mt: "功能受限",
    row_11_om: "✅ RSA 2048/4096 (JKS, BKS)",
    build_title: "从源码编译",
    build_subtitle: "需要安装 JDK 21 和配置好的 Android SDK。",
    btn_copy: "复制命令",
    btn_copied: "已复制!",
    cta_title: "准备好体验 OpenManager 了吗？",
    cta_subtitle: "在 GitHub Releases 官方页面查阅发布日志并获取构建文件。",
    cta_btn_rel: "前往 GitHub Releases",
    cta_btn_star: "在 GitHub 点亮 Star ⭐",
    footer_desc: "适用于 Android 的双面板文件管理器与移动端逆向工程套件。",
    foot_proj: "项目相关",
    foot_issues: "反馈问题",
    foot_sec: "安全策略",
    foot_priv: "隐私声明",
    foot_contact: "开发者联系"
  }
};

let currentLang = 'es';
let activeGalleryIndex = 0;

function storageGet(key) {
  try { return localStorage.getItem(key); } catch (_) { return null; }
}

function storageSet(key, val) {
  try { localStorage.setItem(key, val); } catch (_) {}
}

const extraI18n = {
  es: { skip_content: 'Saltar al contenido', btn_download_apk: 'Descargar APK' },
  en: { skip_content: 'Skip to content', btn_download_apk: 'Download APK' },
  zh: { skip_content: '跳转到主要内容', btn_download_apk: '下载 APK' }
};

document.addEventListener('DOMContentLoaded', () => {
  // 1. Language detection & initialization
  const savedLang = storageGet('om_lang');
  const browserLang = (navigator.language || 'es').slice(0, 2).toLowerCase();

  if (savedLang && i18nData[savedLang]) {
    currentLang = savedLang;
  } else if (browserLang === 'zh') {
    currentLang = 'zh';
  } else if (browserLang === 'en') {
    currentLang = 'en';
  } else {
    currentLang = 'es';
  }

  const langSelect = document.getElementById('langSelect');
  if (langSelect) {
    langSelect.value = currentLang;
    langSelect.addEventListener('change', (e) => {
      setLanguage(e.target.value);
    });
  }

  applyLanguage(currentLang);

  // 2. Accessible Mobile Navigation Toggle
  const menuToggle = document.getElementById('menuToggle');
  const navLinks = document.getElementById('navLinks');

  const closeMenu = () => {
    if (!menuToggle || !navLinks) return;
    navLinks.classList.remove('active');
    menuToggle.setAttribute('aria-expanded', 'false');
  };

  if (menuToggle && navLinks) {
    menuToggle.addEventListener('click', (e) => {
      e.stopPropagation();
      const isOpen = navLinks.classList.toggle('active');
      menuToggle.setAttribute('aria-expanded', String(isOpen));
    });

    navLinks.querySelectorAll('a').forEach(link => {
      link.addEventListener('click', closeMenu);
    });

    document.addEventListener('click', (e) => {
      if (navLinks.classList.contains('active') && !navLinks.contains(e.target) && !menuToggle.contains(e.target)) {
        closeMenu();
      }
    });

    document.addEventListener('keydown', (e) => {
      if (e.key === 'Escape' && navLinks.classList.contains('active')) {
        closeMenu();
        menuToggle.focus();
      }
    });
  }

  // 3. Category Tabs Filter
  const tabs = document.querySelectorAll('.cat-tab');
  const cards = document.querySelectorAll('.feature-card');

  tabs.forEach(tab => {
    tab.addEventListener('click', () => {
      tabs.forEach(t => t.classList.remove('active'));
      tab.classList.add('active');

      const targetCat = tab.getAttribute('data-cat');
      cards.forEach(card => {
        const cardCat = card.getAttribute('data-category');
        if (targetCat === 'all' || cardCat === targetCat) {
          card.classList.remove('hidden');
        } else {
          card.classList.add('hidden');
        }
      });

      const featuresGrid = document.getElementById('featuresGrid');
      if (featuresGrid) {
        featuresGrid.scrollTo({ left: 0, behavior: 'smooth' });
      }
    });
  });

  // 4. Copy Code Snippet Button (with fallback)
  const copyBtn = document.getElementById('copyBtn');
  const copyBtnText = document.getElementById('copyBtnText');
  if (copyBtn && copyBtnText) {
    copyBtn.addEventListener('click', async () => {
      const code = `git clone https://github.com/uamo11/OpenManager.git\ncd OpenManager\n./gradlew assembleDebug`;
      let copied = false;
      try {
        if (navigator.clipboard && navigator.clipboard.writeText) {
          await navigator.clipboard.writeText(code);
          copied = true;
        }
      } catch (_) {}

      if (!copied) {
        const ta = document.createElement('textarea');
        ta.value = code;
        ta.setAttribute('readonly', '');
        ta.style.position = 'fixed';
        ta.style.opacity = '0';
        document.body.appendChild(ta);
        ta.select();
        try { copied = document.execCommand('copy'); } catch (_) {}
        document.body.removeChild(ta);
      }

      if (copied) {
        const originalText = copyBtnText.textContent;
        copyBtnText.textContent = (i18nData[currentLang] && i18nData[currentLang].btn_copied) || 'Copied!';
        copyBtn.style.borderColor = 'var(--accent)';
        setTimeout(() => {
          copyBtnText.textContent = originalText;
          copyBtn.style.borderColor = '';
        }, 2200);
      }
    });
  }

  // 5. Scroll Progress Bar (rAF-throttled)
  const scrollProgress = document.getElementById('scrollProgress');
  if (scrollProgress) {
    let ticking = false;
    const updateProgress = () => {
      ticking = false;
      const doc = document.documentElement;
      const scrollable = doc.scrollHeight - window.innerHeight;
      const pct = scrollable > 0 ? Math.min(100, Math.max(0, (window.scrollY / scrollable) * 100)) : 0;
      scrollProgress.style.width = `${pct}%`;
    };
    window.addEventListener('scroll', () => {
      if (!ticking) {
        ticking = true;
        requestAnimationFrame(updateProgress);
      }
    }, { passive: true });
    updateProgress();
  }

  // 6. Scroll Reveal via IntersectionObserver (keep gallery & comparison always visible)
  const prefersReduced = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
  const revealTargets = document.querySelectorAll('.metric-item, .feature-card');
  if (!prefersReduced && 'IntersectionObserver' in window) {
    revealTargets.forEach(el => el.classList.add('reveal'));
    const io = new IntersectionObserver((entries) => {
      entries.forEach(entry => {
        if (entry.isIntersecting) {
          entry.target.classList.add('is-visible');
          io.unobserve(entry.target);
        }
      });
    }, { rootMargin: '0px 0px -40px 0px', threshold: 0.08 });
    revealTargets.forEach(el => io.observe(el));
  }

  // 7. Horizontal Carousels (prev/next buttons)
  initCarousels();

  // 8. Native <dialog> Lightbox bindings
  initLightbox();

  // 9. Fetch Latest Release Tag & Direct APK URL (1h cache)
  fetchLatestRelease();
});

function initCarousels() {
  document.querySelectorAll('.carousel-shell').forEach(shell => {
    const track = shell.querySelector('.features-grid, .gallery-grid');
    const prevBtn = shell.querySelector('.carousel-prev');
    const nextBtn = shell.querySelector('.carousel-next');
    if (!track) return;

    const getStep = () => Math.max(200, Math.round(track.clientWidth * 0.78));
    if (prevBtn) {
      prevBtn.addEventListener('click', () => {
        track.scrollBy({ left: -getStep(), behavior: 'smooth' });
      });
    }
    if (nextBtn) {
      nextBtn.addEventListener('click', () => {
        track.scrollBy({ left: getStep(), behavior: 'smooth' });
      });
    }
  });
}

function setLanguage(lang) {
  if (!i18nData[lang]) return;
  currentLang = lang;
  storageSet('om_lang', lang);
  applyLanguage(lang);
}

function applyLanguage(lang) {
  document.documentElement.lang = lang;
  const dict = { ...(extraI18n[lang] || extraI18n.es), ...(i18nData[lang] || {}) };

  document.querySelectorAll('[data-i18n]').forEach(el => {
    const key = el.getAttribute('data-i18n');
    if (dict[key] !== undefined) {
      el.innerHTML = dict[key];
    }
  });

  const tagEl = document.getElementById('latestTag');
  const activeTag = window.__latestTag || 'v1.9.69';
  if (tagEl) {
    tagEl.textContent = activeTag;
  }

  const baseLabel = dict.btn_download_apk || 'Descargar APK';
  document.querySelectorAll('.js-apk-label').forEach(el => {
    el.textContent = `${baseLabel} (${activeTag})`;
  });
}

// Native <dialog> Lightbox
function getGalleryItems() {
  return Array.from(document.querySelectorAll('.gallery-item[data-index]'));
}

function renderLightboxItem(index) {
  const items = getGalleryItems();
  if (!items.length) return;
  activeGalleryIndex = (index + items.length) % items.length;
  const btn = items[activeGalleryIndex];
  const img = btn.querySelector('img');
  const captionSpan = btn.querySelector('.gallery-overlay span');

  const lightboxImg = document.getElementById('lightboxImg');
  const lightboxCaption = document.getElementById('lightboxCaption');
  const lightboxCounter = document.getElementById('lightboxCounter');

  if (lightboxImg && img) {
    lightboxImg.src = img.getAttribute('src') || '';
    lightboxImg.alt = img.getAttribute('alt') || 'Captura ampliada';
  }
  if (lightboxCaption) {
    lightboxCaption.textContent = (captionSpan && captionSpan.textContent.trim()) || btn.getAttribute('aria-label') || '';
  }
  if (lightboxCounter) {
    lightboxCounter.textContent = `${activeGalleryIndex + 1} / ${items.length}`;
  }
}

function openLightboxByIndex(index) {
  const lightbox = document.getElementById('lightbox');
  if (!lightbox) return;
  renderLightboxItem(index);
  if (typeof lightbox.showModal === 'function') {
    if (!lightbox.open) lightbox.showModal();
  } else {
    lightbox.setAttribute('open', '');
  }
}

function closeLightbox() {
  const lightbox = document.getElementById('lightbox');
  if (!lightbox) return;
  if (typeof lightbox.close === 'function') {
    if (lightbox.open) lightbox.close();
  } else {
    lightbox.removeAttribute('open');
  }
}

function initLightbox() {
  const lightbox = document.getElementById('lightbox');
  const items = getGalleryItems();

  items.forEach((btn, idx) => {
    btn.addEventListener('click', () => openLightboxByIndex(idx));
  });

  if (!lightbox) return;

  const closeBtn = document.getElementById('lbClose');
  const prevBtn = document.getElementById('lbPrev');
  const nextBtn = document.getElementById('lbNext');

  if (closeBtn) closeBtn.addEventListener('click', closeLightbox);
  if (prevBtn) prevBtn.addEventListener('click', () => renderLightboxItem(activeGalleryIndex - 1));
  if (nextBtn) nextBtn.addEventListener('click', () => renderLightboxItem(activeGalleryIndex + 1));

  lightbox.addEventListener('click', (e) => {
    if (e.target === lightbox) closeLightbox();
  });

  lightbox.addEventListener('keydown', (e) => {
    if (e.key === 'ArrowLeft') {
      e.preventDefault();
      renderLightboxItem(activeGalleryIndex - 1);
    } else if (e.key === 'ArrowRight') {
      e.preventDefault();
      renderLightboxItem(activeGalleryIndex + 1);
    }
  });
}

function applyReleaseData(tag, apkUrl) {
  if (tag) {
    window.__latestTag = tag;
    const tagEl = document.getElementById('latestTag');
    if (tagEl) tagEl.textContent = tag;
  }

  if (apkUrl) {
    document.querySelectorAll('.js-apk-download').forEach(btn => {
      btn.href = apkUrl;
    });
  }
  if (tag) {
    const dict = extraI18n[currentLang] || extraI18n.es;
    document.querySelectorAll('.js-apk-label').forEach(el => {
      el.textContent = `${dict.btn_download_apk} (${tag})`;
    });
  }
}

// Fetch Latest Release Tag & APK asset (stale-while-revalidate, always checks GitHub)
async function fetchLatestRelease() {
  const CACHE_KEY = 'om_release_cache_v2';

  try {
    const cachedRaw = storageGet(CACHE_KEY);
    if (cachedRaw) {
      const cached = JSON.parse(cachedRaw);
      if (cached && cached.tag && cached.apkUrl) {
        applyReleaseData(cached.tag, cached.apkUrl);
      }
    }
  } catch (_) {}

  try {
    const res = await fetch('https://api.github.com/repos/uamo11/OpenManager/releases/latest', {
      cache: 'no-store',
      headers: { Accept: 'application/vnd.github+json' }
    });
    if (!res.ok) return;
    const data = await res.json();
    const tag = data.tag_name;
    const assets = Array.isArray(data.assets) ? data.assets : [];
    const releaseApk = assets.find(a => a.name && a.name.endsWith('-release.apk'))
      || assets.find(a => a.name && a.name.endsWith('.apk'));
    const apkUrl = (releaseApk && releaseApk.browser_download_url) || data.html_url || '';

    if (tag) {
      applyReleaseData(tag, apkUrl);
      storageSet(CACHE_KEY, JSON.stringify({ ts: Date.now(), tag, apkUrl }));
    }
  } catch (_) {
    // Keep fallback v1.9.69
  }
}
