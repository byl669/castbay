const { invoke } = window.__TAURI__.core;
const { listen } = window.__TAURI__.event;
const $ = (id) => document.getElementById(id);

// ---------------------------------------------------------------- text
const TEXT = {
  zh: {
    app_name: "映湾桌面端", settings: "设置", download: "下载", update_now: "更新", got_it: "知道了", ok: "确定", search_again: "重新查找",
    aria_back: "返回", aria_settings: "设置", aria_refresh: "刷新", aria_what: "投什么", aria_source: "选择要投的内容", aria_code: "配对码",
    aria_s_quality: "画质", aria_s_audio: "同时传声音", aria_s_autostart: "开机自动启动",
    w_title: "开始使用", w_1: "在电视或平板上打开映湾，在设置里开启“电脑投屏”。",
    w_2: "如果 Windows 防火墙弹窗询问，请允许在专用网络上访问，否则找不到接收端。",
    w_3: "在下面选择接收端并开始投屏。第一次连接时接收端会显示 6 位配对码，在这里输入即可。",
    receivers: "接收端", what: "投什么", screen: "整个屏幕", window: "某个窗口",
    empty_title: "没有找到接收端", empty_body: "在电视或平板上打开映湾，开启“电脑投屏”；两者需在同一网络，Windows 防火墙要允许本程序访问专用网络。",
    password: "接收端密码", start: "开始投屏", stop: "停止投屏", change: "更改",
    system_picker: "开始投屏时，系统会询问要共享哪个屏幕或窗口。", with_audio: "带声音", no_audio: "不带声音", no_windows: "没有可投的窗口", no_screens: "没有可投的屏幕", screen_n: "屏幕 {0}",
    pair_open: "无需配对", pair_ask: "需要在接收端确认", pair_pin: "首次需要配对码", paired: "已配对", needs_password: "需要密码",
    pick_receiver: "请先选一个接收端", pick_source: "请先选一个窗口",
    connecting: "正在连接…", needs_code: "需要配对", casting: "正在投屏", reconnecting: "连接中断，正在重连…",
    code_prompt: "请输入接收端屏幕上显示的 6 位配对码。", locked_note: "电脑已锁屏：Windows 在锁屏时不提供画面，所以画面会停住。解锁后会自动继续。",
    details: "详细信息", s_decode: "接收端解码", s_fps: "接收端帧率", s_dropped: "丢帧", s_rate: "码率", tray_hint: "投屏时关闭窗口，投屏会继续在系统托盘里运行。",
    fps_n: "{0} 帧",
    s_quality: "画质", q_saver: "省流", q_balanced: "均衡", q_smooth: "流畅",
    qn_saver: "30 帧 · 4 Mbit/s：省流量，画面细节少一些。", qn_balanced: "30 帧 · 8 Mbit/s：大多数场景够用。", qn_smooth: "60 帧 · 10 Mbit/s：动态画面更顺，占用更多网络和 CPU。",
    s_audio: "同时传声音", s_encoder: "编码器", e_auto: "自动", e_cpu: "CPU（x264）", encoder_note: "自动会依次尝试 AMD、NVIDIA，最后用 CPU。",
    s_general: "通用", s_autostart: "开机自动启动", s_language: "语言", lang_auto: "跟随系统", s_paired: "已配对的接收端",
    s_about: "关于", s_version: "版本", s_check_update: "检查更新", s_logs: "打开日志文件夹", s_license: "开源许可证与源码",
    up_checking: "正在检查…", up_latest: "已是最新", up_new: "有新版本 {0}", up_failed: "检查失败", up_dev: "开发版",
    paired_intro: "这些接收端配对过。忘记后，下次投屏需要重新配对。", paired_none: "还没有配对过的接收端。", unnamed: "未命名的接收端",
    last_seen: "上次连接 {0}", forget: "忘记", forget_sure: "确认忘记", forget_receiver: "忘记这个接收端",
    update_text: "有新版本 {0} 可用",
    up_downloading: "正在下载 {0}…", up_installing: "正在安装，程序会重新打开…", up_opened: "已打开安装程序，请按提示完成更新", up_busy: "投屏结束后再更新", up_error: "更新失败：{0}",
    ended_stopped_by_receiver: "接收端结束了投屏", ended_replaced: "接收端被另一台设备占用了", ended_receiver_stopped: "接收端已停止接收",
    failed: "无法投屏：{0}",
    f_busy: "接收端正在被另一台设备使用。", f_blocked: "接收端屏蔽了这台电脑。请在接收端的“已知设备”里解除屏蔽。",
    f_approval_needed: "接收端需要有人在它的屏幕上点“允许”。请确认后重试。", f_locked: "配对尝试次数过多，接收端暂时锁定了配对，请一分钟后重试。",
    f_wrong_password: "接收端密码不对。", f_unsupported: "接收端不支持这次投屏的格式。", f_wrong_code: "配对码不对，请重试。",
    f_code_required: "需要输入配对码才能连接。", f_receiver_changed: "这台接收端的密钥和之前配对的不一样（可能被重置，或不是同一台设备）。如果确认就是它，请先忘记它，再重新配对。",
    f_network: "连不上接收端。请确认两者在同一网络，并且接收端已开启桌面投屏。", f_no_picture: "取不到画面。屏幕是否已锁定，或者要投的窗口已经关闭？",
    f_encoder: "无法启动视频编码器：{0}", f_capture: "屏幕捕获出错：{0}", f_protocol: "和接收端通信出错：{0}",
  },
  en: {
    app_name: "CastBay Desktop", settings: "Settings", download: "Download", update_now: "Update", got_it: "Got it", ok: "OK", search_again: "Search again",
    aria_back: "Back", aria_settings: "Settings", aria_refresh: "Refresh", aria_what: "What to cast", aria_source: "Choose what to cast", aria_code: "Pairing code",
    aria_s_quality: "Quality", aria_s_audio: "Send sound too", aria_s_autostart: "Start with Windows",
    w_title: "Getting started", w_1: "On the TV or tablet, open CastBay and turn on \"Desktop casting\" in its settings.",
    w_2: "If Windows asks about the firewall, allow access on private networks. Otherwise receivers cannot be found.",
    w_3: "Pick the receiver below and start. The first time, the receiver shows a 6-digit code: type it here.",
    receivers: "Receivers", what: "What to cast", screen: "A screen", window: "A window",
    empty_title: "No receiver found", empty_body: "Open CastBay on the TV or tablet and turn on Desktop casting. Both must be on the same network, and the Windows firewall must allow this app on private networks.",
    password: "Receiver password", start: "Start casting", stop: "Stop casting", change: "Change",
    system_picker: "When you start, the system asks which screen or window to share.", with_audio: "with sound", no_audio: "no sound", no_windows: "No window to cast", no_screens: "No screen to cast", screen_n: "Screen {0}",
    pair_open: "No pairing needed", pair_ask: "Asks for approval on the receiver", pair_pin: "Needs a code the first time", paired: "Paired", needs_password: "Needs a password",
    pick_receiver: "Pick a receiver first", pick_source: "Pick a window first",
    connecting: "Connecting…", needs_code: "Pairing needed", casting: "Casting", reconnecting: "Connection lost, reconnecting…",
    code_prompt: "Type the 6-digit code shown on the receiver.", locked_note: "The PC is locked: Windows shows nothing to capture, so the picture stays as it was. Unlock it to go on.",
    details: "Details", s_decode: "Receiver decode", s_fps: "Receiver fps", s_dropped: "Dropped", s_rate: "Bitrate", tray_hint: "Closing the window while casting keeps the cast running in the tray.",
    fps_n: "{0} fps",
    s_quality: "Quality", q_saver: "Light", q_balanced: "Balanced", q_smooth: "Smooth",
    qn_saver: "30 fps, 4 Mbit/s: uses less network, less detail.", qn_balanced: "30 fps, 8 Mbit/s: enough for most things.", qn_smooth: "60 fps, 10 Mbit/s: smoother motion, more network and CPU.",
    s_audio: "Send sound too", s_encoder: "Encoder", e_auto: "Automatic", e_cpu: "CPU (x264)", encoder_note: "Automatic tries AMD, then NVIDIA, then the CPU.",
    s_general: "General", s_autostart: "Start with Windows", s_language: "Language", lang_auto: "Follow the system", s_paired: "Paired receivers",
    s_about: "About", s_version: "Version", s_check_update: "Check for updates", s_logs: "Open log folder", s_license: "Licence and source code",
    up_checking: "Checking…", up_latest: "Up to date", up_new: "Version {0} is available", up_failed: "Could not check", up_dev: "Development build",
    paired_intro: "These receivers were paired. After forgetting one, the next cast pairs again.", paired_none: "No receiver has been paired yet.", unnamed: "Unnamed receiver",
    last_seen: "Last connected {0}", forget: "Forget", forget_sure: "Forget it", forget_receiver: "Forget this receiver",
    update_text: "Version {0} is available",
    up_downloading: "Downloading {0}…", up_installing: "Installing; the program opens again…", up_opened: "The installer is open: follow it to finish the update", up_busy: "Update after the cast has ended", up_error: "The update failed: {0}",
    ended_stopped_by_receiver: "The receiver ended the cast", ended_replaced: "Another device took over the receiver", ended_receiver_stopped: "The receiver stopped receiving",
    failed: "Could not cast: {0}",
    f_busy: "The receiver is being used by another device.", f_blocked: "The receiver blocks this computer. Unblock it in the receiver's known devices.",
    f_approval_needed: "Someone has to tap Allow on the receiver's screen. Confirm there and try again.", f_locked: "Too many pairing attempts: the receiver paused pairing. Try again in a minute.",
    f_wrong_password: "Wrong receiver password.", f_unsupported: "The receiver does not support this kind of cast.", f_wrong_code: "Wrong pairing code, try again.",
    f_code_required: "A pairing code is needed to connect.", f_receiver_changed: "This receiver's key differs from the one paired before (it may have been reset, or it is another device). If it is the right one, forget it and pair again.",
    f_network: "Cannot reach the receiver. Check both are on the same network and Desktop casting is on at the receiver.", f_no_picture: "No picture arrived. Is the screen locked, or the window closed?",
    f_encoder: "The video encoder could not start: {0}", f_capture: "Screen capture failed: {0}", f_protocol: "Talking to the receiver failed: {0}",
  },
};
let lang = "en";
const t = (key, ...args) => String(TEXT[lang][key] ?? TEXT.en[key] ?? key).replace(/\{(\d)\}/g, (_, i) => args[i] ?? "");
const systemLang = () => ((navigator.language || "en").toLowerCase().startsWith("zh") ? "zh" : "en");

// ---------------------------------------------------------------- state
let settings = {};
let receivers = [];
let paired = [];
let selected = null;
let sources = { monitors: [], windows: [], system_picker: false };
let view = "home";
let casting = false;
let castNote = { fps: 30, audio: true };
let failedReceiverId = null;
let latest = null; // a newer release: { version, url }
let toastTimer = null;

// ---------------------------------------------------------------- settings
// The person's choices live in settings.json on the Rust side; `settings` is this page's copy.
async function readSettings() {
  settings = await invoke("get_settings");
  if (settings.migrated) return;
  // First start after an update from a version that kept them in the page's own storage: copy once.
  const patch = { migrated: true };
  try {
    const old = (k) => localStorage.getItem(k);
    if (old("quality")) patch.quality = old("quality");
    if (old("encoder")) patch.encoder = old("encoder");
    if (old("audio") !== null) patch.send_audio = old("audio") === "1";
    if (old("welcome-done") === "1") patch.welcome_done = true;
    if (old("receiver")) patch.last_receiver = old("receiver");
    if (old("kind")) patch.source_kind = old("kind");
    if (old("source-monitor")) patch.source_monitor = old("source-monitor");
    if (old("source-window")) patch.source_window = old("source-window");
  } catch {}
  try { settings = await invoke("set_settings", { patch }); }
  catch { settings = await invoke("set_settings", { patch: { migrated: true } }); }
}

async function save(patch) {
  Object.assign(settings, patch);
  try { settings = await invoke("set_settings", { patch }); }
  catch (e) { showError(String(e)); settings = await invoke("get_settings"); }
}

function applyLanguage() {
  lang = settings.language === "zh" || settings.language === "en" ? settings.language : systemLang();
  document.documentElement.lang = lang;
  document.title = t("app_name");
  document.querySelectorAll("[data-i18n]").forEach((el) => { el.textContent = t(el.dataset.i18n); });
  document.querySelectorAll("[data-aria]").forEach((el) => el.setAttribute("aria-label", t("aria_" + el.dataset.aria)));
}

// ---------------------------------------------------------------- small helpers
function el(tag, props = {}, ...children) {
  const node = document.createElement(tag);
  for (const [k, v] of Object.entries(props)) {
    if (k === "class") node.className = v;
    else if (k === "text") node.textContent = v;
    else if (k.startsWith("on")) node.addEventListener(k.slice(2), v);
    else node.setAttribute(k, v);
  }
  for (const c of children.flat()) if (c) node.append(c);
  return node;
}
const icon = (name, cls = "") => { const s = document.createElementNS("http://www.w3.org/2000/svg", "svg"); s.setAttribute("class", "ic " + cls); const u = document.createElementNS("http://www.w3.org/2000/svg", "use"); u.setAttribute("href", "#i-" + name); s.append(u); return s; };

function showError(message, forgetId = null) {
  $("error-box").hidden = !message;
  $("error").textContent = message || "";
  failedReceiverId = forgetId;
  $("forget").hidden = !forgetId;
}

// A short word under the button (not a red box: it is only a nudge).
function nudge(message) {
  $("action-summary").textContent = message;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(renderSummary, 2500);
}

// ---------------------------------------------------------------- views
const TITLES = { home: null, casting: null, settings: "settings", paired: "s_paired" };
function showView(name) {
  view = name;
  for (const v of ["home", "casting", "settings", "paired"]) $("view-" + v).hidden = v !== name;
  const sub = name === "settings" || name === "paired";
  $("back").hidden = !sub;
  $("open-settings").hidden = sub;
  $("title").firstElementChild.toggleAttribute("hidden", sub); // an SVG has no .hidden property
  document.body.dataset.view = name;
  $("title-text").dataset.i18n = TITLES[name] ?? "app_name";
  $("title-text").textContent = t(TITLES[name] ?? "app_name");
  $("action-bar").hidden = sub;
  $("start").hidden = name === "casting";
  $("stop").hidden = name !== "casting";
  if (name === "home") renderSummary();
  else if (name === "casting") $("action-summary").textContent = "";
  document.querySelector("main").scrollTop = 0;
}

function goBack() {
  if (view === "paired") { showView("settings"); return; }
  showView(casting ? "casting" : "home");
}

// ---------------------------------------------------------------- home
function pairText(r) {
  const parts = [];
  if (r.pair === "open") parts.push(t("pair_open"));
  else if (r.pair === "ask") parts.push(t("pair_ask"));
  else parts.push(paired.some((p) => p.id === r.id) ? t("paired") : t("pair_pin"));
  if (r.password) parts.push(t("needs_password"));
  return parts.join(" · ");
}

function renderReceivers() {
  const list = $("receiver-list");
  list.replaceChildren();
  for (const r of receivers) {
    const on = selected?.id === r.id;
    const pick = () => { selected = r; save({ last_receiver: r.id }); renderReceivers(); };
    list.append(el("li", { role: "option", "aria-selected": String(on), tabindex: "0", onclick: pick, onkeydown: (e) => { if (e.key === "Enter" || e.key === " ") { e.preventDefault(); pick(); } } },
      el("span", { class: "badge" }, icon("tv")),
      el("div", { class: "grow" }, el("div", { class: "name", text: r.name }), el("div", { class: "meta", text: pairText(r) })),
      el("span", { class: "end" }, on ? icon("check") : r.password ? icon("lock") : null)));
  }
  $("empty").hidden = receivers.length > 0;
  $("password-row").hidden = !selected?.password;
  renderSummary();
}

async function refreshReceivers() {
  $("refresh").disabled = true;
  try {
    [receivers, paired] = await Promise.all([invoke("discover"), invoke("list_paired")]);
    selected = receivers.find((r) => r.id === selected?.id) ?? receivers.find((r) => r.id === settings.last_receiver) ?? null;
    renderReceivers();
  } catch (e) {
    showError(String(e));
  } finally {
    $("refresh").disabled = false;
  }
}

function currentKind() { return settings.source_kind === "window" ? "window" : "monitor"; }

function fillSources() {
  document.querySelectorAll("#view-home .segmented, #source").forEach((n) => n.toggleAttribute("hidden", !!sources.system_picker));
  $("system-picker-note").toggleAttribute("hidden", !sources.system_picker);
  const kind = currentKind();
  document.querySelectorAll("#view-home .segmented button").forEach((b) => b.setAttribute("aria-checked", String(b.dataset.kind === kind)));
  const select = $("source");
  select.replaceChildren();
  const items = kind === "monitor"
    ? sources.monitors.map((label, i) => [String(i), t("screen_n", label)])
    : sources.windows.map((title) => [title, title]);
  if (items.length === 0) items.push(["", t(kind === "monitor" ? "no_screens" : "no_windows")]);
  for (const [value, label] of items) select.append(el("option", { value, text: label }));
  const remembered = kind === "monitor" ? settings.source_monitor : settings.source_window;
  if ([...select.options].some((o) => o.value === remembered)) select.value = remembered;
}

async function refreshSources() {
  try { sources = await invoke("sources"); fillSources(); }
  catch (e) { showError(String(e)); }
}

function qualityName() { return t("q_" + (settings.quality || "balanced")); }

function renderSummary() {
  if (view !== "home") return;
  const box = $("action-summary");
  clearTimeout(toastTimer);
  box.replaceChildren(
    document.createTextNode([qualityName(), t("fps_n", settings.quality === "smooth" ? 60 : 30), t(settings.send_audio ? "with_audio" : "no_audio")].join(" · ") + " · "),
    el("button", { class: "link", text: t("change"), onclick: () => showView("settings") }));
}

// ---------------------------------------------------------------- casting
function startCast() {
  if (!selected) { nudge(t("pick_receiver")); return; }
  const kind = sources.system_picker ? "monitor" : currentKind();
  const value = $("source").value;
  if (kind === "window" && !value) { nudge(t("pick_source")); return; }
  showError("");
  castNote = { fps: settings.quality === "smooth" ? 60 : 30, audio: !!settings.send_audio };
  invoke("start_cast", { args: { id: selected.id, ip: selected.ip, port: selected.port, password: selected.password ? $("password").value : null, kind, value } })
    .then(() => { casting = true; resetCasting(); showView("casting"); })
    .catch((e) => showError(t("failed", String(e))));
}

function resetCasting() {
  $("state-line").textContent = t("connecting");
  $("receiver-line").textContent = selected?.name ?? "";
  $("detail-line").textContent = "";
  $("code-box").hidden = true;
  $("lock-note").hidden = true;
  for (const id of ["s-decode", "s-fps", "s-dropped", "s-rate"]) $(id).textContent = "–";
}

function submitCode() {
  const code = $("code").value.trim();
  if (code.length !== 6) return;
  $("code-box").hidden = true;
  $("state-line").textContent = t("connecting");
  invoke("submit_code", { code });
}

function onStatus(s) {
  showError("");
  switch (s.kind) {
    case "connecting": casting = true; resetCasting(); showView("casting"); break;
    case "needs_code": $("state-line").textContent = t("needs_code"); $("code-box").hidden = false; $("code").value = ""; $("code").focus(); break;
    case "casting":
      $("code-box").hidden = true;
      $("state-line").textContent = t("casting");
      $("receiver-line").textContent = s.receiver;
      $("detail-line").textContent = [`${s.width}×${s.height}`, t("fps_n", castNote.fps), t(castNote.audio ? "with_audio" : "no_audio")].join(" · ");
      break;
    case "reconnecting": $("state-line").textContent = t("reconnecting"); break;
    case "reconnected": $("state-line").textContent = t("casting"); break;
    case "stats":
      $("s-decode").textContent = `${s.decode_ms} ms`;
      $("s-fps").textContent = s.fps.toFixed(0);
      $("s-dropped").textContent = String(s.dropped);
      break;
    case "bitrate": $("s-rate").textContent = (s.bps / 1e6).toFixed(1) + " Mbit/s"; break;
    case "locked": $("lock-note").hidden = !s.locked; break;
    case "ended": endCast(); { const m = t("ended_" + s.reason); showError(m === "ended_" + s.reason || s.reason === "stopped" ? "" : m); } break;
    case "failed": {
      endCast();
      if (s.code === "stopped") break;
      const known = TEXT[lang]["f_" + s.code] ?? TEXT.en["f_" + s.code];
      showError(known ? t("f_" + s.code, s.reason) : t("failed", s.reason), s.code === "receiver_changed" ? selected?.id : null);
      break;
    }
  }
}

function endCast() {
  casting = false;
  if (view === "casting") showView("home");
  refreshReceivers();
}

// ---------------------------------------------------------------- settings page
function renderSettings() {
  document.querySelectorAll("#view-settings .segmented button").forEach((b) => b.setAttribute("aria-checked", String(b.dataset.quality === settings.quality)));
  $("quality-note").textContent = t("qn_" + settings.quality);
  $("audio").setAttribute("aria-checked", String(!!settings.send_audio));
  $("encoder").value = settings.encoder;
  $("language").value = settings.language;
  $("paired-count").textContent = paired.length ? String(paired.length) : "";
}

async function renderPaired() {
  try { paired = await invoke("list_paired"); } catch {}
  const list = $("paired-list");
  list.replaceChildren();
  for (const p of paired) {
    const when = p.seen ? t("last_seen", new Date(p.seen * 1000).toLocaleDateString(lang === "zh" ? "zh-CN" : "en-GB")) : "";
    let armed = null;
    const button = el("button", { class: "link", text: t("forget") });
    button.addEventListener("click", async () => {
      if (!armed) { button.textContent = t("forget_sure"); armed = setTimeout(() => { armed = null; button.textContent = t("forget"); }, 3000); return; }
      clearTimeout(armed);
      await invoke("forget_receiver", { id: p.id }).catch((e) => showError(String(e)));
      renderPaired(); renderSettings(); renderReceivers();
    });
    list.append(el("li", { class: "row" }, el("div", {}, el("div", { text: p.name || t("unnamed") }), el("div", { class: "muted note", text: when })), button));
  }
  $("paired-empty").hidden = paired.length > 0;
}

// ---------------------------------------------------------------- updates
function newer(a, b) {
  const num = (v) => v.split("-")[0].split(".").map((n) => parseInt(n, 10) || 0);
  const [x, y] = [num(a), num(b)];
  for (let i = 0; i < 3; i++) if ((x[i] || 0) !== (y[i] || 0)) return (x[i] || 0) > (y[i] || 0);
  return false;
}

// "latest" when up to date, a { version, url } when newer, null when it could not be checked. A
// development build (0.0.0) is not checked.
async function checkUpdate() {
  try {
    const current = await window.__TAURI__.app.getVersion();
    if (current === "0.0.0") return "dev";
    // GitHub's list of releases; where GitHub cannot be reached, the website's note on the newest
    // one, or that note through the relay (for networks that cannot reach Cloudflare).
    let releases = null;
    for (const url of ["https://api.github.com/repos/weenas/castbay/releases?per_page=30", "https://castbay.weenas.com/desktop/latest.json", "https://cast.weenas.com/desktop/latest.json"]) {
      try {
        const response = await fetch(url, { signal: AbortSignal.timeout(10000) });
        if (!response.ok) continue;
        const body = await response.json();
        releases = Array.isArray(body) ? body : [{ tag_name: `desktop-v${body.version}`, html_url: body.page, draft: false }];
        break;
      } catch { /* the next address */ }
    }
    if (!releases) return null;
    releases = releases.filter((r) => !r.draft && r.tag_name.startsWith("desktop-v"));
    const best = releases.map((r) => ({ version: r.tag_name.slice("desktop-v".length), url: r.html_url })).sort((p, q) => (newer(p.version, q.version) ? -1 : 1))[0];
    return best && newer(best.version, current) ? best : "latest";
  } catch { return null; }
}

// Downloads and installs the newer version; an installation that is not a program of its own (the
// portable zip) gets the release page instead.
async function installUpdate() {
  if (!latest) return;
  const text = $("update-text"), button = $("update-open");
  button.disabled = true;
  text.textContent = t("up_downloading", latest.version);
  try {
    const done = await invoke("install_update", { version: latest.version });
    text.textContent = t(done === "installing" ? "up_installing" : "up_opened");
    if (done === "opened") button.disabled = false;
  } catch (e) {
    button.disabled = false;
    if (e === "manual") { text.textContent = t("update_text", latest.version); invoke("open_release", { url: latest.url }).catch(() => {}); }
    else text.textContent = e === "busy" ? t("up_busy") : t("up_error", e);
  }
}

function showUpdateResult(result, loud) {
  const status = $("update-status");
  if (result && typeof result === "object") {
    latest = result;
    $("update-text").textContent = t("update_text", result.version);
    $("update").hidden = false;
    status.textContent = t("up_new", result.version);
  } else if (loud) {
    status.textContent = t(result === "latest" ? "up_latest" : result === "dev" ? "up_dev" : "up_failed");
  }
}

// ---------------------------------------------------------------- wiring
function wire() {
  $("open-settings").addEventListener("click", () => { renderSettings(); showView("settings"); });
  $("back").addEventListener("click", goBack);
  document.addEventListener("keydown", (e) => { if (e.key === "Escape" && (view === "settings" || view === "paired")) goBack(); });
  $("refresh").addEventListener("click", refreshReceivers);
  $("empty-retry").addEventListener("click", refreshReceivers);
  $("welcome-ok").addEventListener("click", () => { $("welcome").hidden = true; save({ welcome_done: true }); });
  $("forget").addEventListener("click", async () => { if (failedReceiverId) { await invoke("forget_receiver", { id: failedReceiverId }); showError(""); refreshReceivers(); } });
  $("update-open").addEventListener("click", installUpdate);
  $("start").addEventListener("click", startCast);
  $("stop").addEventListener("click", () => invoke("stop_cast"));
  $("code-ok").addEventListener("click", submitCode);
  $("code").addEventListener("keydown", (e) => { if (e.key === "Enter") submitCode(); });
  document.querySelectorAll("#view-home .segmented button").forEach((b) => b.addEventListener("click", () => { save({ source_kind: b.dataset.kind }); fillSources(); refreshSources(); }));
  $("source").addEventListener("change", () => save(currentKind() === "monitor" ? { source_monitor: $("source").value } : { source_window: $("source").value }));

  document.querySelectorAll("#view-settings .segmented button").forEach((b) => b.addEventListener("click", async () => { await save({ quality: b.dataset.quality }); renderSettings(); }));
  $("audio").addEventListener("click", async () => { await save({ send_audio: !settings.send_audio }); renderSettings(); });
  $("encoder").addEventListener("change", () => save({ encoder: $("encoder").value }));
  $("language").addEventListener("change", async () => {
    await save({ language: $("language").value });
    applyLanguage(); renderSettings(); renderReceivers(); fillSources();
    $("title-text").textContent = t(TITLES[view] ?? "app_name");
  });
  $("autostart").addEventListener("click", async () => {
    const on = $("autostart").getAttribute("aria-checked") !== "true";
    try { await invoke("autostart_set", { on }); $("autostart").setAttribute("aria-checked", String(on)); }
    catch (e) { showError(String(e)); }
  });
  $("open-paired").addEventListener("click", async () => { await renderPaired(); showView("paired"); });
  $("check-update").addEventListener("click", async () => { $("update-status").textContent = t("up_checking"); showUpdateResult(await checkUpdate(), true); });
  $("logs").addEventListener("click", () => invoke("open_logs").catch((e) => showError(String(e))));
  $("open-source").addEventListener("click", () => invoke("open_release", { url: "https://github.com/weenas/castbay/releases/" }).catch(() => {}));
  listen("status", ({ payload }) => onStatus(payload));
}

async function init() {
  wire();
  await readSettings();
  applyLanguage();
  showView("home");
  fillSources();
  $("welcome").hidden = !!settings.welcome_done;
  await Promise.all([refreshSources(), refreshReceivers()]);
  setInterval(() => { if (!casting && view === "home") refreshReceivers(); }, 15000);
  invoke("autostart_get").then((on) => $("autostart").setAttribute("aria-checked", String(on))).catch(() => {});
  window.__TAURI__.app.getVersion().then((v) => { $("version").textContent = v; }).catch(() => {});
  showUpdateResult(await checkUpdate(), false);
}
init();
