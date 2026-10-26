const { invoke } = window.__TAURI__.core;
const { listen } = window.__TAURI__.event;

const zh = {
  title: "映湾桌面端", subtitle: "把这台电脑的屏幕或某个窗口投到映湾接收端。",
  receivers: "接收端", refresh: "刷新",
  none_found: "没有找到接收端。请在电视或平板上打开映湾，并在设置里开启“映湾桌面投屏”；两者需在同一网络。",
  password: "接收端密码", what: "投什么", screen: "整个屏幕", window: "某个窗口",
  quality: "画质", q_balanced: "均衡（30 帧，8 Mbit/s）", q_smooth: "流畅（60 帧，10 Mbit/s）", q_saver: "省流（30 帧，4 Mbit/s）",
  encoder: "编码器", e_auto: "自动", e_cpu: "CPU（x264）", start: "开始投屏", stop: "停止投屏",
  code_prompt: "请输入接收端屏幕上显示的 6 位配对码。", ok: "确定",
  s_decode: "接收端解码", s_fps: "接收端帧率", s_dropped: "丢帧",
  connecting: "正在连接…", needs_code: "需要配对", casting: "正在投屏到 {0}", casting_detail: "{0}×{1}",
  reconnecting: "连接中断，正在重连…", reconnected: "已重新连接",
  ended: "投屏已结束", ended_stopped_by_receiver: "接收端结束了投屏", ended_replaced: "接收端被另一台设备占用了",
  ended_receiver_stopped: "接收端已停止接收", ended_stopped: "投屏已停止",
  failed: "无法投屏：{0}", logs: "打开日志文件夹", forget: "忘记这个接收端",
  f_busy: "接收端正在被另一台设备使用。", f_blocked: "接收端屏蔽了这台电脑。请在接收端的“已知设备”里解除屏蔽。",
  f_approval_needed: "接收端需要有人在它的屏幕上点“允许”。请确认后重试。", f_locked: "配对尝试次数过多，接收端暂时锁定了配对，请一分钟后重试。",
  f_wrong_password: "接收端密码不对。", f_unsupported: "接收端不支持这次投屏的格式。", f_wrong_code: "配对码不对，请重试。",
  f_code_required: "需要输入配对码才能连接。", f_receiver_changed: "这台接收端的密钥和之前配对的不一样（可能被重置，或不是同一台设备）。如果确认就是它，请先忘记它，再重新配对。",
  f_network: "连不上接收端。请确认两者在同一网络，并且接收端已开启桌面投屏。", f_no_picture: "取不到画面。屏幕是否已锁定，或者要投的窗口已经关闭？",
  f_encoder: "无法启动视频编码器：{0}", f_capture: "屏幕捕获出错：{0}", f_protocol: "和接收端通信出错：{0}", pick_receiver: "请先选择一个接收端", source_screen: "屏幕 {0}", no_sources: "没有可投的内容",
  pair_ask: "需确认", pair_pin: "需配对码", pair_open: "", locked: "有密码",
};
const en = {
  casting: "Casting to {0}", casting_detail: "{0}×{1}", source_screen: "Screen {0}",
  ended_stopped_by_receiver: "The receiver ended the cast", ended_replaced: "Another device took over the receiver",
  ended_receiver_stopped: "The receiver stopped receiving", ended_stopped: "Casting stopped",
  connecting: "Connecting…", needs_code: "Pairing needed", reconnecting: "Connection lost, reconnecting…", reconnected: "Reconnected",
  ended: "Casting ended", failed: "Could not cast: {0}", logs: "Open log folder", forget: "Forget this receiver",
  f_busy: "The receiver is being used by another device.", f_blocked: "The receiver blocks this computer. Unblock it in the receiver's known devices.",
  f_approval_needed: "Someone has to tap Allow on the receiver's screen. Confirm there and try again.", f_locked: "Too many pairing attempts: the receiver paused pairing. Try again in a minute.",
  f_wrong_password: "Wrong receiver password.", f_unsupported: "The receiver does not support this kind of cast.", f_wrong_code: "Wrong pairing code, try again.",
  f_code_required: "A pairing code is needed to connect.", f_receiver_changed: "This receiver's key differs from the one paired before (it may have been reset, or it is another device). If it is the right one, forget it and pair again.",
  f_network: "Cannot reach the receiver. Check both are on the same network and Desktop casting is on at the receiver.", f_no_picture: "No picture arrived. Is the screen locked, or the window closed?",
  f_encoder: "The video encoder could not start: {0}", f_capture: "Screen capture failed: {0}", f_protocol: "Talking to the receiver failed: {0}", pick_receiver: "Pick a receiver first", no_sources: "Nothing to cast",
  pair_ask: "asks first", pair_pin: "needs a code", pair_open: "", locked: "password",
};
const lang = (navigator.language || "en").toLowerCase().startsWith("zh") ? zh : en;
const t = (key, ...args) => (lang[key] ?? en[key] ?? key).replace(/\{(\d)\}/g, (_, i) => args[i] ?? "");
document.documentElement.lang = lang === zh ? "zh" : "en";
if (lang === zh) document.querySelectorAll("[data-i18n]").forEach((el) => { if (zh[el.dataset.i18n]) el.textContent = zh[el.dataset.i18n]; });

const $ = (id) => document.getElementById(id);
let receivers = [];
let selected = null;
let sources = { monitors: [], windows: [] };
let casting = false;

const store = {
  get(key, fallback) { try { return localStorage.getItem(key) ?? fallback; } catch { return fallback; } },
  set(key, value) { try { localStorage.setItem(key, value); } catch {} },
};

let failedReceiverId = null;

function showError(message, forgetId = null) {
  $("error-box").hidden = !message;
  $("error").textContent = message || "";
  failedReceiverId = forgetId;
  $("forget").hidden = !forgetId;
}

function renderReceivers() {
  const list = $("receiver-list");
  list.replaceChildren();
  for (const r of receivers) {
    const li = document.createElement("li");
    li.setAttribute("role", "option");
    li.setAttribute("aria-selected", String(selected?.id === r.id));
    li.tabIndex = 0;
    const name = document.createElement("span");
    name.textContent = r.name;
    const meta = document.createElement("span");
    meta.className = "meta";
    meta.textContent = [t("pair_" + r.pair), r.password ? t("locked") : ""].filter(Boolean).join(" · ");
    li.append(name, meta);
    const pick = () => { selected = r; store.set("receiver", r.id); renderReceivers(); updateStart(); };
    li.addEventListener("click", pick);
    li.addEventListener("keydown", (e) => { if (e.key === "Enter" || e.key === " ") { e.preventDefault(); pick(); } });
    list.append(li);
  }
  $("receiver-empty").hidden = receivers.length > 0;
  $("password-row").hidden = !selected?.password;
}

function updateStart() {
  $("start").disabled = casting || !selected || $("source").options.length === 0;
  $("password-row").hidden = !selected?.password;
}

async function refreshReceivers() {
  $("refresh").disabled = true;
  try {
    receivers = await invoke("discover");
    selected = receivers.find((r) => r.id === selected?.id) ?? receivers.find((r) => r.id === store.get("receiver", "")) ?? null;
    renderReceivers();
  } catch (e) {
    showError(String(e));
  } finally {
    $("refresh").disabled = false;
    updateStart();
  }
}

function fillSources() {
  const kind = document.querySelector('input[name="kind"]:checked').value;
  const select = $("source");
  select.replaceChildren();
  const items = kind === "monitor"
    ? sources.monitors.map((label, i) => [String(i), t("source_screen", label)])
    : sources.windows.map((title) => [title, title]);
  for (const [value, label] of items) {
    const option = document.createElement("option");
    option.value = value;
    option.textContent = label;
    select.append(option);
  }
  const remembered = store.get("source-" + kind, "");
  if ([...select.options].some((o) => o.value === remembered)) select.value = remembered;
  updateStart();
}

async function refreshSources() {
  try {
    sources = await invoke("sources");
    fillSources();
  } catch (e) {
    showError(String(e));
  }
}

function showSession(on) {
  casting = on;
  $("setup").hidden = on;
  $("session").hidden = !on;
  updateStart();
}

function setState(text, detail = "") {
  $("state-line").textContent = text;
  $("detail-line").textContent = detail;
}

listen("status", ({ payload: s }) => {
  showError("");
  switch (s.kind) {
    case "connecting": showSession(true); $("code-box").hidden = true; $("stats").hidden = true; setState(t("connecting")); break;
    case "needs_code": setState(t("needs_code")); $("code-box").hidden = false; $("code").value = ""; $("code").focus(); break;
    case "casting": $("code-box").hidden = true; $("stats").hidden = false; setState(t("casting", s.receiver), t("casting_detail", s.width, s.height)); break;
    case "reconnecting": setState(t("reconnecting")); break;
    case "reconnected": setState(t("casting", selected?.name ?? "")); break;
    case "stats":
      $("s-decode").textContent = `${s.decode_ms} ms`;
      $("s-fps").textContent = s.fps.toFixed(0);
      $("s-dropped").textContent = String(s.dropped);
      break;
    case "ended": showSession(false); { const m = t("ended_" + s.reason); showError(m === "ended_" + s.reason ? "" : m); } break;
    case "failed": {
      showSession(false);
      if (s.code === "stopped") break;
      const known = lang["f_" + s.code] ?? en["f_" + s.code];
      showError(known ? t("f_" + s.code, s.reason) : t("failed", s.reason), s.code === "receiver_changed" ? selected?.id : null);
      break;
    }
  }
});

$("refresh").addEventListener("click", refreshReceivers);
document.querySelectorAll('input[name="kind"]').forEach((r) => r.addEventListener("change", () => { store.set("kind", r.value); fillSources(); refreshSources(); }));
$("source").addEventListener("change", () => store.set("source-" + document.querySelector('input[name="kind"]:checked').value, $("source").value));
$("quality").addEventListener("change", () => store.set("quality", $("quality").value));
$("encoder").addEventListener("change", () => store.set("encoder", $("encoder").value));

$("start").addEventListener("click", async () => {
  if (!selected) { showError(t("pick_receiver")); return; }
  showError("");
  const kind = document.querySelector('input[name="kind"]:checked').value;
  try {
    await invoke("start_cast", {
      args: {
        id: selected.id, ip: selected.ip, port: selected.port,
        password: selected.password ? $("password").value : null,
        kind, value: $("source").value, quality: $("quality").value, encoder: $("encoder").value,
      },
    });
    showSession(true);
    setState(t("connecting"));
  } catch (e) {
    showError(t("failed", String(e)));
  }
});

async function submitCode() {
  const code = $("code").value.trim();
  if (code.length === 6) { $("code-box").hidden = true; setState(t("connecting")); await invoke("submit_code", { code }); }
}
$("code-ok").addEventListener("click", submitCode);
$("code").addEventListener("keydown", (e) => { if (e.key === "Enter") submitCode(); });
$("forget").addEventListener("click", async () => { if (failedReceiverId) { await invoke("forget_receiver", { id: failedReceiverId }); showError(""); } });
$("logs").addEventListener("click", () => invoke("open_logs").catch((e) => showError(String(e))));
$("stop").addEventListener("click", () => invoke("stop_cast"));

{
  const kind = store.get("kind", "monitor");
  const radio = document.querySelector(`input[name="kind"][value="${kind}"]`);
  if (radio) radio.checked = true;
  $("quality").value = store.get("quality", "balanced");
  $("encoder").value = store.get("encoder", "auto");
}
refreshSources();
refreshReceivers();
setInterval(() => { if (!casting) refreshReceivers(); }, 15000);
