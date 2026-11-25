// OpenManager website: language, theme tabs, menu and screenshot viewer. No scroll handlers,
// no animation loops: nothing runs after the page has loaded unless you tap something.
(() => {
  const i18n = {
    en: {
      skip: "Skip to content",
      nav_features: "Features", nav_themes: "Themes", nav_zones: "Tour", nav_settings: "Settings",
      nav_languages: "Languages", nav_download: "Download",
      hero_tag: "Free · No ads · GPLv3",
      hero_title: "Your files, your VPS and your APKs in one app",
      hero_lead: "A dual-panel file manager for Android and a free alternative to MT Manager: an SSH terminal with your server's files, a PDF reader, a gallery, APK tools, DEX/Smali editing and full control with root or Shizuku.",
      btn_download: "Download APK", btn_source: "Source code on GitHub", prev: "Previous", next: "Next", btn_issue: "Report a problem",
      fact_size: "APK", fact_langs: "languages", fact_ads: "ads",
      feat_title: "Everything you need, for free",
      feat_sub: "The main features are checked on a real emulator before every release.",
      f1_t: "Dual panels and bookmarks", f1_d: "Two folders at once, copy and move between them, ‹ › history and a bookmarks panel when you swipe up on the bottom bar, with home-screen shortcuts.",
      f2_t: "SSH terminal and your VPS files", f2_d: "SSH and Telnet with keys, and the server's files over SFTP on the same session: no second login, and it recovers by itself from network drops.",
      f3_t: "PDF reader", f3_d: "Whole-document zoom with sharp text, remembers the last page of every PDF, night and sepia modes, and keep-screen-on.",
      f4_t: "Photos, 4K video and gallery", f4_d: "12 MP photos in tiles with their EXIF rotation, 4K video that adapts to your phone, and a per-folder gallery view with the resolution (4K, 2K, 1080p).",
      f5_t: "Reverse engineering", f5_d: "DEX/Smali editor, JADX decompiler, HEX editor with data inspector, ARSC and AXML, Apktool and file comparison.",
      f6_t: "APK tools", f6_d: "Install APKs and splits, AntiSplit, sign with V1/V2/V3, generate keys, quick edit of name, icon and package. 1 GB APKs open at once.",
      f7_t: "Root and Shizuku, with a safety net", f7_d: "Magisk, KernelSU, APatch and Shizuku. Dead Man and DFRoot protection stops mass deletions and writes to partitions before they happen.",
      f8_t: "Ultra RAM mode", f8_d: "For phones with little memory: minimal caches, no animations, and light cached thumbnails only if you want them.",
      f9_t: "Running apps", f9_d: "Real RAM (PSS) of the app and your scripts without root; with root, every process and what is heating the phone: CPU, brightness, charging and GPU.",
      f10_t: "Access from your PC", f10_d: "Manage the phone's files from the computer's browser with a code, or share over FTP. SMB, WebDAV and SFTP too.",
      f11_t: "Themes and Material 3", f11_d: "MT-style dark, pure-black AMOLED, light, and Material 3 with 19 accent colors for day and night.",
      f12_t: "7 complete languages", f12_d: "Spanish, English, Chinese, Russian, Portuguese, Indonesian and Vietnamese: the whole app, warnings and errors included.",
      themes_title: "Themes: dark, AMOLED, light and Material 3",
      themes_sub: "Change the look with one tap in Settings → Appearance. AMOLED uses pure black to save battery on OLED screens.",
      t_dark: "Dark", t_light: "Light", md3_title: "Material 3 in your color",
      zones_title: "Take the tour", zones_sub: "Swipe to see each part. Tap a screenshot to see it large.",
      z_gallery: "Gallery view with resolution", z_bookmarks: "Bookmarks panel", z_drawer: "Side menu", z_terminal: "Terminal",
      z_apk: "APK information", z_editor: "Code editor", z_pdf: "PDF reader", z_running: "Running apps and temperature",
      set_title: "Clear settings, no surprises",
      s1: "Appearance at a glance: quick themes, accent color and thumbnails.",
      s2: "Ultra RAM mode with one switch, which puts everything back as it was when turned off.",
      s3: "Settings search in every language.",
      s4: "Root, Shizuku and protections with their real state in view.",
      s5: "Network: server thumbnails only on Wi-Fi and with a size limit.",
      s6: "Updates from GitHub inside the app.",
      lang_title: "In your language, for real",
      lang_sub: "Every new text arrives in all 7 languages at once, and a test blocks a release if one is missing.",
      dl_title: "Download and install",
      d1: "Open the latest release page and download <b>OpenManager-…-release.apk</b>.",
      d2: "Install it (allow installs from your browser if Android asks).",
      d3: "Next versions are offered inside the app: Settings → Check for updates.",
      build_title: "Build from source",
      foot: "OpenManager is free software (GPLv3). It collects no data."
    },
    zh: {
      skip: "跳到内容",
      nav_features: "功能", nav_themes: "主题", nav_zones: "浏览", nav_settings: "设置", nav_languages: "语言", nav_download: "下载",
      hero_tag: "免费 · 无广告 · GPLv3",
      hero_title: "文件、VPS 和 APK，一个应用全搞定",
      hero_lead: "适用于 Android 的双窗口文件管理器，MT 管理器的免费替代品：带服务器文件的 SSH 终端、PDF 阅读器、图库、APK 工具、DEX/Smali 编辑，并可通过 Root 或 Shizuku 全面掌控。",
      btn_download: "下载 APK", btn_source: "GitHub 上的源代码", prev: "上一张", next: "下一张", btn_issue: "报告问题",
      fact_size: "APK", fact_langs: "种语言", fact_ads: "广告",
      feat_title: "所需功能一应俱全，全部免费",
      feat_sub: "每个版本发布前，主要功能都会在真实模拟器上检查。",
      f1_t: "双窗口与书签", f1_d: "同时打开两个文件夹，在其间复制和移动，‹ › 历史记录，在底部栏上滑打开书签面板，还可添加到主屏幕。",
      f2_t: "SSH 终端与 VPS 文件", f2_d: "支持密钥的 SSH 和 Telnet，并在同一会话上通过 SFTP 访问服务器文件：无需再次登录，网络中断后自动恢复。",
      f3_t: "PDF 阅读器", f3_d: "整篇文档缩放且文字清晰，记住每个 PDF 的最后一页，夜间和护眼模式，可保持屏幕常亮。",
      f4_t: "照片、4K 视频与图库", f4_d: "1200 万像素照片分块显示并保留 EXIF 旋转，4K 视频自适应手机，按文件夹的图库视图并标注分辨率（4K、2K、1080p）。",
      f5_t: "逆向工程", f5_d: "DEX/Smali 编辑器、JADX 反编译器、带数据检查器的 HEX 编辑器、ARSC 与 AXML、Apktool 及文件比较。",
      f6_t: "APK 工具", f6_d: "安装 APK 与拆分包、AntiSplit、V1/V2/V3 签名、生成密钥、快速修改名称、图标和包名。1 GB 的 APK 也能瞬间打开。",
      f7_t: "Root 与 Shizuku，并有安全保护", f7_d: "支持 Magisk、KernelSU、APatch 和 Shizuku。Dead Man 与 DFRoot 保护会在批量删除和写入分区之前将其拦截。",
      f8_t: "Ultra RAM 模式", f8_d: "适合内存较小的手机：最小缓存、无动画，仅在需要时显示轻量并缓存的缩略图。",
      f9_t: "运行中的应用", f9_d: "无需 Root 即可查看本应用及脚本的真实内存（PSS）；有 Root 时显示所有进程，以及让手机发热的原因：CPU、亮度、充电和 GPU。",
      f10_t: "从电脑访问", f10_d: "在电脑浏览器中凭代码管理手机文件，或通过 FTP 共享。也支持 SMB、WebDAV 和 SFTP。",
      f11_t: "主题与 Material 3", f11_d: "MT 风格深色、纯黑 AMOLED、浅色，以及支持 19 种强调色（日间和夜间）的 Material 3。",
      f12_t: "7 种完整语言", f12_d: "西班牙语、英语、中文、俄语、葡萄牙语、印尼语和越南语：整个应用，包括提示和错误信息。",
      themes_title: "主题：深色、AMOLED、浅色与 Material 3",
      themes_sub: "在 设置 → 外观 中一键切换。AMOLED 使用纯黑，在 OLED 屏幕上更省电。",
      t_dark: "深色", t_light: "浅色", md3_title: "使用你喜欢颜色的 Material 3",
      zones_title: "浏览应用", zones_sub: "滑动查看各个部分。点按截图可放大查看。",
      z_gallery: "带分辨率的图库视图", z_bookmarks: "书签面板", z_drawer: "侧边菜单", z_terminal: "终端",
      z_apk: "APK 信息", z_editor: "代码编辑器", z_pdf: "PDF 阅读器", z_running: "运行中的应用与温度",
      set_title: "设置清晰，没有意外",
      s1: "外观一目了然：快速主题、强调色和缩略图。",
      s2: "一键开启 Ultra RAM 模式，关闭后一切恢复原样。",
      s3: "支持所有语言的设置搜索。",
      s4: "Root、Shizuku 及各项保护的真实状态一目了然。",
      s5: "网络：服务器缩略图仅在 Wi-Fi 下加载并限制大小。",
      s6: "在应用内从 GitHub 更新。",
      lang_title: "真正使用你的语言",
      lang_sub: "每条新文本都会同时提供 7 种语言，缺少任何一种都会被测试阻止发布。",
      dl_title: "下载与安装",
      d1: "打开最新版本页面，下载 <b>OpenManager-…-release.apk</b>。",
      d2: "安装（如 Android 提示，请允许从浏览器安装）。",
      d3: "之后的版本会在应用内提示：设置 → 检查更新。",
      build_title: "从源代码编译",
      foot: "OpenManager 是自由软件（GPLv3），不收集任何数据。"
    }
  };

  const html = document.documentElement;
  const nodes = [...document.querySelectorAll("[data-i18n]")];
  const spanish = new Map(nodes.map((n) => [n, n.innerHTML]));
  // Accessible names (icon-only buttons) translate too.
  const labelled = [...document.querySelectorAll("[data-i18n-label]")];
  const spanishLabels = new Map(labelled.map((n) => [n, n.getAttribute("aria-label")]));
  const flags = { es: "#flag-mx", en: "#flag-us", zh: "#flag-cn" };
  const langBtn = document.getElementById("langBtn");
  const langMenu = document.getElementById("langMenu");
  const langItems = [...langMenu.querySelectorAll("[data-lang]")];

  function setLang(lang) {
    const dict = i18n[lang];
    for (const n of nodes) {
      const key = n.dataset.i18n;
      n.innerHTML = dict && dict[key] ? dict[key] : spanish.get(n);
    }
    for (const n of labelled) {
      const key = n.dataset.i18nLabel;
      const text = dict && dict[key] ? dict[key] : spanishLabels.get(n);
      n.setAttribute("aria-label", text);
      if (n.hasAttribute("title")) n.title = text;
    }
    html.lang = lang === "zh" ? "zh-CN" : lang;
    langBtn.querySelector(".flag use").setAttribute("href", flags[lang] || flags.es);
    for (const item of langItems) item.setAttribute("aria-checked", String(item.dataset.lang === lang));
    try { localStorage.setItem("om_lang", lang); } catch (_) {}
  }
  let lang = null;
  try { lang = localStorage.getItem("om_lang"); } catch (_) {}
  if (!lang) {
    const nav = (navigator.language || "es").toLowerCase();
    lang = nav.startsWith("zh") ? "zh" : nav.startsWith("es") ? "es" : "en";
  }
  if (lang !== "es") setLang(lang);

  // Language menu: flag button, closes on a choice, outside tap or Escape; arrows move.
  function openLang(open) {
    langMenu.hidden = !open;
    langBtn.setAttribute("aria-expanded", String(open));
    if (open) (langItems.find((i) => i.getAttribute("aria-checked") === "true") || langItems[0]).focus();
  }
  langBtn.addEventListener("click", () => openLang(langMenu.hidden));
  langMenu.addEventListener("click", (e) => {
    const item = e.target.closest("[data-lang]");
    if (!item) return;
    setLang(item.dataset.lang);
    openLang(false);
    langBtn.focus();
  });
  langMenu.addEventListener("keydown", (e) => {
    const i = langItems.indexOf(document.activeElement);
    if (e.key === "ArrowDown" || e.key === "ArrowUp") {
      e.preventDefault();
      const n = langItems.length;
      langItems[(i + (e.key === "ArrowDown" ? 1 : n - 1)) % n].focus();
    }
  });
  document.addEventListener("click", (e) => {
    if (!langMenu.hidden && !e.target.closest(".langpick")) openLang(false);
  });
  document.addEventListener("keydown", (e) => {
    if (e.key !== "Escape") return;
    if (!langMenu.hidden) { openLang(false); langBtn.focus(); }
    if (links.classList.contains("open")) { links.classList.remove("open"); menu.setAttribute("aria-expanded", "false"); menu.focus(); }
  });

  // Mobile menu
  const menu = document.getElementById("menu");
  const links = document.getElementById("links");
  menu.addEventListener("click", () => {
    const open = links.classList.toggle("open");
    menu.setAttribute("aria-expanded", String(open));
  });
  links.addEventListener("click", (e) => {
    if (e.target.closest("a")) { links.classList.remove("open"); menu.setAttribute("aria-expanded", "false"); }
  });

  // Theme tabs and Material 3 swatches swap one screenshot.
  for (const group of document.querySelectorAll(".tabs[data-target]")) {
    group.addEventListener("click", (e) => {
      const button = e.target.closest("button[data-src]");
      if (!button) return;
      document.getElementById(group.dataset.target).src = button.dataset.src;
      for (const b of document.querySelectorAll(".tabs[data-target] button")) b.setAttribute("aria-selected", String(b === button));
    });
  }

  // Arrows for the screenshot strip on computers (shown only with a mouse).
  const reduceMotion = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
  for (const ctl of document.querySelectorAll(".strip-ctl")) {
    const strip = document.getElementById(ctl.dataset.strip);
    ctl.addEventListener("click", (e) => {
      const b = e.target.closest("button[data-dir]");
      if (!b || !strip) return;
      strip.scrollBy({ left: Number(b.dataset.dir) * strip.clientWidth * 0.8, behavior: reduceMotion ? "auto" : "smooth" });
    });
  }

  // Tap a screenshot to see it large.
  const viewer = document.getElementById("viewer");
  if (viewer && viewer.showModal) {
    const big = viewer.querySelector("img");
    document.addEventListener("click", (e) => {
      const img = e.target.closest(".strip img, .phone img");
      if (!img) return;
      big.src = img.currentSrc || img.src;
      big.alt = img.alt;
      viewer.showModal();
    });
    viewer.addEventListener("click", () => viewer.close());
  }

  // Latest version tag, cached for a day so the GitHub API is not asked on every visit.
  const ver = document.getElementById("ver");
  try {
    const cached = JSON.parse(localStorage.getItem("om_latest") || "null");
    if (cached && Date.now() - cached.t < 86400000) {
      ver.textContent = cached.v;
    } else {
      fetch("https://api.github.com/repos/uamo11/OpenManager/releases/latest")
        .then((r) => (r.ok ? r.json() : null))
        .then((j) => {
          if (j && j.tag_name) {
            ver.textContent = j.tag_name;
            localStorage.setItem("om_latest", JSON.stringify({ v: j.tag_name, t: Date.now() }));
          }
        })
        .catch(() => {});
    }
  } catch (_) {}
})();
