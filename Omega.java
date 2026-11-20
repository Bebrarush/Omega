import javax.microedition.midlet.*;
import javax.microedition.lcdui.*;
import javax.microedition.io.*;
import javax.microedition.rms.*;
import javax.microedition.io.file.*;
import java.io.*;
import java.util.Vector;
import java.util.Enumeration;

public class Omega extends MIDlet {
private Display display;
private ConfigCanvas configCanvas;
private TerminalCanvas chatCanvas;
private ThemeCanvas themeCanvas;
private CustomSelectCanvas selectCanvas;

private int navToken = 0;
private boolean appStarted = false;

public int selectedProvider = 0;
public String selectedModel = "gpt-4o-mini";
public String apiKey = "";
public String currentChat = "default_chat";
public Vector availableModels = new Vector();

public int proxyMode = 1; // 0 = DIRECT, 1 = FOR IMAGES ONLY, 2 = FOR EVERYTHING

// --- THEME COLORS ---
public int colorBg = 0x000000;
public int colorMain = 0x00FF00;
public int colorHeaderBg = 0x003200;
public int colorFocusBg = 0x007800;
public int colorWarn = 0xFFFF00;
public int colorError = 0xFF0000;
public int colorSoftbarBg = 0x002800;

public String currentThemeName = "[TERMINAL]";
public boolean isCustomTheme = false;

public String proxyUrl = ""; 
public String systemPrompt = "Plain text without emojis, tables and markdown";





public static final String[] PROVIDER_NAMES = {
    "Ollama Cloud",
    "VoidAI",
    "OpenCode",
    "GroqCloud",
    "OpenRouter"
};

public static final String[] PROVIDER_URLS = {
    "https://ollama.com/api/tags",
    "https://api.voidai.app/v1/models",
    "https://opencode.ai/inference/v1/models",
    "https://api.groq.com/openai/v1/models",
    "https://openrouter.ai/api/v1/models"
};

public static final String[] CHAT_URLS = {
    "https://ollama.com/api/generate",
    "https://api.voidai.app/v1/chat/completions",
    "https://opencode.ai/inference/openai/v1/chat/completions",
    "https://api.groq.com/openai/v1/chat/completions",
    "https://openrouter.ai/api/v1/chat/completions"
};

public Omega() {
    display = Display.getDisplay(this);

    loadTheme();
    boolean hasSettings = loadSettings();
    loadProviderCache(selectedProvider);

    configCanvas = new ConfigCanvas(this);
    chatCanvas = new TerminalCanvas(this);
    themeCanvas = new ThemeCanvas(this);
    selectCanvas = new CustomSelectCanvas(this);

    if (hasSettings && apiKey.length() > 0) {
        chatCanvas.initChatSession(currentChat);
    }
}

protected void startApp() {
    // После паузы телефон вызывает startApp повторно, а заново показывать загрузку не нужно
    if (appStarted) return;
    appStarted = true;

    final BootCanvas bootCanvas = new BootCanvas(this);
    display.setCurrent(bootCanvas);

    new Thread(new Runnable() {
        public void run() {
            try {
                Thread.sleep(1200);
            } catch (Exception e) {}

            RecordStore rs = null;
            boolean hasSettings = false;
            try {
                rs = RecordStore.openRecordStore("OmegaPrefs", false);
                if (rs != null && rs.getNumRecords() > 0) hasSettings = true;
            } catch (Exception e) {}
            finally { if (rs != null) try { rs.closeRecordStore(); } catch (Exception e) {} }

            if (hasSettings && apiKey.length() > 0) {
                safeSetCurrent(chatCanvas);
            } else {
                safeSetCurrent(configCanvas);
            }
            startAutoUpdate();
        }
    }).start();
}

protected void pauseApp() {}
protected void destroyApp(boolean unconditional) {
    saveProviderCache();
    saveSettings();
    saveTheme(currentThemeName);
}

// Переключение экрана по действию пользователя: инвалидирует все запоздавшие фоновые переключения
public void safeSetCurrent(final Displayable d) {
    bumpNav();
    display.callSerially(new Runnable() {
        public void run() {
            display.setCurrent(d);
        }
    });
}

private synchronized int bumpNav() {
    return ++navToken;
}

public synchronized boolean isNavCurrent(int tok) {
    return tok == navToken;
}

// Вызывать в начале фоновой операции, которая в конце захочет показать экран
public int beginAsyncNav() {
    return bumpNav();
}

// Показать экран выбора из фонового потока, только если пользователь за это время никуда не ушёл
public void openSelectionMenuIf(final int tok, final String title, final Vector items, final int initialSel,
                                final SelectCallback callback, final Runnable cancelCallback) {
    display.callSerially(new Runnable() {
        public void run() {
            if (!isNavCurrent(tok)) return;
            selectCanvas.open(title, items, initialSel, callback, cancelCallback);
            display.setCurrent(selectCanvas);
        }
    });
}

public void switchToChat() {
    saveProviderCache();
    saveSettings();
    chatCanvas.initChatSession(currentChat);
    safeSetCurrent(chatCanvas);
}

public void switchToConfig() {
    saveProviderCache();
    saveSettings();
    configCanvas.repaint();
    safeSetCurrent(configCanvas);
}

public void switchToThemes() {
    themeCanvas.repaint();
    safeSetCurrent(themeCanvas);
}

public void openSelectionMenu(String title, Vector items, int initialSel, SelectCallback callback, Runnable cancelCallback) {
    selectCanvas.open(title, items, initialSel, callback, cancelCallback);
    safeSetCurrent(selectCanvas);
}

public void openChatSelectionMenu(Runnable onDone) {
    selectCanvas.openChatSelector(onDone);
    safeSetCurrent(selectCanvas);
}

public void openProxyConfigMenu() {
    final Vector proxyItems = new Vector();
    String mStr = (proxyMode == 0) ? "DIRECT" : (proxyMode == 1 ? "FOR IMAGES ONLY" : "FOR EVERYTHING");
    proxyItems.addElement("[MODE: " + mStr + "]");
    proxyItems.addElement("[URL: " + (proxyUrl.length() > 0 ? proxyUrl : "NOT SET") + "]");

    openSelectionMenu("Proxy Config", proxyItems, 0, new SelectCallback() {
        public void onSelected(int index, String value) {
            if (index == 0) {
                proxyMode = (proxyMode + 1) % 3;
                saveSettings();
                openProxyConfigMenu();
            } else if (index == 1) {
                final TextBox tb = new TextBox("Enter Proxy URL", proxyUrl, 512, TextField.URL);
                tb.addCommand(new Command("OK", Command.OK, 1));
                tb.addCommand(new Command("Cancel", Command.CANCEL, 2));
                tb.setCommandListener(new CommandListener() {
                    public void commandAction(Command c, Displayable d) {
                        if (c.getLabel().equals("OK")) {
                            proxyUrl = tb.getString().trim();
                            saveSettings();
                        }
                        openProxyConfigMenu();
                    }
                });
                safeSetCurrent(tb);
            }
        }
    }, new Runnable() {
        public void run() {
            switchToConfig();
        }
    });
}

// ===== Параметры генерации (temperature и т.п.) =====
// Каждый параметр можно отключить: выключенный в тело запроса не попадает вообще.
private static final String[] PARAM_NAMES = { "temperature", "top_p", "max_tokens", "frequency_penalty", "presence_penalty" };
private static final String[] PARAM_DEFAULTS = { "0.7", "1.0", "1024", "0", "0" };
private boolean[] paramOn = new boolean[5];
private String[] paramVal = new String[5];
private boolean paramsLoaded = false;
 
private void loadParams() {
    if (paramsLoaded) return;
    paramsLoaded = true;
    for (int i = 0; i < 5; i++) { paramOn[i] = false; paramVal[i] = PARAM_DEFAULTS[i]; }
    RecordStore rs = null;
    try {
        rs = RecordStore.openRecordStore("OmegaParams", false);
        if (rs.getNumRecords() > 0) {
            String data = new String(rs.getRecord(1), "UTF-8");
            int idx = 0;
            for (int i = 0; i < 5 && idx <= data.length(); i++) {
                int nl = data.indexOf('\n', idx);
                if (nl == -1) nl = data.length();
                String line = data.substring(idx, nl).trim();
                int bar = line.indexOf('|');
                if (bar > 0) {
                    paramOn[i] = line.substring(0, bar).equals("1");
                    String v = normalizeNumber(line.substring(bar + 1), i == 2);
                    if (v != null) paramVal[i] = v;
                }
                idx = nl + 1;
            }
        }
    } catch (Exception e) {}
    finally { if (rs != null) try { rs.closeRecordStore(); } catch (Exception e) {} }
}
 
private void saveParams() {
    StringBuffer sb = new StringBuffer();
    for (int i = 0; i < 5; i++) {
        sb.append(paramOn[i] ? "1" : "0").append('|').append(paramVal[i]);
        if (i < 4) sb.append('\n');
    }
    RecordStore rs = null;
    try { RecordStore.deleteRecordStore("OmegaParams"); } catch (Exception e) {}
    try {
        rs = RecordStore.openRecordStore("OmegaParams", true);
        byte[] b = sb.toString().getBytes("UTF-8");
        rs.addRecord(b, 0, b.length);
    } catch (Exception e) {}
    finally { if (rs != null) try { rs.closeRecordStore(); } catch (Exception e) {} }
}
 
// Проверяет и приводит число к виду, допустимому в JSON. Возвращает null, если это не число.
private static String normalizeNumber(String s, boolean integer) {
    if (s == null) return null;
    s = s.trim().replace(',', '.');
    if (s.length() == 0 || s.length() > 8) return null;
    boolean dot = false;
    for (int i = 0; i < s.length(); i++) {
        char c = s.charAt(i);
        if (c == '-' && i == 0) continue;
        if (c == '.' && !dot && !integer) { dot = true; continue; }
        if (c < '0' || c > '9') return null;
    }
    boolean neg = s.startsWith("-");
    String body = neg ? s.substring(1) : s;
    if (body.length() == 0 || body.equals(".")) return null;
    if (integer && neg) return null;
    int dotIdx = body.indexOf('.');
    String ip = (dotIdx == -1) ? body : body.substring(0, dotIdx);
    String fp = (dotIdx == -1) ? "" : body.substring(dotIdx + 1);
    if (ip.length() == 0) ip = "0";
    while (ip.length() > 1 && ip.charAt(0) == '0') ip = ip.substring(1);
    if (dotIdx != -1 && fp.length() == 0) fp = "0";
    return (neg ? "-" : "") + ip + (dotIdx != -1 ? "." + fp : "");
}
 
// Фрагмент для тела запроса напрямую к провайдеру: ,"temperature":0.7,... (пустая строка, если всё отключено)
public String paramsJson(int prov) {
    loadParams();
    StringBuffer sb = new StringBuffer();
    for (int i = 0; i < 5; i++) {
        if (!paramOn[i]) continue;
        String name = (prov == 0 && i == 2) ? "num_predict" : PARAM_NAMES[i];
        if (sb.length() > 0) sb.append(',');
        sb.append('"').append(name).append("\":").append(paramVal[i]);
    }
    if (sb.length() == 0) return "";
    if (prov == 0) return ",\"options\":{" + sb.toString() + "}";
    return "," + sb.toString();
}
 
// Значение заголовка X-Omega-Params для прокси: {"temperature":0.7,...} (пустая строка, если всё отключено)
public String paramsHeader() {
    loadParams();
    StringBuffer sb = new StringBuffer();
    for (int i = 0; i < 5; i++) {
        if (!paramOn[i]) continue;
        if (sb.length() > 0) sb.append(',');
        sb.append('"').append(PARAM_NAMES[i]).append("\":").append(paramVal[i]);
    }
    if (sb.length() == 0) return "";
    return "{" + sb.toString() + "}";
}

// ===== Стриминг, скорость печати и размер контекста =====
private boolean streamOn = true;       // получать ответ потоком
private int streamStep = 3;            // сколько символов печатать за один шаг
private int streamDelay = 50;          // пауза между шагами, мс (на деле кратна 50)
private int ctxLimit = 24000;          // сколько символов истории уходит в запрос (0 = вся история)
private boolean streamCfgLoaded = false;


private void loadStreamCfg() {
    if (streamCfgLoaded) return;
    streamCfgLoaded = true;
    RecordStore rs = null;
    try {
        rs = RecordStore.openRecordStore("OmegaStream", false);
        if (rs.getNumRecords() > 0) {
            String data = new String(rs.getRecord(1), "UTF-8");
            int idx = 0;
            for (int i = 0; i < 4 && idx <= data.length(); i++) {
                int nl = data.indexOf('\n', idx);
                if (nl == -1) nl = data.length();
                String line = data.substring(idx, nl).trim();
                idx = nl + 1;
                try {
                    int v = Integer.parseInt(line);
                    if (i == 0) streamOn = (v == 1);
                    else if (i == 1 && v >= 1 && v <= 500) streamStep = v;
                    else if (i == 2 && v >= 0 && v <= 5000) streamDelay = v;
                    else if (i == 3 && v >= 0 && v <= 500000) ctxLimit = v;
                } catch (Exception e) {}
            }
        }
    } catch (Exception e) {}
    finally { if (rs != null) try { rs.closeRecordStore(); } catch (Exception e) {} }
}
 
private void saveStreamCfg() {
    String data = (streamOn ? "1" : "0") + "\n" + streamStep + "\n" + streamDelay + "\n" + ctxLimit;
    RecordStore rs = null;
    try { RecordStore.deleteRecordStore("OmegaStream"); } catch (Exception e) {}
    try {
        rs = RecordStore.openRecordStore("OmegaStream", true);
        byte[] b = data.getBytes("UTF-8");
        rs.addRecord(b, 0, b.length);
    } catch (Exception e) {}
    finally { if (rs != null) try { rs.closeRecordStore(); } catch (Exception e) {} }
}
 
public boolean isStreamOn() { loadStreamCfg(); return streamOn; }
public int getStreamStep() { loadStreamCfg(); return streamStep; }
public int getStreamDelay() { loadStreamCfg(); return streamDelay; }
public int getCtxLimit() { loadStreamCfg(); return ctxLimit; }

 
// Оставляет в истории только последние сообщения, которые влезают в limit символов.
// Начинается всегда с вопроса пользователя. Если ничего не обрезано, возвращает ту же строку.
public static String trimHistory(String hist, int limit) {
    if (hist == null || limit <= 0 || hist.length() <= limit) return hist;
    String key = "{\"role\":\"";
    Vector starts = new Vector();
    int p = 0;
    while (true) {
        int i = hist.indexOf(key, p);
        if (i == -1) break;
        starts.addElement(new Integer(i));
        p = i + key.length();
    }
    if (starts.size() < 2) return hist;
    int end = hist.lastIndexOf(']');
    if (end == -1) return hist;
 
    int pick = -1;
    for (int k = 0; k < starts.size(); k++) {
        int s = ((Integer) starts.elementAt(k)).intValue();
        if (end - s + 2 > limit) continue;                        // этот хвост не влезает
        if (hist.startsWith("user", s + key.length())) { pick = s; break; }
    }
    if (pick == -1) {
        // Даже последний обмен не влезает: оставляем хотя бы последний вопрос пользователя
        for (int k = starts.size() - 1; k >= 0; k--) {
            int s = ((Integer) starts.elementAt(k)).intValue();
            if (hist.startsWith("user", s + key.length())) { pick = s; break; }
        }
    }
    if (pick == -1) return hist;
    return "[" + hist.substring(pick, end) + "]";
}
 
private String fallbackModels = "";
private boolean fallbackLoaded = false;
 
private void loadFallback() {
    if (fallbackLoaded) return;
    fallbackLoaded = true;
    RecordStore rs = null;
    try {
        rs = RecordStore.openRecordStore("OmegaFallback", false);
        if (rs.getNumRecords() > 0) fallbackModels = cleanFallback(new String(rs.getRecord(1), "UTF-8"));
    } catch (Exception e) {}
    finally { if (rs != null) try { rs.closeRecordStore(); } catch (Exception e) {} }
}
 
private void saveFallback() {
    RecordStore rs = null;
    try { RecordStore.deleteRecordStore("OmegaFallback"); } catch (Exception e) {}
    try {
        rs = RecordStore.openRecordStore("OmegaFallback", true);
        byte[] b = fallbackModels.getBytes("UTF-8");
        rs.addRecord(b, 0, b.length);
    } catch (Exception e) {}
    finally { if (rs != null) try { rs.closeRecordStore(); } catch (Exception e) {} }
}
 
// Оставляет в списке только допустимые для id символы, имена через запятую, не больше 5 штук
private static String cleanFallback(String s) {
    if (s == null) return "";
    StringBuffer out = new StringBuffer();
    StringBuffer tok = new StringBuffer();
    int count = 0;
    for (int i = 0; i <= s.length(); i++) {
        char ch = (i < s.length()) ? s.charAt(i) : ',';
        boolean ok = (ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z') || (ch >= '0' && ch <= '9')
                  || ch == '/' || ch == '-' || ch == '_' || ch == '.' || ch == ':' || ch == '~';
        if (ok) {
            if (tok.length() < 80) tok.append(ch);
        } else if (tok.length() > 0) {
            if (count < 5) {
                if (out.length() > 0) out.append(',');
                out.append(tok.toString());
                count++;
            }
            tok.setLength(0);
        }
    }
    return out.toString();
}
 
public String getFallbackModels() { loadFallback(); return fallbackModels; }
 
// Фрагмент для тела запроса к OpenRouter: ,"models":["a","b"] (пустая строка, если список пуст или провайдер другой)
public String fallbackJson(int prov) {
    loadFallback();
    if (prov != 4 || fallbackModels.length() == 0) return "";
    StringBuffer sb = new StringBuffer(",\"models\":[");
    int start = 0;
    boolean first = true;
    while (start <= fallbackModels.length()) {
        int comma = fallbackModels.indexOf(',', start);
        if (comma == -1) comma = fallbackModels.length();
        String m = fallbackModels.substring(start, comma);
        if (m.length() > 0) {
            if (!first) sb.append(',');
            sb.append('"').append(m).append('"');
            first = false;
        }
        start = comma + 1;
    }
    sb.append(']');
    return sb.toString();
}
 
// Значение заголовка X-Omega-Fallback для прокси (пустая строка, если не нужно)
public String fallbackHeader(int prov) {
    loadFallback();
    return (prov == 4) ? fallbackModels : "";
}
 
private void openFallbackBox() {
    loadFallback();
    final TextBox tb = new TextBox("Fallback models, comma separated", fallbackModels, 400, TextField.ANY);
    tb.addCommand(new Command("OK", Command.OK, 1));
    tb.addCommand(new Command("Cancel", Command.CANCEL, 2));
    tb.setCommandListener(new CommandListener() {
        public void commandAction(Command c, Displayable d) {
            if (c.getLabel().equals("OK")) {
                fallbackModels = cleanFallback(tb.getString());
                saveFallback();
            }
            openParamsMenu(10);
        }
    });
    safeSetCurrent(tb);
}

private String updRepo = "Bebrarush/Omega";
private int updSrc = 0;                  // 0: файлы релиза на GitHub (Releases), 1: файлы в репозитории (raw)
private String updTag = "";              // тег релиза; пусто = последний (latest)
private String updBranch = "main";
private String updFolder = "";
private String updName = "";
private boolean updAuto = true;
private boolean updLoaded = false;
private String updStatus = "not checked yet";
private String updRemote = "";
private boolean updAvail = false;
private boolean updBusy = false;
private boolean updAutoDone = false;
private String updErr = "";
 
// Версия установленного приложения из JAD/манифеста
private String updInstalled() {
    String v = getAppProperty("MIDlet-Version");
    return (v == null || v.trim().length() == 0) ? "?" : v.trim();
}
 
// Оставляет в строке только допустимые символы: буквы, цифры, _ . - и при необходимости /
private String updClean(String s, boolean slash) {
    if (s == null) return "";
    StringBuffer sb = new StringBuffer();
    for (int i = 0; i < s.length(); i++) {
        char c = s.charAt(i);
        if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_' || c == '.' || c == '-' || (slash && c == '/')) sb.append(c);
    }
    String r = sb.toString();
    while (r.startsWith("/")) r = r.substring(1);
    while (r.endsWith("/")) r = r.substring(0, r.length() - 1);
    return r;
}
 
// Имя файла по умолчанию: берётся из адреса JAR, с которого приложение установлено (так отличаются сборки вроде Omega_s40_fix)
private String updGuessName() {
    String u = getAppProperty("MIDlet-Jar-URL");
    if (u == null) return "Omega";
    int q = u.indexOf('?');
    if (q >= 0) u = u.substring(0, q);
    int sl = u.lastIndexOf('/');
    if (sl >= 0) u = u.substring(sl + 1);
    sl = u.lastIndexOf('\\');
    if (sl >= 0) u = u.substring(sl + 1);
    if (u.toLowerCase().endsWith(".jar")) u = u.substring(0, u.length() - 4);
    u = updClean(u, false);
    return u.length() == 0 ? "Omega" : u;
}
 
private void loadUpd() {
    if (updLoaded) return;
    updLoaded = true;
    updName = updGuessName();
    RecordStore rs = null;
    try {
        rs = RecordStore.openRecordStore("OmegaUpdate", false);
        if (rs.getNumRecords() > 0) {
            String d = new String(rs.getRecord(1), "UTF-8");
            String[] f = new String[7];
            int n = 0, st = 0;
            for (int i = 0; i <= d.length() && n < 7; i++) {
                if (i == d.length() || d.charAt(i) == '\n') { f[n++] = d.substring(st, i); st = i + 1; }
            }
            if (n == 7) {
                updSrc = f[5].equals("1") ? 1 : 0;
                updTag = updClean(f[6], false);
                String r = updClean(f[0], true);
                if (r.indexOf('/') > 0) updRepo = r;
                String b = updClean(f[1], true);
                if (b.length() > 0) updBranch = b;
                updFolder = updClean(f[2], true);
                String nm = updClean(f[3], false);
                if (nm.length() > 0) updName = nm;
                updAuto = !f[4].equals("0");
            }
        }
    } catch (Exception e) {}
    finally { if (rs != null) try { rs.closeRecordStore(); } catch (Exception e) {} }
}
 
private void saveUpd() {
    RecordStore rs = null;
    try { RecordStore.deleteRecordStore("OmegaUpdate"); } catch (Exception e) {}
    try {
        rs = RecordStore.openRecordStore("OmegaUpdate", true);
        byte[] b = (updRepo + "\n" + updBranch + "\n" + updFolder + "\n" + updName + "\n" + (updAuto ? "1" : "0") + "\n" + updSrc + "\n" + updTag).getBytes("UTF-8");
        rs.addRecord(b, 0, b.length);
    } catch (Exception e) {}
    finally { if (rs != null) try { rs.closeRecordStore(); } catch (Exception e) {} }
}
 
// Через прокси ходим только когда он включён для всего; иначе телефон стучится в GitHub напрямую
private boolean updUseProxy() {
    return proxyMode == 2 && proxyUrl != null && proxyUrl.trim().length() > 0;
}
 
private String updProxyBase() {
    String b = proxyUrl.trim();
    if (b.endsWith("/")) b = b.substring(0, b.length() - 1);
    return b;
}
 
private String updQuery() {
    return "repo=" + escapeUrl(updRepo) + "&src=" + (updSrc == 1 ? "raw" : "release") + "&tag=" + escapeUrl(updTag) + "&branch=" + escapeUrl(updBranch) + "&folder=" + escapeUrl(updFolder) + "&name=" + escapeUrl(updName);
}
 
private String updDirectUrl(String ext) {
    if (updSrc == 0) {
        if (updTag.length() == 0) return "https://github.com/" + updRepo + "/releases/latest/download/" + updName + ext;
        return "https://github.com/" + updRepo + "/releases/download/" + updTag + "/" + updName + ext;
    }
    String u = "https://raw.githubusercontent.com/" + updRepo + "/" + updBranch + "/";
    if (updFolder.length() > 0) u += updFolder + "/";
    return u + updName + ext;
}
 
// Адрес, который откроет браузер для установки: JAR напрямую с GitHub или через прокси
private String updInstallUrl() {
    if (updUseProxy()) return updProxyBase() + "/" + updName + ".jar?type=jar&" + updQuery();
    return updDirectUrl(".jar");
}
 
// Небольшой текстовый GET с переходами по редиректам. null при ошибке, причина в updErr
private String updHttpGet(String url) {
    for (int hop = 0; hop < 4; hop++) {
        HttpConnection c = null;
        InputStream is = null;
        try {
            c = (HttpConnection) Connector.open(url, Connector.READ);
            c.setRequestMethod(HttpConnection.GET);
            c.setRequestProperty("User-Agent", "Omega-Symbian");
            c.setRequestProperty("Connection", "close");
            int rc = c.getResponseCode();
            if (rc == 301 || rc == 302 || rc == 303 || rc == 307 || rc == 308) {
                String loc = c.getHeaderField("Location");
                if (loc == null || loc.length() == 0) { updErr = "bad redirect"; return null; }
                url = loc;
                continue;
            }
            if (rc != 200) { updErr = "HTTP " + rc; return null; }
            is = c.openInputStream();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[512];
            int n;
            while ((n = is.read(buf)) != -1) {
                bo.write(buf, 0, n);
                if (bo.size() > 16384) break;
            }
            return new String(bo.toByteArray(), "UTF-8");
        } catch (Exception e) {
            String m = String.valueOf(e);
            if (m.length() > 40) m = m.substring(0, 40);
            updErr = "network: " + m;
            return null;
        } finally {
            try { if (is != null) is.close(); } catch (Exception e) {}
            try { if (c != null) c.close(); } catch (Exception e) {}
        }
    }
    updErr = "too many redirects";
    return null;
}
 
// Значение строки "Ключ: значение" из JAD или манифеста
private String updJadValue(String jad, String key) {
    int i = 0, len = jad.length();
    while (i < len) {
        int e = jad.indexOf('\n', i);
        if (e < 0) e = len;
        String line = jad.substring(i, e).trim();
        int c = line.indexOf(':');
        if (c > 0 && line.substring(0, c).trim().equalsIgnoreCase(key)) return line.substring(c + 1).trim();
        i = e + 1;
    }
    return "";
}
 
// Числа из строки версии: "1.2.10" -> {1,2,10,0,0,0}
private int[] updNums(String v) {
    int[] r = new int[6];
    int idx = 0, cur = -1;
    for (int i = 0; i <= v.length() && idx < 6; i++) {
        char c = (i < v.length()) ? v.charAt(i) : ' ';
        if (c >= '0' && c <= '9') {
            cur = (cur < 0 ? 0 : cur) * 10 + (c - '0');
            if (cur > 100000) cur = 100000;
        } else if (cur >= 0) {
            r[idx++] = cur;
            cur = -1;
        }
    }
    return r;
}
 
// Больше нуля, если версия a новее версии b
private int updCompare(String a, String b) {
    int[] x = updNums(a), y = updNums(b);
    for (int i = 0; i < 6; i++) {
        if (x[i] != y[i]) return x[i] > y[i] ? 1 : -1;
    }
    return 0;
}
 
// Синхронная проверка: обновляет updStatus, updRemote, updAvail. Вызывать из фонового потока
private void updCheckCore() {
    synchronized (this) {
        if (updBusy) return;
        updBusy = true;
    }
    try {
        loadUpd();
        updAvail = false;
        updErr = "";
        String jad;
        if (updUseProxy()) {
            String r = updHttpGet(updProxyBase() + "/?type=update&" + updQuery());
            if (r == null) jad = null;
            else if (r.startsWith("OK")) jad = r.substring(2);
            else { updErr = r.trim(); if (updErr.length() > 40) updErr = updErr.substring(0, 40); jad = null; }
        } else {
            jad = updHttpGet(updDirectUrl(".jad"));
        }
        if (jad == null) {
            updStatus = "error: " + updErr;
            if (!updUseProxy() && updErr.startsWith("network")) updStatus += " (try proxy: FOR EVERYTHING)";
            return;
        }
        String rv = updJadValue(jad, "MIDlet-Version");
        if (rv.length() == 0) { updStatus = "no MIDlet-Version in JAD"; return; }
        updRemote = rv;
        if (updCompare(rv, updInstalled()) > 0) {
            updAvail = true;
            updStatus = "new version v" + rv;
        } else {
            updStatus = "up to date (v" + rv + ")";
        }
    } catch (Exception e) {
        updStatus = "error: " + e;
    } finally {
        updBusy = false;
    }
}
 
// Проверка при запуске, если включена в настройках: в фоне, и коротким окошком сообщает, если есть новая версия
public void startAutoUpdate() {
    loadUpd();
    if (!updAuto || updAutoDone) return;
    updAutoDone = true;
    new Thread(new Runnable() {
        public void run() {
            try { Thread.sleep(5000); } catch (Exception e) {}
            updCheckCore();
            if (!updAvail) return;
            display.callSerially(new Runnable() {
                public void run() {
                    Displayable cur = display.getCurrent();
                    if (cur == null) return;
                    Alert al = new Alert("Update", "New version v" + updRemote + " is available. Open Params, then UPDATE to install.", null, AlertType.INFO);
                    al.setTimeout(6000);
                    display.setCurrent(al, cur);
                }
            });
        }
    }).start();
}
 
private Vector updItems() {
    Vector it = new Vector();
    it.addElement(updBusy ? "[CHECKING...]" : "[CHECK NOW]");
    it.addElement(updAvail ? "[INSTALL v" + updRemote + "]" : "[STATUS: " + updStatus + "]");
    it.addElement("[INSTALLED: v" + updInstalled() + "]");
    it.addElement("[SOURCE: " + (updSrc == 0 ? "RELEASES" : "REPO FILES") + "]");
    it.addElement("[REPO: " + updRepo + "]");
    it.addElement("[TAG: " + (updTag.length() == 0 ? "latest" : updTag) + "]");
    it.addElement("[BRANCH: " + updBranch + (updSrc == 0 ? " (unused)" : "") + "]");
    it.addElement("[FOLDER: " + (updFolder.length() == 0 ? "(root)" : updFolder) + (updSrc == 0 ? " (unused)" : "") + "]");
    it.addElement("[FILE: " + updName + ".jar]");
    it.addElement("[AUTO CHECK: " + (updAuto ? "ON" : "OFF") + "]");
    it.addElement("[VIA: " + (updUseProxy() ? "PROXY" : "DIRECT") + "]");
    return it;
}
 
private SelectCallback updCallback() {
    return new SelectCallback() {
        public void onSelected(int index, String value) {
            if (index == 0) {
                if (!updBusy) {
                    updStatus = "checking...";
                    updAvail = false;
                    openUpdateMenu(0);
                    updCheckAsync();
                } else {
                    openUpdateMenu(0);
                }
            } else if (index == 1) {
                if (updAvail) updAskInstall();
                else openUpdateMenu(1);
            } else if (index == 2) {
                openUpdateMenu(2);
            } else if (index == 3) {
                updSrc = 1 - updSrc;
                updAvail = false;
                updStatus = "not checked yet";
                saveUpd();
                openUpdateMenu(3);
            } else if (index >= 4 && index <= 8) {
                openUpdText(index);
            } else if (index == 9) {
                updAuto = !updAuto;
                saveUpd();
                openUpdateMenu(9);
            } else if (index == 10) {
                openProxyConfigMenu();
            }
        }
    };
}
 
public void openUpdateMenu(int sel) {
    loadUpd();
    openSelectionMenu("Update", updItems(), sel, updCallback(), new Runnable() { public void run() { openParamsMenu(11); } });
}
 
// Проверка в фоне; по окончании меню обновляется, если пользователь никуда не ушёл
private void updCheckAsync() {
    final int tok = beginAsyncNav();
    new Thread(new Runnable() {
        public void run() {
            updCheckCore();
            openSelectionMenuIf(tok, "Update", updItems(), updAvail ? 1 : 0, updCallback(), new Runnable() { public void run() { openParamsMenu(11); } });
        }
    }).start();
}
 
private void updAskInstall() {
    Vector it = new Vector();
    it.addElement("[YES: OPEN DOWNLOAD IN BROWSER]");
    it.addElement("[NO]");
    openSelectionMenu("Install v" + updRemote + "?", it, 1, new SelectCallback() {
        public void onSelected(int index, String value) {
            if (index == 0) updInstall();
            else openUpdateMenu(1);
        }
    }, new Runnable() { public void run() { openUpdateMenu(1); } });
}
 
// Установка: браузер или системный установщик скачивает JAR и предлагает установить
private void updInstall() {
    boolean mustExit = false;
    try {
        mustExit = platformRequest(updInstallUrl());
        updStatus = "download opened";
    } catch (ConnectionNotFoundException e) {
        updStatus = "cannot open browser";
    } catch (Exception e) {
        updStatus = "error: " + e;
    }
    if (mustExit) {
        destroyApp(true);
        notifyDestroyed();
        return;
    }
    openUpdateMenu(1);
}
 
private void openUpdText(final int idx) {
    String title = (idx == 4) ? "Repo (owner/name)" : (idx == 5 ? "Release tag (empty = latest)" : (idx == 6 ? "Branch" : (idx == 7 ? "Folder (empty = root)" : "File name without extension")));
    String cur = (idx == 4) ? updRepo : (idx == 5 ? updTag : (idx == 6 ? updBranch : (idx == 7 ? updFolder : updName)));
    final TextBox tb = new TextBox(title, cur, 100, TextField.ANY);
    tb.addCommand(new Command("OK", Command.OK, 1));
    tb.addCommand(new Command("Cancel", Command.CANCEL, 2));
    tb.setCommandListener(new CommandListener() {
        public void commandAction(Command c, Displayable d) {
            if (c.getLabel().equals("OK")) {
                String v = updClean(tb.getString(), idx == 4 || idx == 6 || idx == 7);
                if (idx == 4) { if (v.indexOf('/') > 0 && v.indexOf('/') == v.lastIndexOf('/') && !v.endsWith("/")) updRepo = v; }
                else if (idx == 5) updTag = v;
                else if (idx == 6) { if (v.length() > 0) updBranch = v; }
                else if (idx == 7) updFolder = v;
                else if (v.length() > 0) updName = v;
                updAvail = false;
                updStatus = "not checked yet";
                saveUpd();
            }
            openUpdateMenu(idx);
        }
    });
    safeSetCurrent(tb);
}
 
public void openParamsMenu(int sel) {
    loadParams();
    loadStreamCfg();
    loadFallback();
    Vector items = new Vector();
    for (int i = 0; i < 5; i++) {
        items.addElement("[" + (paramOn[i] ? "ON " : "OFF") + "] " + PARAM_NAMES[i] + " = " + paramVal[i]);
    }
    items.addElement("[ALL OFF]");
    items.addElement("[STREAM: " + (streamOn ? "ON" : "OFF") + "]");
    items.addElement("[TYPE STEP: " + streamStep + " chars]");
    items.addElement("[TYPE DELAY: " + streamDelay + " ms]");
    items.addElement("[CONTEXT: " + (ctxLimit == 0 ? "ALL" : ctxLimit + " chars") + "]");
    items.addElement("[OPENROUTER FALLBACK: " + (fallbackModels.length() == 0 ? "OFF" : "SET") + "]");
    items.addElement("[UPDATE: v" + updInstalled() + "]");
 
    openSelectionMenu("Generation Params", items, sel, new SelectCallback() {
        public void onSelected(int index, String value) {
            if (index < 5) {
                openParamEditMenu(index);
            } else if (index == 5) {
                for (int i = 0; i < 5; i++) paramOn[i] = false;
                saveParams();
                openParamsMenu(5);
            } else if (index == 6) {
                streamOn = !streamOn;
                saveStreamCfg();
                openParamsMenu(6);
            } else if (index >= 7 && index <= 9) {
                openStreamValueBox(index);
            } else if (index == 10) {
                openFallbackBox();
            } else if (index == 11) {
                openUpdateMenu(0);
            }
        }
    }, new Runnable() { public void run() { switchToConfig(); } });
}
 
private void openStreamValueBox(final int idx) {
    String title = (idx == 7) ? "Type step (chars)" : ((idx == 8) ? "Type delay (ms)" : "Context chars (0 = all)");
    String cur = String.valueOf((idx == 7) ? streamStep : ((idx == 8) ? streamDelay : ctxLimit));
    final TextBox tb = new TextBox(title, cur, 8, TextField.NUMERIC);
    tb.addCommand(new Command("OK", Command.OK, 1));
    tb.addCommand(new Command("Cancel", Command.CANCEL, 2));
    tb.setCommandListener(new CommandListener() {
        public void commandAction(Command c, Displayable d) {
            if (c.getLabel().equals("OK")) {
                try {
                    int v = Integer.parseInt(tb.getString().trim());
                    if (idx == 7) streamStep = (v < 1) ? 1 : (v > 500 ? 500 : v);
                    else if (idx == 8) streamDelay = (v < 0) ? 0 : (v > 5000 ? 5000 : v);
                    else ctxLimit = (v == 0) ? 0 : (v < 500 ? 500 : (v > 500000 ? 500000 : v));
                    saveStreamCfg();
                } catch (Exception e) {}
            }
            openParamsMenu(idx);
        }
    });
    safeSetCurrent(tb);
}
 
private void openParamEditMenu(final int idx) {
    Vector opts = new Vector();
    opts.addElement(paramOn[idx] ? "[TURN OFF] (not sent)" : "[TURN ON]");
    opts.addElement("[EDIT VALUE: " + paramVal[idx] + "]");
    opts.addElement("[RESET: " + PARAM_DEFAULTS[idx] + "]");

    openSelectionMenu(PARAM_NAMES[idx], opts, 0, new SelectCallback() {
        public void onSelected(int index, String value) {
            if (index == 0) {
                paramOn[idx] = !paramOn[idx];
                saveParams();
                openParamsMenu(idx);
            } else if (index == 1) {
                openParamValueBox(idx);
            } else if (index == 2) {
                paramVal[idx] = PARAM_DEFAULTS[idx];
                saveParams();
                openParamsMenu(idx);
            }
        }
    }, new Runnable() { public void run() { openParamsMenu(idx); } });
}
 
private void openParamValueBox(final int idx) {
    final TextBox tb = new TextBox(PARAM_NAMES[idx], paramVal[idx], 12, TextField.ANY);
    tb.addCommand(new Command("OK", Command.OK, 1));
    tb.addCommand(new Command("Cancel", Command.CANCEL, 2));
    tb.setCommandListener(new CommandListener() {
        public void commandAction(Command c, Displayable d) {
            if (c.getLabel().equals("OK")) {
                String v = normalizeNumber(tb.getString(), idx == 2);
                if (v != null) {
                    paramVal[idx] = v;
                    paramOn[idx] = true;   // если значение ввели, значит, оно нужно
                    saveParams();
                }
            }
            openParamsMenu(idx);
        }
    });
    safeSetCurrent(tb);
}

public interface SelectCallback {
    void onSelected(int index, String value);
}

public static int emphColor(int main, int bg) {
    // Цвет для выделения: основной цвет, сдвинутый к белому на тёмном фоне и к чёрному на светлом
    int lum = (((bg >> 16) & 0xFF) * 3 + ((bg >> 8) & 0xFF) * 6 + (bg & 0xFF)) / 10;
    return mixColor(main, lum < 128 ? 0xFFFFFF : 0x000000, 45);
}

public static Font uiFont() {
    return Font.getFont(Font.FACE_MONOSPACE, Font.STYLE_BOLD, Font.SIZE_MEDIUM);
}

// ===== Разметка текста сообщений: markdown, перенос строк по таблице ширин символов =====
// Не зависит от экрана: получает шрифт, ширину строки и три вектора для результата.
static class MdLayout {
    static final byte S_BOLD = 1, S_ITAL = 2, S_CODE = 4, S_HEAD = 8, S_QUOTE = 16, S_LINK = 32;
    // Роли строк: 0 вопрос, 1 ответ, 2 пустая, 3 системная, 4 кнопка, 5 код, 6 приглушённая (линия, разделитель)
    static final Integer[] ROLE = { new Integer(0), new Integer(1), new Integer(2), new Integer(3), new Integer(4), new Integer(5), new Integer(6) };
    static final byte[] NONE = new byte[0];
 
    // Ширина символа считается один раз и запоминается: так перенос строк не вызывает шрифт на каждый символ
    private static int[] cwTab = new int[0x500];
    private static Font cwFont = null;
 
    static int charW(Font f, char c) {
        if (f != cwFont) { cwFont = f; cwTab = new int[0x500]; }
        if (c < 0x500) {
            int v = cwTab[c];
            if (v == 0) { v = f.charWidth(c) + 1; cwTab[c] = v; }
            return v - 1;
        }
        return f.charWidth(c);
    }
 
    static int textW(Font f, String s, int from, int to) {
        int w = 0;
        for (int i = from; i < to; i++) w += charW(f, s.charAt(i));
        return w;
    }
 
    private Font font;
    int maxW;
    private int spaceW;
    private Vector outLines, outStyles, outRoles;
    private int baseRole = 1;
 
    // Состояние между исходными строками
    boolean inCode = false;
    boolean lastBlank = true;
    String prefix = null;            // подпись "модель> " для первой строки сообщения
    private boolean sCode, sBlank;
    private String sPrefix;
 
    MdLayout(Font f, int maxWidth) {
        font = f;
        maxW = maxWidth;
        spaceW = charW(f, ' ');
        if (spaceW < 1) spaceW = 1;
    }
 
    void setTarget(Vector lines, Vector styles, Vector roles, int role) {
        outLines = lines; outStyles = styles; outRoles = roles; baseRole = role;
        // Если кто-то добавлял в lines строки напрямую (системные сообщения), выравниваем параллельные векторы
        while (outRoles.size() < outLines.size()) outRoles.addElement(ROLE[3]);
        while (outStyles.size() < outLines.size()) outStyles.addElement(NONE);
    }
 
    // Запомнить и вернуть состояние: нужно, чтобы незавершённый хвост ответа можно было пересчитывать много раз
    void mark() { sCode = inCode; sBlank = lastBlank; sPrefix = prefix; }
    void rollback() { inCode = sCode; lastBlank = sBlank; prefix = sPrefix; }
 
    private void emit(String line, byte[] sty, int kind) {
        outLines.addElement(line);
        outStyles.addElement(sty == null ? NONE : sty);
        int role = baseRole;
        if (kind == 1) role = 5; else if (kind == 2) role = 6; else if (kind == 3) role = 2;
        outRoles.addElement(ROLE[role]);
    }
 
    private String takePrefix() {
        String p = (prefix == null) ? "" : prefix;
        prefix = null;
        return p;
    }
 
    // Если сообщение так и не вывело подпись (пустой ответ), выводим её отдельной строкой
    void finish() {
        if (prefix != null) { emit(prefix.trim(), null, 0); prefix = null; }
    }
 
    // Пустая строка между сообщениями
    void separator() {
        emit("", null, 3);
        lastBlank = true;
    }
 
    // Незавершённая строка (ещё нет перевода строки): раскладываем, но состояние не меняем
    void addPartial(String tail) {
        mark();
        if (tail.length() == 0) {
            if (prefix != null) emit(prefix.trim(), null, 0);
        } else {
            addSourceLine(tail, true);
        }
        rollback();
    }
 
    void addSourceLine(String src, boolean markdown) {
        src = norm(src);
        String t = src.trim();
        if (!markdown) {
            if (t.length() == 0) { addBlank(); return; }
            wrap(takePrefix() + src, null, 0, 0);
            lastBlank = false;
            return;
        }
        if (t.startsWith("```") || t.startsWith("~~~")) {
            inCode = !inCode;
            if (inCode && prefix != null) { emit(prefix.trim(), null, 0); prefix = null; lastBlank = false; }
            return;
        }
        if (inCode) { wrapHard(src); lastBlank = false; return; }
        if (t.length() == 0) { addBlank(); return; }
 
        // Заголовок: # ... ######
        int hl = 0;
        while (hl < t.length() && hl < 6 && t.charAt(hl) == '#') hl++;
        if (hl > 0 && hl < t.length() && t.charAt(hl) == ' ') {
            if (!lastBlank && prefix == null) addBlank();
            String p = takePrefix();
            String plain = parseInline(t.substring(hl + 1).trim(), S_HEAD);
            wrap(p + plain, shift(inlineStyles, p.length(), S_HEAD, p.length() + plain.length()), 0, 0);
            lastBlank = false;
            return;
        }
        // Горизонтальная линия и разделитель строки таблицы
        if (isRule(t)) {
            String p = takePrefix();
            int n = (maxW - textW(font, p, 0, p.length())) / charW(font, '-');
            if (n < 3) n = 3;
            StringBuffer sb = new StringBuffer(p);
            for (int i = 0; i < n; i++) sb.append('-');
            emit(sb.toString(), null, 2);
            lastBlank = false;
            return;
        }
        // Цитата
        if (t.charAt(0) == '>') {
            int k = 0;
            while (k < t.length() && (t.charAt(k) == '>' || t.charAt(k) == ' ')) k++;
            String lead = takePrefix() + "| ";
            String plain = parseInline(t.substring(k), S_QUOTE);
            wrap(lead + plain, shift(inlineStyles, lead.length(), S_QUOTE, lead.length() + plain.length()), lead.length(), 0);
            lastBlank = false;
            return;
        }
        // Пункт списка: -, *, + или 1. / 1)
        int sp = 0;
        while (sp < src.length() && src.charAt(sp) == ' ') sp++;
        String marker = null;
        int cs = sp;
        if (sp + 1 < src.length() && (src.charAt(sp) == '-' || src.charAt(sp) == '*' || src.charAt(sp) == '+') && src.charAt(sp + 1) == ' ') {
            marker = "- ";
            cs = sp + 2;
        } else {
            int d = sp;
            while (d < src.length() && src.charAt(d) >= '0' && src.charAt(d) <= '9') d++;
            if (d > sp && d - sp <= 3 && d + 1 < src.length() && (src.charAt(d) == '.' || src.charAt(d) == ')') && src.charAt(d + 1) == ' ') {
                marker = src.substring(sp, d) + ". ";
                cs = d + 2;
            }
        }
        if (marker != null) {
            int lvl = sp / 2;
            if (lvl > 4) lvl = 4;
            StringBuffer lead = new StringBuffer(takePrefix());
            for (int i = 0; i < lvl * 2; i++) lead.append(' ');
            lead.append(marker);
            String plain = parseInline(src.substring(cs).trim(), 0);
            wrap(lead.toString() + plain, shift(inlineStyles, lead.length(), (byte) 0, lead.length() + plain.length()), lead.length(), 0);
            lastBlank = false;
            return;
        }
        // Обычный абзац
        String p = takePrefix();
        String plain = parseInline(t, 0);
        wrap(p + plain, shift(inlineStyles, p.length(), (byte) 0, p.length() + plain.length()), 0, 0);
        lastBlank = false;
    }
 
    private void addBlank() {
        if (lastBlank) return;
        emit("", null, 3);
        lastBlank = true;
    }
 
    private static String norm(String s) {
        StringBuffer sb = null;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\t' || c == '\r' || (c < 32)) {
                if (sb == null) { sb = new StringBuffer(s.length() + 8); sb.append(s.substring(0, i)); }
                if (c == '\t') sb.append("    ");
                // остальные управляющие символы отбрасываем
            } else if (sb != null) sb.append(c);
        }
        return sb == null ? s : sb.toString();
    }
 
    private static boolean isRule(String t) {
        // ---, ***, ___ (можно с пробелами) или строка-разделитель таблицы |---|:--:|
        char first = t.charAt(0);
        int cnt = 0;
        boolean allSame = true, tableSep = (t.indexOf('-') != -1);
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c == ' ') continue;
            if (c == first && (c == '-' || c == '*' || c == '_')) cnt++; else allSame = false;
            if (!(c == '|' || c == '-' || c == ':' || c == ' ')) tableSep = false;
        }
        if (allSame && cnt >= 3) return true;
        return tableSep && t.indexOf('|') != -1;
    }
 
    // Стили строки со сдвигом на lead символов вправо; null, если стилей нет
    private static byte[] shift(byte[] st, int lead, int whole, int total) {
        boolean any = (whole != 0);
        if (!any && st != null) {
            for (int i = 0; i < st.length; i++) if (st[i] != 0) { any = true; break; }
        }
        if (!any) return null;
        byte[] r = new byte[total];
        if (st != null) {
            for (int i = 0; i < st.length && lead + i < total; i++) r[lead + i] = st[i];
        }
        return r;
    }
    private static byte[] shift(byte[] st, int lead, byte whole, int total) { return shift(st, lead, (int) whole, total); }
 
    // Перенос по словам. Первая строка уже содержит нужные отступы, у следующих indentRest пробелов
    private void wrap(String text, byte[] st, int indentRest, int kind) {
        int len = text.length();
        if (len == 0) { emit("", null, kind); return; }
        int start = 0;
        boolean first = true;
        while (start < len) {
            int ind = first ? 0 : indentRest;
            int avail = maxW - ind * spaceW;
            if (avail < spaceW * 4) avail = spaceW * 4;
            int w = 0, end = start, lastSp = -1;
            boolean seen = false;
            while (end < len) {
                char c = text.charAt(end);
                int cwd = charW(font, c);
                if (w + cwd > avail && end > start) break;
                w += cwd;
                if (c == ' ') { if (seen) lastSp = end; } else seen = true;
                end++;
            }
            int brk;
            if (end >= len) brk = len;
            else if (text.charAt(end) == ' ') brk = end;
            else if (lastSp > start) brk = lastSp;
            else brk = end;
            int lineEnd = brk;
            while (lineEnd > start && text.charAt(lineEnd - 1) == ' ') lineEnd--;
            emitSlice(text, st, start, lineEnd, ind, kind);
            start = brk;
            while (start < len && text.charAt(start) == ' ') start++;
            first = false;
        }
    }
 
    // Блок кода: перенос по ширине без учёта слов, пробелы сохраняются
    private void wrapHard(String text) {
        int len = text.length();
        if (len == 0) { emit("", null, 1); return; }
        int start = 0;
        while (start < len) {
            int w = 0, end = start;
            while (end < len) {
                int cwd = charW(font, text.charAt(end));
                if (w + cwd > maxW && end > start) break;
                w += cwd;
                end++;
            }
            emit(text.substring(start, end), null, 1);
            start = end;
        }
    }
 
    private void emitSlice(String text, byte[] st, int a, int b, int indent, int kind) {
        String s;
        byte[] sty = null;
        if (indent > 0) {
            StringBuffer sb = new StringBuffer(indent + b - a);
            for (int i = 0; i < indent; i++) sb.append(' ');
            sb.append(text.substring(a, b));
            s = sb.toString();
        } else {
            s = text.substring(a, b);
        }
        if (st != null) {
            boolean any = false;
            for (int i = a; i < b && i < st.length; i++) if (st[i] != 0) { any = true; break; }
            if (any) {
                sty = new byte[s.length()];
                for (int i = a; i < b && i < st.length; i++) sty[indent + (i - a)] = st[i];
            }
        }
        emit(s, sty, kind);
    }
 
    // ===== Разбор строчной разметки: **жирный**, *курсив*, `код`, [ссылка](url) =====
    private byte[] inlineStyles = null;
    private static final String ESC = "\\`*_{}[]()#+-.!|~>$";
 
    private static boolean isWord(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c > 127;
    }
 
    private String parseInline(String s, int base) {
        int len = s.length();
        StringBuffer sb = new StringBuffer(len);
        byte[] st = new byte[len + 4];
        boolean bold = false, ital = false, code = false;
        int i = 0;
        while (i < len) {
            char c = s.charAt(i);
            int cur = base | (bold ? S_BOLD : 0) | (ital ? S_ITAL : 0);
            if (code) {
                if (c == '`') { code = false; i++; continue; }
                st[sb.length()] = (byte) (base | S_CODE);
                sb.append(c); i++;
                continue;
            }
            if (c == '\\' && i + 1 < len && ESC.indexOf(s.charAt(i + 1)) != -1) {
                st[sb.length()] = (byte) cur;
                sb.append(s.charAt(i + 1)); i += 2;
                continue;
            }
            if (c == '`' && s.indexOf('`', i + 1) != -1) { code = true; i++; continue; }
            if (c == '*' || c == '_') {
                int n = 1;
                while (i + n < len && s.charAt(i + n) == c) n++;
                if (n <= 3) {
                    boolean intra = (c == '_') && i > 0 && isWord(s.charAt(i - 1)) && i + n < len && isWord(s.charAt(i + n));
                    if (!intra) {
                        boolean closing = (n == 1) ? ital : ((n == 2) ? bold : (bold && ital));
                        boolean ok;
                        if (closing) {
                            ok = i > 0 && s.charAt(i - 1) != ' ';
                        } else {
                            StringBuffer run = new StringBuffer();
                            for (int k = 0; k < n; k++) run.append(c);
                            ok = i + n < len && s.charAt(i + n) != ' ' && s.indexOf(run.toString(), i + n) != -1;
                        }
                        if (ok) {
                            if (n == 1) ital = !ital;
                            else if (n == 2) bold = !bold;
                            else { bold = !closing; ital = !closing; }
                            i += n;
                            continue;
                        }
                    }
                }
                for (int k = 0; k < n; k++) { st[sb.length()] = (byte) cur; sb.append(c); }
                i += n;
                continue;
            }
            if (c == '~' && i + 1 < len && s.charAt(i + 1) == '~') { i += 2; continue; }
            if (c == '[' || (c == '!' && i + 1 < len && s.charAt(i + 1) == '[')) {
                boolean img = (c == '!');
                int open = img ? i + 1 : i;
                int j = s.indexOf("](", open + 1);
                int k = (j == -1) ? -1 : s.indexOf(')', j + 2);
                if (j != -1 && k != -1) {
                    if (img) { st[sb.length()] = (byte) (cur | S_LINK); sb.append('['); }
                    for (int q = open + 1; q < j; q++) { st[sb.length()] = (byte) (cur | S_LINK); sb.append(s.charAt(q)); }
                    if (img) { st[sb.length()] = (byte) (cur | S_LINK); sb.append(']'); }
                    i = k + 1;
                    continue;
                }
            }
            st[sb.length()] = (byte) cur;
            sb.append(c);
            i++;
        }
        String plain = sb.toString();
        boolean any = false;
        for (int q = 0; q < plain.length(); q++) if (st[q] != 0) { any = true; break; }
        if (any) {
            inlineStyles = new byte[plain.length()];
            for (int q = 0; q < plain.length(); q++) inlineStyles[q] = st[q];
        } else {
            inlineStyles = null;
        }
        return plain;
    }
}

private static class Scroller {
    private static final int SLOP = 6;           // сколько пикселей пальца считается касанием, а не скроллом
 
    private final Canvas canvas;
    private float pos = 0f;                      // текущее смещение в пикселях
    private float vel = 0f;                      // скорость инерции, px/мс
    private int max = 0;                         // максимальное смещение
    private boolean hasTarget = false;           // плавный доезд до цели (для клавиш)
    private float target = 0f;
    private int pendingJump = -1;                // отложенный прыжок: применится при следующем setMax
    private boolean pressed = false;
    private boolean moved = false;
    private boolean suppressTap = false;
    private int startY = 0;
    private int lastY = 0;
    private long lastT = 0;
    private boolean running = false;
 
    public Scroller(Canvas c) { canvas = c; }
 
    public synchronized int getPos() { return (int) (pos + 0.5f); }
 
    // Вызывать при каждой отрисовке: сообщает, насколько далеко можно прокрутить
    public synchronized void setMax(int m) {
        max = (m < 0) ? 0 : m;
        if (pendingJump >= 0) { pos = (float) pendingJump; pendingJump = -1; }
        pos = clamp(pos);
        if (hasTarget) target = clamp(target);
    }
 
    private float clamp(float v) {
        if (v < 0f) return 0f;
        if (v > (float) max) return (float) max;
        return v;
    }
 
    private void stopAnim() { vel = 0f; hasTarget = false; }
 
    // Мгновенно перейти на позицию p (применится при ближайшей отрисовке)
    public synchronized void jump(int p) { stopAnim(); pendingJump = (p < 0) ? 0 : p; }
 
    public synchronized void jumpToEnd() { stopAnim(); pendingJump = Integer.MAX_VALUE; }
 
    // Плавно доехать до позиции p
    public synchronized void glideTo(int p) {
        target = clamp((float) p);
        hasTarget = true;
        vel = 0f;
        start();
    }
 
    public synchronized void glideBy(int d) {
        float base = hasTarget ? target : pos;
        glideTo((int) (base + d));
    }
 
    // Плавно прокрутить так, чтобы участок [top, bottom] оказался в окне высотой viewH
    public synchronized void ensureVisible(int top, int bottom, int viewH) {
        float ref = hasTarget ? target : pos;
        if ((float) top < ref) glideTo(top);
        else if ((float) bottom > ref + viewH) glideTo(bottom - viewH);
    }
 
    public synchronized void press(int y) {
        suppressTap = running && (hasTarget || vel > 0.02f || vel < -0.02f);
        stopAnim();
        pressed = true;
        moved = false;
        startY = y;
        lastY = y;
        lastT = System.currentTimeMillis();
    }
 
    public synchronized void drag(int y) {
        if (!pressed) return;
        if (!moved) {
            int d = y - startY;
            if (d < 0) d = -d;
            if (d < SLOP) return;
            moved = true;
        }
        long now = System.currentTimeMillis();
        long dt = now - lastT;
        float delta = (float) (lastY - y);
        pos = clamp(pos + delta);
        if (dt > 0) {
            float v = delta / (float) dt;
            vel = vel * 0.4f + v * 0.6f;
            if (vel > 4f) vel = 4f;
            if (vel < -4f) vel = -4f;
        }
        lastY = y;
        lastT = now;
        canvas.repaint();
    }
 
    // Возвращает true, если это был скролл (или касание, остановившее инерцию), а не обычный тап
    public synchronized boolean release() {
        boolean wasScroll = moved || suppressTap || !pressed;
        if (pressed && moved) {
            long idle = System.currentTimeMillis() - lastT;
            if (idle > 120 || (vel < 0.03f && vel > -0.03f)) vel = 0f;
            else start();
        }
        pressed = false;
        moved = false;
        suppressTap = false;
        return wasScroll;
    }
 
    private void start() {
        if (running) return;
        running = true;
        new Thread(new Runnable() {
            public void run() { loop(); }
        }).start();
    }
 
    private void loop() {
        long last = System.currentTimeMillis();
        while (true) {
            try { Thread.sleep(25); } catch (InterruptedException e) {}
            long now = System.currentTimeMillis();
            long dt = now - last;
            last = now;
            if (dt > 100) dt = 100;
            boolean go;
            synchronized (this) {
                go = step(dt) && canvas.isShown();
                if (!go) { running = false; vel = 0f; hasTarget = false; }
            }
            canvas.repaint();
            if (!go) return;
        }
    }
 
    private boolean step(long dt) {
        int n = (int) (dt / 16);
        if (n < 1) n = 1;
        for (int i = 0; i < n; i++) {
            if (hasTarget) {
                pos += (target - pos) * 0.25f;
                float diff = target - pos;
                if (diff < 0f) diff = -diff;
                if (diff < 0.5f) { pos = target; hasTarget = false; return false; }
            } else if (vel != 0f) {
                pos += vel * 16f;
                vel *= 0.95f;
                if (pos <= 0f) { pos = 0f; vel = 0f; }
                else if (pos >= (float) max) { pos = (float) max; vel = 0f; }
                if (vel < 0.02f && vel > -0.02f) vel = 0f;
            } else {
                return false;
            }
        }
        return hasTarget || vel != 0f;
    }
}

public void resetDynamicStatusColors() {
    if (configCanvas != null) configCanvas.resetStatusColors();
}

public void saveProviderCache() {
    RecordStore rs = null;
    try { RecordStore.deleteRecordStore("OM_PROV_CACHE_" + selectedProvider); } catch (Exception e) {}
    try {
        rs = RecordStore.openRecordStore("OM_PROV_CACHE_" + selectedProvider, true);
        StringBuffer sb = new StringBuffer(selectedModel).append("\n");
        for (int i = 0; i < availableModels.size(); i++) {
            sb.append((String) availableModels.elementAt(i)).append("\n");
        }
        byte[] bytes = sb.toString().getBytes("UTF-8");
        rs.addRecord(bytes, 0, bytes.length);
    } catch (Exception e) {}
    finally { if (rs != null) try { rs.closeRecordStore(); } catch (Exception e) {} }
}

public void loadProviderCache(int providerId) {
    RecordStore rs = null;
    availableModels = new Vector();
    try {
        rs = RecordStore.openRecordStore("OM_PROV_CACHE_" + providerId, false);
        if (rs.getNumRecords() > 0) {
            String data = new String(rs.getRecord(1), "UTF-8");
            int idx = 0;
            int nextIdx = data.indexOf('\n', idx);
            if (nextIdx != -1) {
                selectedModel = data.substring(idx, nextIdx).trim();
                idx = nextIdx + 1;
                while (idx < data.length()) {
                    nextIdx = data.indexOf('\n', idx);
                    if (nextIdx == -1) nextIdx = data.length();
                    String m = data.substring(idx, nextIdx).trim();
                    if (m.length() > 0 && !availableModels.contains(m)) {
                        availableModels.addElement(m);
                    }
                    idx = nextIdx + 1;
                }
            }
        }
    } catch (Exception e) {}
    finally { if (rs != null) try { rs.closeRecordStore(); } catch (Exception e) {} }

    if (availableModels.size() == 0) {
        if (providerId == 1) {
            availableModels.addElement("gpt-4o-mini");
            selectedModel = "gpt-4o-mini";
        } else if (providerId == 3) {
            availableModels.addElement("llama-3.3-70b-versatile");
            selectedModel = "llama-3.3-70b-versatile";
        } else if (providerId == 4) {
            availableModels.addElement("deepseek/deepseek-chat:free");
            selectedModel = "deepseek/deepseek-chat:free";
        } else {
            availableModels.addElement("default-model");
            selectedModel = "default-model";
        }
    }
}

public void applyPresetTheme(int preset) {
    isCustomTheme = false;
    if (preset == 0) {
        currentThemeName = "[TERMINAL]";
        colorBg = 0x000000; colorMain = 0x00FF00; colorHeaderBg = 0x003200;
        colorFocusBg = 0x007800; colorWarn = 0xFFFF00; colorError = 0xFF0000; colorSoftbarBg = 0x002800;
    } else if (preset == 1) {
        currentThemeName = "[CMD]";
        colorBg = 0x000000; colorMain = 0xFFFFFF; colorHeaderBg = 0x222222;
        colorFocusBg = 0x444444; colorWarn = 0xFFFFFF; colorError = 0xFFFFFF; colorSoftbarBg = 0x222222;
    } else if (preset == 2) {
        currentThemeName = "[LCD]";
        colorBg = 0x000088; colorMain = 0xFFFFFF; colorHeaderBg = 0x0000AA;
        colorFocusBg = 0x0000FF; colorWarn = 0xFFFFFF; colorError = 0xFFFFFF; colorSoftbarBg = 0x0000AA;
    }
    resetDynamicStatusColors();
    saveTheme(currentThemeName);
}

public void saveTheme(String name) {
    currentThemeName = name;
    isCustomTheme = !isPresetName(name);
    RecordStore rs = null;

    // Пресеты в RMS не пишем: они встроенные. Сохраняем только кастомные темы.
    if (isCustomTheme) {
        try { RecordStore.deleteRecordStore("OM_THEME_" + name); } catch (Exception e) {}
        try {
            rs = RecordStore.openRecordStore("OM_THEME_" + name, true);
            String data = colorBg + "\n" + colorMain + "\n" + colorHeaderBg + "\n" +
                          colorFocusBg + "\n" + colorWarn + "\n" + colorError + "\n" + colorSoftbarBg;
            byte[] bytes = data.getBytes("UTF-8");
            rs.addRecord(bytes, 0, bytes.length);
        } catch (Exception e) {}
        finally { if (rs != null) try { rs.closeRecordStore(); } catch (Exception e) {} }
    }

    try { RecordStore.deleteRecordStore("OmegaActiveTheme"); } catch (Exception e) {}
    try {
        rs = RecordStore.openRecordStore("OmegaActiveTheme", true);
        byte[] bytes = name.getBytes("UTF-8");
        rs.addRecord(bytes, 0, bytes.length);
    } catch (Exception e) {}
    finally { if (rs != null) try { rs.closeRecordStore(); } catch (Exception e) {} }
}

private void loadTheme() {
    RecordStore rs = null;

    // Чистим старые «просочившиеся» сторы пресетов
    try { RecordStore.deleteRecordStore("OM_THEME_[TERMINAL]"); } catch (Exception e) {}
    try { RecordStore.deleteRecordStore("OM_THEME_[CMD]"); } catch (Exception e) {}
    try { RecordStore.deleteRecordStore("OM_THEME_[LCD]"); } catch (Exception e) {}

    try {
        rs = RecordStore.openRecordStore("OmegaActiveTheme", false);
        if (rs.getNumRecords() > 0) {
            currentThemeName = new String(rs.getRecord(1), "UTF-8").trim();
        }
        rs.closeRecordStore();
    } catch (Exception e) {}
    rs = null;

    // Пресет берём из кода, а не из RMS
    if (isPresetName(currentThemeName)) {
        String t = currentThemeName.trim().toUpperCase();
        applyPresetTheme(t.equals("[CMD]") ? 1 : (t.equals("[LCD]") ? 2 : 0));
        return;
    }

    try {
        rs = RecordStore.openRecordStore("OM_THEME_" + currentThemeName, false);
        if (rs.getNumRecords() > 0) {
            String data = new String(rs.getRecord(1), "UTF-8");
            int idx = 0;
            int[] c = new int[7];
            for (int i = 0; i < 7; i++) {
                int nextIdx = data.indexOf('\n', idx);
                if (nextIdx == -1) nextIdx = data.length();
                c[i] = Integer.parseInt(data.substring(idx, nextIdx).trim());
                idx = nextIdx + 1;
            }
            colorBg = c[0]; colorMain = c[1]; colorHeaderBg = c[2];
            colorFocusBg = c[3]; colorWarn = c[4]; colorError = c[5]; colorSoftbarBg = c[6];
            isCustomTheme = true;
        }
    } catch (Exception e) {
        applyPresetTheme(0);
    }
    finally { if (rs != null) try { rs.closeRecordStore(); } catch (Exception e) {} }
}

public void saveSettings() {
    RecordStore rs = null;
    try { RecordStore.deleteRecordStore("OmegaPrefs"); } catch (Exception e) {}
    try {
        rs = RecordStore.openRecordStore("OmegaPrefs", true);
        String data = selectedProvider + "\n" + selectedModel + "\n" + apiKey + "\n" + currentChat + "\n" + proxyUrl + "\n" + systemPrompt + "\n" + proxyMode;
        byte[] bytes = data.getBytes("UTF-8");
        rs.addRecord(bytes, 0, bytes.length);
    } catch (Exception e) {}
    finally { if (rs != null) try { rs.closeRecordStore(); } catch (Exception e) {} }
}

private boolean loadSettings() {
    RecordStore rs = null;
    try {
        rs = RecordStore.openRecordStore("OmegaPrefs", false);
        if (rs.getNumRecords() > 0) {
            byte[] bytes = rs.getRecord(1);
            String data = new String(bytes, "UTF-8");
            int idx1 = data.indexOf('\n');
            if (idx1 != -1) {
                int idx2 = data.indexOf('\n', idx1 + 1);
                int idx3 = (idx2 != -1) ? data.indexOf('\n', idx2 + 1) : -1;
                int idx4 = (idx3 != -1) ? data.indexOf('\n', idx3 + 1) : -1;
                int idx5 = (idx4 != -1) ? data.indexOf('\n', idx4 + 1) : -1;
                int idx6 = (idx5 != -1) ? data.indexOf('\n', idx5 + 1) : -1;
                
                if (idx2 != -1) {
                    try { selectedProvider = Integer.parseInt(data.substring(0, idx1).trim()); } catch (Exception ex) {}
                    selectedModel = data.substring(idx1 + 1, idx2);
                    if (idx3 != -1) {
                        apiKey = data.substring(idx2 + 1, idx3);
                        if (idx4 != -1) {
                            currentChat = data.substring(idx3 + 1, idx4);
                            if (idx5 != -1) {
                                proxyUrl = data.substring(idx4 + 1, idx5);
                                if (idx6 != -1) {
                                    systemPrompt = data.substring(idx5 + 1, idx6);
                                    try { proxyMode = Integer.parseInt(data.substring(idx6 + 1).trim()); } catch (Exception ex) {}
                                } else {
                                    systemPrompt = data.substring(idx5 + 1);
                                }
                            } else {
                                proxyUrl = data.substring(idx4 + 1);
                            }
                        } else {
                            currentChat = data.substring(idx3 + 1);
                        }
                    } else {
                        apiKey = data.substring(idx2 + 1);
                    }
                }
            }
            return true;
        }
    } catch (Exception e) {}
    finally { if (rs != null) try { rs.closeRecordStore(); } catch (Exception e) {} }
    return false;
}

public static String loadChatFromRms(String chatName) {
    if (chatName == null || chatName.equals("temp_new_chat")) return "[]";
    RecordStore rs = null;
    try {
        rs = RecordStore.openRecordStore("OM_CHAT_" + chatName, false);
        if (rs.getNumRecords() > 0) return fixControlChars(new String(rs.getRecord(1), "UTF-8"));
    } catch (Exception e) {}
    finally { if (rs != null) try { rs.closeRecordStore(); } catch (Exception e) {} }
    return "[]";
}

public static void saveChatToRms(String chatName, String jsonContent) {
    if (chatName == null || chatName.equals("temp_new_chat")) return;
    RecordStore rs = null;
    try { RecordStore.deleteRecordStore("OM_CHAT_" + chatName); } catch (Exception e) {}
    try {
        rs = RecordStore.openRecordStore("OM_CHAT_" + chatName, true);
        byte[] data = jsonContent.getBytes("UTF-8");
        rs.addRecord(data, 0, data.length);
    } catch (Exception e) {}
    finally { if (rs != null) try { rs.closeRecordStore(); } catch (Exception e) {} }
}

public static int mixColor(int a, int b, int pctA) {
    int r = (((a >> 16) & 0xFF) * pctA + ((b >> 16) & 0xFF) * (100 - pctA)) / 100;
    int g = (((a >> 8) & 0xFF) * pctA + ((b >> 8) & 0xFF) * (100 - pctA)) / 100;
    int bl = ((a & 0xFF) * pctA + (b & 0xFF) * (100 - pctA)) / 100;
    return (r << 16) | (g << 8) | bl;
}
 
// Имя чата из текста запроса: только допустимые символы, не длиннее maxLen
public static String sanitizeChatName(String raw, int maxLen, String fallback) {
    String base = (raw == null) ? "" : raw.trim();
    if (base.length() > maxLen) base = base.substring(0, maxLen).trim();
    StringBuffer sb = new StringBuffer();
    for (int i = 0; i < base.length(); i++) {
        char ch = base.charAt(i);
        boolean ok = (ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z') || (ch >= '0' && ch <= '9') || (ch >= 0x0410 && ch <= 0x044F) || ch == 0x0401 || ch == 0x0451;
        if (ok || ch == ' ' || ch == '_' || ch == '-') sb.append(ch); else sb.append('_');
    }
    String res = sb.toString().trim();
    if (res.length() == 0) res = fallback;
    return res;
}
 
public static boolean chatExists(String name) {
    if (name == null || name.equals("temp_new_chat")) return true;
    String[] stores = RecordStore.listRecordStores();
    if (stores == null) return false;
    String key = "OM_CHAT_" + name;
    for (int i = 0; i < stores.length; i++) {
        if (key.equals(stores[i])) return true;
    }
    return false;
}
 
// Если чат с таким именем уже есть, добавляет номер: "привет", "привет_2", "привет_3"...
public static String uniqueChatName(String base) {
    if (!chatExists(base)) return base;
    for (int n = 2; n < 1000; n++) {
        String cand = base + "_" + n;
        if (!chatExists(cand)) return cand;
    }
    return base + "_" + (System.currentTimeMillis() % 100000);
}
 
// Подпись автора ответа в чате: имя модели без префикса провайдера, не длиннее 12 символов
public static String modelLabel(String model) {
    if (model == null || model.trim().length() == 0) return "AI";
    String m = model.trim();
    int sl = m.lastIndexOf('/');
    if (sl != -1 && sl < m.length() - 1) m = m.substring(sl + 1);
    if (m.length() > 12) m = m.substring(0, 12);
    return m;
}
 
// Убирает из истории служебное поле "model": провайдеры могут отвергнуть лишние поля в сообщениях
public static String stripModelKeys(String s) {
    String key = "\"model\":\"";
    if (s == null || s.indexOf(key) == -1) return s;
    StringBuffer sb = new StringBuffer(s.length());
    int pos = 0;
    while (true) {
        int i = s.indexOf(key, pos);
        if (i == -1) break;
        sb.append(s.substring(pos, i));
        int e = i + key.length();
        while (e < s.length()) {
            char ch = s.charAt(e);
            if (ch == '\\') { e += 2; continue; }
            if (ch == '"') break;
            e++;
        }
        e++;
        if (e < s.length() && s.charAt(e) == ',') e++;
        else if (sb.length() > 0 && sb.charAt(sb.length() - 1) == ',') sb.setLength(sb.length() - 1);
        pos = e;
    }
    if (pos < s.length()) sb.append(s.substring(pos));
    return sb.toString();
}
 
public static void appendToChatRms(String chatName, String userMsg, String aiResp) {
    appendToChatRms(chatName, userMsg, aiResp, null);
}
 
// model запоминается вместе с ответом: при смене модели старые сообщения сохраняют своё имя
public static void appendToChatRms(String chatName, String userMsg, String aiResp, String model) {
    if (chatName == null || chatName.equals("temp_new_chat")) return;
    String existing = loadChatFromRms(chatName).trim();
    if (existing.length() == 0 || !existing.startsWith("[")) existing = "[]";
    if (existing.endsWith("]")) existing = existing.substring(0, existing.length() - 1);
    StringBuffer sb = new StringBuffer(existing);
    if (sb.length() > 1) sb.append(",");
    sb.append("{\"role\":\"user\",\"content\":\"").append(escapeStatic(userMsg)).append("\"},");
    sb.append("{\"role\":\"assistant\",");
    if (model != null && model.length() > 0) sb.append("\"model\":\"").append(escapeStatic(model)).append("\",");
    sb.append("\"content\":\"").append(escapeStatic(aiResp)).append("\"}]");
    saveChatToRms(chatName, sb.toString());
}
 

private static String escapeStatic(String s) {
    StringBuffer sb = new StringBuffer();
    for (int i = 0; i < s.length(); i++) {
        char ch = s.charAt(i);
        if (ch == '"') sb.append("\\\"");
        else if (ch == '\\') sb.append("\\\\");
        else if (ch == '\n') sb.append("\\n");
        else if (ch == '\r') sb.append("\\r");
        else if (ch == '\t') sb.append("\\t");
        else if (ch < 0x20) {
            String hex = Integer.toHexString(ch);
            sb.append("\\u");
            for (int k = hex.length(); k < 4; k++) sb.append('0');
            sb.append(hex);
        }
        else sb.append(ch);
    }
    return sb.toString();
}
 
// Лечит старые чаты: «сырые» табы и прочие управляющие символы внутри строк превращает в экранированные
private static String fixControlChars(String s) {
    StringBuffer sb = null;
    for (int i = 0; i < s.length(); i++) {
        char ch = s.charAt(i);
        if (ch < 0x20) {
            if (sb == null) { sb = new StringBuffer(s.length() + 16); sb.append(s.substring(0, i)); }
            if (ch == '\t') sb.append("\\t");
            else if (ch == '\n') sb.append("\\n");
            else if (ch == '\r') sb.append("\\r");
            else {
                String hex = Integer.toHexString(ch);
                sb.append("\\u");
                for (int k = hex.length(); k < 4; k++) sb.append('0');
                sb.append(hex);
            }
        } else if (sb != null) {
            sb.append(ch);
        }
    }
    return (sb == null) ? s : sb.toString();
}

public String escapeUrl(String s) {
    if (s == null) return "";
    byte[] b;
    try { b = s.getBytes("UTF-8"); } catch (Exception e) { b = s.getBytes(); }
    StringBuffer sb = new StringBuffer();
    for (int i = 0; i < b.length; i++) {
        int v = b[i] & 0xFF;
        char c = (char) v;
        if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') ||
            c == '-' || c == '_' || c == '.' || c == '~') {
            sb.append(c);
        } else {
            sb.append('%');
            if (v < 16) sb.append('0');
            sb.append(Integer.toHexString(v).toUpperCase());
        }
    }
    return sb.toString();
}

public static boolean isPresetName(String n) {
    if (n == null) return false;
    String t = n.trim().toUpperCase();
    return t.equals("[TERMINAL]") || t.equals("[CMD]") || t.equals("[LCD]");
}

// Возвращает цвет c, если он читается на фоне against. Иначе подбирает запасной.
public int visibleColor(int c, int against) {
    int[] cand = { c, colorBg, 0xFFFFFF, 0x000000 };
    for (int i = 0; i < cand.length; i++) {
        if (colorDistance(cand[i], against) >= 150) return cand[i];
    }
    return c;
}

private static int colorDistance(int a, int b) {
    return Math.abs(((a >> 16) & 0xFF) - ((b >> 16) & 0xFF))
         + Math.abs(((a >> 8) & 0xFF) - ((b >> 8) & 0xFF))
         + Math.abs((a & 0xFF) - (b & 0xFF));
}

private class CustomSelectCanvas extends Canvas {
    private Omega midlet;
    private String title = "";
    private Vector items = new Vector();
    private int selectedIdx = 0;
    private int scrollOffset = 0;
    private SelectCallback callback;
    private Runnable cancelCallback;

    private boolean isChatMode = false;
    private Runnable chatDoneCallback;
    private int deleteState = 0;
    private int deletingIndex = -1;
    private String currentConfirmQuote = "";
    private String currentDeletedNotice = "";
    private long deleteTimer = 0;

    private int touchStartY = 0;
    private int touchLastY = 0;
    private boolean isDragging = false;

    private final Scroller scroller = new Scroller(this);
    private boolean pressInBar = false;

    private Vector remoteChats = new Vector();   // чаты, найденные на сервере

    private final String[] CONFIRM_QUOTES = {
        "r u sure?", "completely sure?", "please no!", "i dont wanna die!",
        "let me be!", "maybe you let me be? >_<", "what i did to you?",
        "i was that bad?", "no, please! i'll be good!", "TWT", "damn.",
        "is this the end?", "think about our memories...", "we had a good run...",
        "why are you doing this?", "i served you well...", "i'll miss you...",
        "i'm too young to be garbage collected!", "error 404: mercy not found?"
    };

    private final String[] DELETED_QUOTES = {
        "was deleted.", "was destroyed.", "was obliterated.",
        "you killed it.", "wiped off the face of the earth.",
        "is no more. rust in peace.", "sent to the digital shadow realm."
    };

    public CustomSelectCanvas(Omega m) {
        midlet = m;
        setFullScreenMode(true);
    }

    public void open(String title, Vector items, int initialSel, SelectCallback callback, Runnable cancelCallback) {
        this.isChatMode = false;
        this.title = title;
        this.items = items;
        this.selectedIdx = (initialSel >= 0 && initialSel < items.size()) ? initialSel : 0;
        this.callback = callback;
        this.cancelCallback = cancelCallback;
        currentDeletedNotice = "";
        deleteState = 0;
        deletingIndex = -1;
        scroller.jump(Math.max(0, this.selectedIdx - 3) * rowH());
        repaint();
    }

    public void openChatSelector(Runnable onDone) {
        this.isChatMode = true;
        this.title = "Select Chat";
        this.chatDoneCallback = onDone;
        // Экран выбора общий для всех меню: сбрасываем обработчики прошлого меню (например, проводника),
        // иначе [BACK] выполнит чужое действие
        this.callback = null;
        this.cancelCallback = null;
        this.deleteState = 0;
        this.deletingIndex = -1;
        this.currentConfirmQuote = "";
        this.currentDeletedNotice = "";
        this.remoteChats = new Vector();
 
        refreshChatList();
        repaint();
        fetchRemoteChats();
    }

    // Локальные чаты плюс чаты, которые есть только на сервере
    private Vector buildChatItems() {
        Vector v = new Vector();
        v.addElement("[+ NEW CHAT]");
        String[] stores = RecordStore.listRecordStores();
        if (stores != null) {
            for (int i = 0; i < stores.length; i++) {
                if (stores[i].startsWith("OM_CHAT_")) {
                    v.addElement(stores[i].substring(8));
                }
            }
        }
        for (int i = 0; i < remoteChats.size(); i++) {
            String rn = (String) remoteChats.elementAt(i);
            boolean have = false;
            for (int k = 1; k < v.size(); k++) {
                if (rn.equals(v.elementAt(k))) { have = true; break; }
            }
            if (!have) v.addElement(rn);
        }
        return v;
    }
 
    private void refreshChatList() {
        items = buildChatItems();
 
        this.selectedIdx = 0;
        for (int i = 1; i < items.size(); i++) {
            if (items.elementAt(i).equals(midlet.currentChat)) {
                this.selectedIdx = i;
                break;
            }
        }
        scroller.jump(Math.max(0, this.selectedIdx - 3) * rowH());
    }
 
    // Когда пришёл список с сервера: дописываем новые чаты, выбранная строка остаётся на месте
    private void mergeRemoteChats() {
        String keep = null;
        if (selectedIdx >= 0 && selectedIdx < items.size()) keep = (String) items.elementAt(selectedIdx);
        items = buildChatItems();
        int idx = 0;
        if (keep != null) {
            for (int i = 0; i < items.size(); i++) {
                if (keep.equals(items.elementAt(i))) { idx = i; break; }
            }
        }
        selectedIdx = idx;
        ensureSelVisible();
    }

        // Удаление чата на сервере. Сначала обычный запрос, потом проверка по списку чатов: если чат всё ещё
    // на сервере (так бывает на телефонах с патчем виртуальной машины), пробуем другие способы по очереди
    private void deleteChatOnServer(String cName) {
        if (midlet.proxyUrl == null || midlet.proxyUrl.trim().length() == 0) return;
        String base = midlet.proxyUrl.trim();
        if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        for (int mode = 0; mode < 4; mode++) {
            sendDeleteRequest(base, cName, mode);
            int st = chatOnServer(base, cName);
            if (st != 1) return;               // удалён, или проверить не удалось: дальше не настаиваем
        }
    }
 
    // Один запрос на удаление. mode 0: GET как раньше; 1: GET только на чтение; 2: POST; 3: метод DELETE
    private void sendDeleteRequest(String base, String cName, int mode) {
        HttpConnection c = null;
        OutputStream os = null;
        InputStream is = null;
        try {
            String url = base + "/?type=delete&chat=" + midlet.escapeUrl(cName) + "&key=" + midlet.escapeUrl(midlet.apiKey);
            if (mode == 0) {
                c = (HttpConnection) Connector.open(url);
                c.setRequestMethod(HttpConnection.GET);
            } else if (mode == 1) {
                c = (HttpConnection) Connector.open(url, Connector.READ);
                c.setRequestMethod(HttpConnection.GET);
                c.setRequestProperty("User-Agent", "Omega-Symbian");
                c.setRequestProperty("Connection", "close");
            } else if (mode == 2) {
                c = (HttpConnection) Connector.open(url, Connector.READ_WRITE, true);
                c.setRequestMethod(HttpConnection.POST);
                c.setRequestProperty("Content-Type", "application/octet-stream");
                c.setRequestProperty("Content-Length", "1");
                c.setRequestProperty("Connection", "close");
                os = c.openOutputStream();
                os.write(48);
                os.flush();
                os.close();
                os = null;
            } else {
                c = (HttpConnection) Connector.open(url, Connector.READ_WRITE, true);
                c.setRequestMethod("DELETE");
                c.setRequestProperty("Connection", "close");
            }
            c.getResponseCode();
            is = c.openInputStream();
            while (is.read() != -1) { }        // дочитываем ответ до конца, чтобы запрос точно завершился
        } catch (Exception e) {
            // Не получилось этим способом: проверка ниже покажет, остался ли чат на сервере
        } finally {
            try { if (is != null) is.close(); } catch (Exception e) {}
            try { if (os != null) os.close(); } catch (Exception e) {}
            try { if (c != null) c.close(); } catch (Exception e) {}
        }
    }
 
    // Есть ли чат в списке на сервере: 1 да, 0 нет, -1 проверить не удалось
    private int chatOnServer(String base, String cName) {
        HttpConnection c = null;
        InputStream is = null;
        try {
            c = (HttpConnection) Connector.open(base + "/?type=list", Connector.READ);
            c.setRequestMethod(HttpConnection.GET);
            c.setRequestProperty("User-Agent", "Omega-Symbian");
            if (c.getResponseCode() != HttpConnection.HTTP_OK) return -1;
            is = c.openInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            int ch;
            while ((ch = is.read()) != -1) bos.write(ch);
            String body = new String(bos.toByteArray(), "UTF-8");
            if (body.trim().startsWith("<")) return -1;
            int p = 0;
            while (p <= body.length()) {
                int e = body.indexOf('\n', p);
                if (e == -1) e = body.length();
                if (body.substring(p, e).trim().equals(cName)) return 1;
                p = e + 1;
            }
            return 0;
        } catch (Exception e) {
            return -1;
        } finally {
            try { if (is != null) is.close(); } catch (Exception e) {}
            try { if (c != null) c.close(); } catch (Exception e) {}
        }
    }
 
    // Спрашивает у прокси, какие чаты у него есть (type=list), и добавляет их в список
    private void fetchRemoteChats() {
        if (midlet.proxyUrl == null || midlet.proxyUrl.trim().length() == 0) return;
        new Thread(new Runnable() {
            public void run() {
                HttpConnection c = null;
                InputStream is = null;
                try {
                    String base = midlet.proxyUrl.trim();
                    if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
                    c = (HttpConnection) Connector.open(base + "/?type=list", Connector.READ);
                    c.setRequestMethod(HttpConnection.GET);
                    c.setRequestProperty("User-Agent", "Omega-Symbian");
                    if (c.getResponseCode() == HttpConnection.HTTP_OK) {
                        is = c.openInputStream();
                        ByteArrayOutputStream bos = new ByteArrayOutputStream();
                        int ch;
                        while ((ch = is.read()) != -1) bos.write(ch);
                        String body = new String(bos.toByteArray(), "UTF-8");
 
                        final Vector found = new Vector();
                        int p = 0;
                        while (p <= body.length()) {
                            int e = body.indexOf('\n', p);
                            if (e == -1) e = body.length();
                            String name = body.substring(p, e).trim();
                            if (name.length() > 0 && !name.startsWith("<") && !name.equals("temp_new_chat")) {
                                found.addElement(name);
                            }
                            p = e + 1;
                        }
                        display.callSerially(new Runnable() {
                            public void run() {
                                if (!isChatMode) return;
                                remoteChats = found;
                                mergeRemoteChats();
                                repaint();
                            }
                        });
                    }
                } catch (Exception e) {
                    // Нет связи с прокси: остаются только локальные чаты
                } finally {
                    try { if (is != null) is.close(); } catch (Exception e) {}
                    try { if (c != null) c.close(); } catch (Exception e) {}
                }
            }
        }).start();
    }

    private int rowH() { return Omega.uiFont().getHeight() + 8; }
    private int viewTop() { return Omega.uiFont().getHeight() + 12; }
    private int noticeH() { return (currentDeletedNotice.length() > 0) ? Omega.uiFont().getHeight() + 8 : 0; }
    private int viewBottom() { return getHeight() - 35 - noticeH() - 2; }
 
    private void updateScrollBounds() {
        scroller.setMax(items.size() * rowH() - 2 - (viewBottom() - viewTop()));
    }
 
    private void ensureSelVisible() {
        updateScrollBounds();
        int top = selectedIdx * rowH();
        scroller.ensureVisible(top, top + rowH() - 2, viewBottom() - viewTop());
    }
 
    protected void paint(Graphics g) {
        int w = getWidth();
        int h = getHeight();
 
        g.setColor(midlet.colorBg);
        g.fillRect(0, 0, w, h);
 
        Font font = Omega.uiFont();
        g.setFont(font);
        int fh = font.getHeight() + 6;
 
        if (isChatMode && deleteState == 1 && System.currentTimeMillis() - deleteTimer > 3000) {
            deleteState = 0;
            deletingIndex = -1;
            currentConfirmQuote = "";
        }
 
        g.setColor(midlet.colorHeaderBg);
        g.fillRect(0, 0, w, fh + 4);
        g.setColor(midlet.colorMain);
        g.drawString("== " + title + " ==", 4, 2, Graphics.TOP | Graphics.LEFT);
 
        int startY = fh + 8;
        int bottomBarHeight = 35;
 
        int y = startY;
 
        if (isChatMode && items.size() == 1) {
            g.setColor(midlet.colorFocusBg);
            g.fillRect(2, y - 2, w - 4, fh);
            g.setColor(0xFFFFFF);
            g.drawString((String) items.elementAt(0), 6, y, Graphics.TOP | Graphics.LEFT);
            y += fh + 2;
 
            g.setColor(midlet.visibleColor(midlet.colorError, midlet.colorBg));
            g.drawString("[nothing to delete.]", 6, y, Graphics.TOP | Graphics.LEFT);
        } else {
            // Список рисуется со смещением в пикселях и обрезается по области между шапкой и софтбаром
            updateScrollBounds();
            int pos = scroller.getPos();
            int top = viewTop();
            int bottom = viewBottom();
            g.setClip(0, top, w, bottom - top);
 
            for (int i = 0; i < items.size(); i++) {
                y = startY + i * (fh + 2) - pos;
                if (y - 2 + fh < top || y - 2 > bottom) continue;
 
                boolean isSel = (i == selectedIdx);
                String itemText = (String) items.elementAt(i);
 
                if (isSel) {
                    g.setColor(midlet.colorFocusBg);
                    g.fillRect(2, y - 2, w - 4, fh);
                    g.setColor(0xFFFFFF);
                } else {
                    g.setColor(midlet.colorMain);
                }
 
                if (isChatMode && i > 0) {
                    if (deleteState == 1 && deletingIndex == i) {
                        String quoteStr = "[" + currentConfirmQuote + "]";
                        int quoteW = font.stringWidth(quoteStr);
                        int maxItemW = w - quoteW - 14;
 
                        String renderItemText = itemText;
                        while (renderItemText.length() > 1 && font.stringWidth(renderItemText) > maxItemW) {
                            renderItemText = renderItemText.substring(0, renderItemText.length() - 1);
                        }
                        if (!renderItemText.equals(itemText)) renderItemText += "...";
 
                        g.drawString(renderItemText, 6, y, Graphics.TOP | Graphics.LEFT);
                        // Цвет подтверждения подбирается так, чтобы он читался на фоне строки
                        int rowBg = isSel ? midlet.colorFocusBg : midlet.colorBg;
                        g.setColor(midlet.visibleColor(midlet.colorWarn, rowBg));
                        g.drawString(quoteStr, w - quoteW - 6, y, Graphics.TOP | Graphics.LEFT);
                    } else {
                        String btnStr = "[DEL]";
                        int btnW = font.stringWidth(btnStr) + 8;
                        int maxItemW = w - btnW - 14;
 
                        String renderItemText = itemText;
                        while (renderItemText.length() > 1 && font.stringWidth(renderItemText) > maxItemW) {
                            renderItemText = renderItemText.substring(0, renderItemText.length() - 1);
                        }
                        if (!renderItemText.equals(itemText)) renderItemText += "...";
 
                        g.drawString(renderItemText, 6, y, Graphics.TOP | Graphics.LEFT);
                        g.setColor(isSel ? 0xFFFFFF : midlet.colorMain);
                        g.drawString(btnStr, w - btnW + 4, y, Graphics.TOP | Graphics.LEFT);
                    }
                } else {
                    g.drawString(itemText, 6, y, Graphics.TOP | Graphics.LEFT);
                }
            }
            g.setClip(0, 0, w, h);
        }
 
        if (currentDeletedNotice.length() > 0) {
            String notice = currentDeletedNotice;
            int maxNoticeW = w - 12;
            while (notice.length() > 4 && font.stringWidth(notice) > maxNoticeW) {
                notice = notice.substring(0, notice.length() - 1);
            }
            if (!notice.equals(currentDeletedNotice)) {
                notice = notice.substring(0, notice.length() - 3) + "...";
            }
            g.setColor(midlet.visibleColor(midlet.colorError, midlet.colorBg));
            // Привязка к нижнему краю над софтбаром: не зависит от высоты шрифта
            g.drawString(notice, 6, h - bottomBarHeight - 4, Graphics.BOTTOM | Graphics.LEFT);
        }
 
        // SOFTBAR (Left: SELECT, Right: BACK - FIXED per user instructions)
        g.setColor(midlet.colorSoftbarBg);
        g.fillRect(0, h - bottomBarHeight, w, bottomBarHeight);
        g.setColor(midlet.colorMain);
        g.drawLine(0, h - bottomBarHeight, w, h - bottomBarHeight);
        g.drawString("[SELECT]", 10, h - 26, Graphics.TOP | Graphics.LEFT);
        String rightBtn = "[BACK]";
        g.drawString(rightBtn, w - font.stringWidth(rightBtn) - 10, h - 26, Graphics.TOP | Graphics.LEFT);
    }
 
    protected void keyPressed(int keyCode) {
        int ga = getGameAction(keyCode);
        currentDeletedNotice = "";
 
        if (ga == UP) {
            if (selectedIdx > 0) selectedIdx--;
            ensureSelVisible();
            repaint();
        } else if (ga == DOWN) {
            if (selectedIdx < items.size() - 1) selectedIdx++;
            ensureSelVisible();
            repaint();
        } else if (ga == FIRE || keyCode == 10 || keyCode == 13 || keyCode == -5 || keyCode == -6) {
            // Left softkey or Fire -> SELECT
            confirmSelection();
        } else if (keyCode == -7) {
            // Right softkey -> BACK
            cancelSelection();
        } else if (isChatMode && (keyCode == KEY_NUM0 || keyCode == '0')) {
            if (selectedIdx > 0 && selectedIdx < items.size()) {
                handleDeleteChat(selectedIdx);
            }
        }
    }
 
    protected void pointerPressed(int x, int y) {
        // Касание, начавшееся на софтбаре, скроллом не считается
        if (y > getHeight() - 35) { pressInBar = true; return; }
        pressInBar = false;
        scroller.press(y);
    }
 
    protected void pointerDragged(int x, int y) {
        if (pressInBar) return;
        scroller.drag(y);
    }
 
    protected void pointerReleased(int x, int y) {
        int w = getWidth();
        int h = getHeight();
        currentDeletedNotice = "";
        boolean inBar = y > h - 35;
 
        // Кнопки софтбара срабатывают, только если касание и началось, и закончилось на нём
        if (pressInBar) {
            pressInBar = false;
            if (inBar) {
                if (x < w / 2) confirmSelection(); // Left: SELECT
                else cancelSelection(); // Right: BACK
            }
            return;
        }
 
        // Скролл или остановка инерции - это не тап по строке
        if (scroller.release()) { repaint(); return; }
        if (inBar) return;
 
        int clickedRow = (y - viewTop() + scroller.getPos()) / rowH();
        if (y >= viewTop() && clickedRow >= 0 && clickedRow < items.size()) {
            if (isChatMode && clickedRow > 0 && x > w - 100) {
                handleDeleteChat(clickedRow);
                return;
            }
            selectedIdx = clickedRow;
            confirmSelection();
        }
    }

private void handleDeleteChat(final int index) {
    if (deleteState == 0 || deletingIndex != index) {
        deleteState = 1;
        deletingIndex = index;
        int qIdx = (int) (System.currentTimeMillis() % CONFIRM_QUOTES.length);
        currentConfirmQuote = CONFIRM_QUOTES[qIdx];
        final long stamp = System.currentTimeMillis();
        deleteTimer = stamp;
 
        // Через 3 секунды сами сбрасываем подтверждение и перерисовываем экран
        new Thread(new Runnable() {
            public void run() {
                try { Thread.sleep(3000); } catch (InterruptedException e) {}
                if (deleteState == 1 && deletingIndex == index && deleteTimer == stamp) {
                    deleteState = 0;
                    deletingIndex = -1;
                    currentConfirmQuote = "";
                    repaint();
                }
            }
        }).start();
    } else if (deleteState == 1 && deletingIndex == index) {
        final String cName = (String) items.elementAt(index);
 
        // Удаляем локально
        try { RecordStore.deleteRecordStore("OM_CHAT_" + cName); } catch (Exception e) {}
        remoteChats.removeElement(cName);
 
        // Удаляем с сервера асинхронно (с проверкой и запасными способами)
        new Thread(new Runnable() {
            public void run() { deleteChatOnServer(cName); }
        }).start();
 
        deleteState = 0;
        deletingIndex = -1;
        currentConfirmQuote = "";
 
        int dIdx = (int) (System.currentTimeMillis() % DELETED_QUOTES.length);
        currentDeletedNotice = "[" + cName + " " + DELETED_QUOTES[dIdx] + "]";
 
        refreshChatList();
        if (midlet.currentChat.equals(cName)) {
            midlet.currentChat = items.size() > 1 ? (String) items.elementAt(1) : "default_chat";
        }
    }
    repaint();
}

    private void confirmSelection() {
        if (isChatMode) {
            if (selectedIdx == 0) {
                midlet.currentChat = "temp_new_chat";
                midlet.switchToChat();
            } else if (selectedIdx > 0 && selectedIdx < items.size()) {
                midlet.currentChat = (String) items.elementAt(selectedIdx);
                if (chatDoneCallback != null) chatDoneCallback.run();
                else midlet.switchToConfig();
            }
        } else {
            if (selectedIdx >= 0 && selectedIdx < items.size() && callback != null) {
                callback.onSelected(selectedIdx, (String) items.elementAt(selectedIdx));
            }
        }
    }

    private void cancelSelection() {
        if (cancelCallback != null) cancelCallback.run();
        else midlet.switchToConfig();
    }
}

private class BootCanvas extends Canvas {
    private Omega midlet;
    private Image bootIcon = null;

    public BootCanvas(Omega m) {
        midlet = m;
        setFullScreenMode(true);
        try {
            Image rawIcon = Image.createImage("/icon.png");
            bootIcon = scaleImage(rawIcon, 114, 128);
        } catch (Exception e) {}
    }

    private Image scaleImage(Image src, int tw, int th) {
        int sw = src.getWidth();
        int sh = src.getHeight();
        int[] srgb = new int[sw * sh];
        src.getRGB(srgb, 0, sw, 0, 0, sw, sh);

        int[] trgb = new int[tw * th];
        for (int y = 0; y < th; y++) {
            for (int x = 0; x < tw; x++) {
                int sx = (x * sw) / tw;
                int sy = (y * sh) / th;
                trgb[y * tw + x] = srgb[sy * sw + sx];
            }
        }
        return Image.createRGBImage(trgb, tw, th, true);
    }

    protected void paint(Graphics g) {
        int w = getWidth();
        int h = getHeight();
        g.setColor(0x000000);
        g.fillRect(0, 0, w, h);
        if (bootIcon != null) {
            g.drawImage(bootIcon, (w - bootIcon.getWidth()) / 2, (h - bootIcon.getHeight()) / 2, Graphics.TOP | Graphics.LEFT);
        }
    }
}

private class ConfigCanvas extends Canvas {
    private Omega midlet;
    private int focusIndex = 0;
    private int scrollOffset = 0;
    private boolean isFetching = false;
    private String fetchBtnText = "[FETCH MODELS]";
    private int fetchStatusColor = -1;

    private int tickerPos = 0;
    private final int SPEED_DIVISOR = 4;
    private Thread tickerThread;
    private boolean running = true;

    private int touchStartY = 0;
    private int touchLastY = 0;
    private boolean isDragging = false;

    private final Scroller scroller = new Scroller(this);
    private boolean pressInBar = false;
    private final int ROW_COUNT = 11;

    public ConfigCanvas(Omega m) {
        midlet = m;
        setFullScreenMode(true);
        tickerThread = new Thread(new Runnable() {
            public void run() {
                while (running) {
                    try { Thread.sleep(50); } catch (Exception e) {}
                    if (isShown()) {
                        tickerPos++;
                        repaint();
                    }
                }
            }
        });
        tickerThread.start();
    }

    public void resetStatusColors() { fetchStatusColor = -1; }

    private int rowH() { return Omega.uiFont().getHeight() + 8; }
    private int viewTop() { return Omega.uiFont().getHeight() + 12; }
    private int viewBottom() { return getHeight() - 35 - 4; }
 
    private void updateScrollBounds() {
        scroller.setMax(ROW_COUNT * rowH() - 2 - (viewBottom() - viewTop()));
    }
 
    private void ensureFocusVisible() {
        updateScrollBounds();
        int top = focusIndex * rowH();
        scroller.ensureVisible(top, top + rowH() - 2, viewBottom() - viewTop());
    }
 
    protected void paint(Graphics g) {
        int w = getWidth();
        int h = getHeight();
 
        g.setColor(midlet.colorBg);
        g.fillRect(0, 0, w, h);
 
        Font font = Omega.uiFont();
        g.setFont(font);
        int fh = font.getHeight() + 6;
 
        g.setColor(midlet.colorHeaderBg);
        g.fillRect(0, 0, w, fh + 4);
        g.setColor(midlet.colorMain);
        g.drawString("== OMEGA // CONFIG ==", 4, 2, Graphics.TOP | Graphics.LEFT);
 
        int startY = fh + 8;
        int bottomBarHeight = 35;
 
        String maskedKey = midlet.apiKey.length() > 0 ? "********" : "EMPTY";
        String proxyModeStr = (midlet.proxyMode == 0) ? "DIRECT (NO PROXY)" : (midlet.proxyMode == 1 ? "FOR IMAGES ONLY" : "FOR EVERYTHING");
        String displayChatName = midlet.currentChat.equals("temp_new_chat") ? "[NEW CHAT]" : midlet.currentChat;
 
        String[] rowsText = {
            "[PROVIDER: " + PROVIDER_NAMES[midlet.selectedProvider] + "]",
            "[MODEL: " + midlet.selectedModel + "]",
            "[API KEY: " + maskedKey + "]",
            "[PROXY CONFIG: " + proxyModeStr + "]",
            "[CHAT: " + displayChatName + "]",
            "[SYS PROMPT: " + midlet.systemPrompt + "]",
            fetchBtnText,
            "[OPEN CHAT]",
            "[PARAMS]",
            "[THEMES]",
            "[ABOUT]"
        };
 
        tickerPos = (tickerPos + 1) % 1000;
 
        // Список рисуется со смещением в пикселях и обрезается по области между шапкой и софтбаром
        updateScrollBounds();
        int pos = scroller.getPos();
        int top = viewTop();
        int bottom = viewBottom();
        g.setClip(0, top, w, bottom - top);
        for (int i = 0; i < rowsText.length; i++) {
            int y = startY + i * (fh + 2) - pos;
            if (y - 2 + fh < top || y - 2 > bottom) continue;
            int customCol = midlet.colorMain;
            if (i == 6 && fetchStatusColor != -1) customCol = fetchStatusColor;
            drawRow(g, i, rowsText[i], y, fh, customCol, font, w);
        }
        g.setClip(0, 0, w, h);
 
        // SOFTBAR (Left: CHAT, Right: EXIT - FIXED per user instructions)
        g.setColor(midlet.colorSoftbarBg);
        g.fillRect(0, h - bottomBarHeight, w, bottomBarHeight);
        g.setColor(midlet.colorMain);
        g.drawLine(0, h - bottomBarHeight, w, h - bottomBarHeight);
        g.drawString("[CHAT]", 10, h - 26, Graphics.TOP | Graphics.LEFT);
        String rightBtn = "[EXIT]";
        g.drawString(rightBtn, w - font.stringWidth(rightBtn) - 10, h - 26, Graphics.TOP | Graphics.LEFT);
    }
 
    protected void keyPressed(int keyCode) {
        int ga = getGameAction(keyCode);
 
        if (ga == UP) {
            if (focusIndex > 0) focusIndex--;
            ensureFocusVisible();
            repaint();
        } else if (ga == DOWN) {
            if (focusIndex < ROW_COUNT - 1) focusIndex++;
            ensureFocusVisible();
            repaint();
        } else if (ga == FIRE || keyCode == 10 || keyCode == 13 || keyCode == -5 || keyCode == -6) {
            // Left softkey or Fire -> CHAT
            handleRowClick();
            repaint();
        } else if (keyCode == -7) {
            // Right softkey -> EXIT
            notifyDestroyed();
        }
    }
 
    protected void pointerPressed(int x, int y) {
        // Касание, начавшееся на софтбаре, скроллом не считается
        if (y > getHeight() - 35) { pressInBar = true; return; }
        pressInBar = false;
        scroller.press(y);
    }
 
    protected void pointerDragged(int x, int y) {
        if (pressInBar) return;
        scroller.drag(y);
    }
 
    protected void pointerReleased(int x, int y) {
        int w = getWidth();
        int h = getHeight();
        boolean inBar = y > h - 35;
 
        // Кнопки софтбара срабатывают, только если касание и началось, и закончилось на нём
        if (pressInBar) {
            pressInBar = false;
            if (inBar) {
                if (x < w / 2) midlet.switchToChat(); // Left: CHAT
                else notifyDestroyed(); // Right: EXIT
            }
            return;
        }
 
        // Скролл или остановка инерции - это не тап по строке
        if (scroller.release()) { repaint(); return; }
        if (inBar) return;
 
        int clickedRow = (y - viewTop() + scroller.getPos()) / rowH();
        if (y >= viewTop() && clickedRow >= 0 && clickedRow < ROW_COUNT) {
            focusIndex = clickedRow;
            handleRowClick();
            repaint();
        }
    }

    private void drawRow(Graphics g, int index, String text, int y, int fh, int customColor, Font font, int screenW) {
        boolean isFocused = (focusIndex == index);
        if (isFocused) {
            int fillCol = (customColor != midlet.colorMain) ? customColor : midlet.colorFocusBg;
            g.setColor(fillCol);
            g.fillRect(2, y - 2, screenW - 4, fh);
            g.setColor((fillCol == midlet.colorWarn) ? 0x000000 : 0xFFFFFF);
        } else {
            g.setColor(customColor);
        }

        String renderText = text;
        int textW = font.stringWidth(renderText);
        int availableW = screenW - 12;

        if (isFocused && textW > availableW) {
            int shift = (tickerPos / SPEED_DIVISOR) % (renderText.length() + 5);
            String padded = renderText + "     " + renderText;
            if (shift < padded.length()) renderText = padded.substring(shift);
        } else if (textW > availableW) {
            while (renderText.length() > 4 && font.stringWidth(renderText + "...") > availableW) {
                renderText = renderText.substring(0, renderText.length() - 1);
            }
            renderText = renderText + "...";
        }

        g.drawString(renderText, 6, y, Graphics.TOP | Graphics.LEFT);
    }


    private void handleRowClick() {
        if (focusIndex == 0) {
            Vector provs = new Vector();
            for (int i = 0; i < PROVIDER_NAMES.length; i++) provs.addElement(PROVIDER_NAMES[i]);
            midlet.openSelectionMenu("Select Provider", provs, midlet.selectedProvider, new SelectCallback() {
                public void onSelected(int index, String value) {
                    if (index != midlet.selectedProvider) {
                        midlet.saveProviderCache();
                        midlet.selectedProvider = index;
                        midlet.loadProviderCache(index);
                        midlet.saveSettings();
                    }
                    midlet.switchToConfig();
                }
            }, new Runnable() { public void run() { midlet.switchToConfig(); } });
        } else if (focusIndex == 1) {
            int initSel = 0;
            for (int i = 0; i < midlet.availableModels.size(); i++) {
                if (midlet.availableModels.elementAt(i).equals(midlet.selectedModel)) { initSel = i; break; }
            }
            midlet.openSelectionMenu("Select Model", midlet.availableModels, initSel, new SelectCallback() {
                public void onSelected(int index, String value) {
                    midlet.selectedModel = value;
                    midlet.saveProviderCache();
                    midlet.switchToConfig();
                }
            }, new Runnable() { public void run() { midlet.switchToConfig(); } });
        } else if (focusIndex == 2) {
            final TextBox tb = new TextBox("Enter API Key", midlet.apiKey, 256, TextField.ANY);
            tb.addCommand(new Command("OK", Command.OK, 1));
            tb.addCommand(new Command("Cancel", Command.CANCEL, 2));
            tb.setCommandListener(new CommandListener() {
                public void commandAction(Command c, Displayable d) {
                    if (c.getLabel().equals("OK")) midlet.apiKey = tb.getString().trim();
                    midlet.safeSetCurrent(ConfigCanvas.this);
                }
            });
            midlet.safeSetCurrent(tb);
        } else if (focusIndex == 3) {
            midlet.openProxyConfigMenu();
        } else if (focusIndex == 4) {
            midlet.openChatSelectionMenu(new Runnable() { public void run() { midlet.switchToConfig(); } });
        } else if (focusIndex == 5) {
            final TextBox tb = new TextBox("System Prompt", midlet.systemPrompt, 1024, TextField.ANY);
            tb.addCommand(new Command("OK", Command.OK, 1));
            tb.addCommand(new Command("Cancel", Command.CANCEL, 2));
            tb.setCommandListener(new CommandListener() {
                public void commandAction(Command c, Displayable d) {
                    if (c.getLabel().equals("OK")) midlet.systemPrompt = tb.getString().trim();
                    midlet.safeSetCurrent(ConfigCanvas.this);
                }
            });
            midlet.safeSetCurrent(tb);
        } else if (focusIndex == 6) {
            if (!isFetching) {
                if (midlet.apiKey == null || midlet.apiKey.trim().length() == 0) {
                    fetchBtnText = "[ERR: NO KEY]";
                    fetchStatusColor = midlet.colorError;
                    repaint();
                    return;
                }
                isFetching = true;
                fetchBtnText = "[FETCHING...]";
                fetchStatusColor = midlet.colorWarn;
                repaint();
                new Thread(new Runnable() { public void run() { fetchModelsTask(); } }).start();
            }
        } else if (focusIndex == 7) {
            midlet.switchToChat();
        } else if (focusIndex == 8) {
            midlet.openParamsMenu(0);
        } else if (focusIndex == 9) {
            midlet.switchToThemes();
        } else if (focusIndex == 10) {
            Form aboutForm = new Form("Omega v1.09");
            aboutForm.append(new StringItem("", "Universal J2ME AI Client.\n"));
            
            aboutForm.append(new StringItem("Shortcuts:", "- D-Pad Up / Down: Scroll\n- Fire: Full text input\n- Left Softkey: Chat / Select\n- Right Softkey: Exit / Back\n- Key '0': Delete chat\n"));

            aboutForm.append(new StringItem("\nChangelog:", "- Added Over-The-Air update from GitHub repo.\n"));

            aboutForm.append(new StringItem("", "\nwith love by bebrauwu <3"));
            Command backCmd = new Command("Back", Command.BACK, 1);
            aboutForm.addCommand(backCmd);
            aboutForm.setCommandListener(new CommandListener() {
                public void commandAction(Command c, Displayable d) { midlet.safeSetCurrent(ConfigCanvas.this); }
            });
            midlet.safeSetCurrent(aboutForm);
        }
    }

    private void fetchModelsTask() {
        HttpConnection c = null;
        InputStream is = null;
        try {
            String targetUrl = PROVIDER_URLS[midlet.selectedProvider];
            if (midlet.selectedProvider == 4) targetUrl += "?key=" + midlet.apiKey;

            boolean useProxyForModels = (midlet.proxyMode == 2 && midlet.proxyUrl != null && midlet.proxyUrl.trim().length() > 0);
            if (useProxyForModels) {
                String proxyBase = midlet.proxyUrl.trim();
                if (!proxyBase.endsWith("/")) proxyBase += "/";
                targetUrl = proxyBase + "?type=models&prov=" + midlet.selectedProvider + "&key=" + midlet.escapeUrl(midlet.apiKey);
            }

            c = (HttpConnection) Connector.open(targetUrl);
            c.setRequestMethod(HttpConnection.GET);
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36");
            c.setRequestProperty("Accept", "application/json, text/plain, */*");

            if (!useProxyForModels && midlet.selectedProvider != 4 && midlet.apiKey != null && midlet.apiKey.length() > 0) {
                c.setRequestProperty("Authorization", "Bearer " + midlet.apiKey);
            }

            int code = c.getResponseCode();
            if (code == HttpConnection.HTTP_OK) {
                is = c.openInputStream();
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                int ch;
                while ((ch = is.read()) != -1) bos.write(ch);
                Vector parsed = parseModelNames(new String(bos.toByteArray(), "UTF-8"));
                if (parsed.size() > 0) {
                    midlet.availableModels = parsed;
                    midlet.selectedModel = (String) parsed.elementAt(0);
                    midlet.saveProviderCache();
                    fetchBtnText = "[LOADED: " + parsed.size() + "]";
                    fetchStatusColor = midlet.colorMain;
                } else {
                    fetchBtnText = "[ERR: 0 MODELS]";
                    fetchStatusColor = midlet.colorError;
                }
            } else {
                fetchBtnText = "[ERR: HTTP " + code + "]";
                fetchStatusColor = midlet.colorError;
            }
        } catch (Exception e) {
            fetchBtnText = "[ERR: NET FAIL]";
            fetchStatusColor = midlet.colorError;
        } finally {
            try { if (is != null) is.close(); } catch (Exception e) {}
            try { if (c != null) c.close(); } catch (Exception e) {}
            isFetching = false;
            repaint();
        }
    }

    // Список моделей: берём только технические id, которые принимает провайдер (qwen/qwen3-27b).
    // Раньше в список попадали ещё и "name" - красивые названия вроде "Qwen: Qwen3 27B", с которыми запрос не работает
    private Vector parseModelNames(String json) {
        Vector models = new Vector();
        collectJsonStrings(json, "id", models);                   // VoidAI, OpenCode, Groq, OpenRouter
        if (models.size() == 0) collectJsonStrings(json, "name", models);   // Ollama: /api/tags отдаёт только name
        return models;
    }
 
    private void collectJsonStrings(String json, String key, Vector out) {
        String pat = "\"" + key + "\"";
        int idx = 0;
        int n = json.length();
        while (true) {
            int k = json.indexOf(pat, idx);
            if (k == -1) break;
            int p = k + pat.length();
            idx = p;
            while (p < n && json.charAt(p) <= ' ') p++;
            if (p >= n || json.charAt(p) != ':') continue;
            p++;
            while (p < n && json.charAt(p) <= ' ') p++;
            if (p >= n || json.charAt(p) != '"') continue;
            int end = json.indexOf('"', p + 1);
            if (end == -1) break;
            String m = json.substring(p + 1, end);
            idx = end;
            // Id не бывает с пробелами; служебные id вроде modelperm-... пропускаем
            if (m.length() == 0 || m.length() >= 100 || m.indexOf(' ') != -1 || m.startsWith("modelperm-")) continue;
            if (!out.contains(m)) out.addElement(m);
        }
    }
}
private class ThemeCanvas extends Canvas {
    private Omega midlet;
    private Vector items = new Vector();
    private int selectedIdx = 0;
    private int scrollOffset = 0;

    private int deleteState = 0;
    private int deletingIndex = -1;
    private String currentConfirmQuote = "";
    private String currentDeletedNotice = "";
    private long deleteTimer = 0;

    private boolean isEditingColor = false;
    private boolean showPaletteMenu = false;
    private int targetSlot = 0;
    private int activeHsvControl = 0;
    private int hue = 0;
    private int sat = 255;
    private int val = 255;

    private int touchStartY = 0;
    private int touchLastY = 0;
    private boolean isDragging = false;

    private final String[] COLOR_SLOT_NAMES = {"[BG COLOR]", "[MAIN COLOR]", "[WARNING COLOR]", "[ERROR COLOR]"};
    private final String[] CONFIRM_QUOTES = {"sure?"};
    private final String[] DELETED_QUOTES = {"was deleted.", "was destroyed.", "was obliterated."};

    private final Scroller scroller = new Scroller(this);
    private boolean pressInBar = false;

    public ThemeCanvas(Omega m) {
        midlet = m;
        setFullScreenMode(true);
        refreshThemeList();
    }

    private void refreshThemeList() {
        items = new Vector();
        items.addElement("[+ NEW THEME]");
        items.addElement("[TERMINAL]");
        items.addElement("[CMD]");
        items.addElement("[LCD]");
 
        String[] stores = RecordStore.listRecordStores();
        if (stores != null) {
            for (int i = 0; i < stores.length; i++) {
                if (stores[i].startsWith("OM_THEME_")) {
                    String tName = stores[i].substring(9);
                    if (!Omega.isPresetName(tName)) {
                        items.addElement(tName);
                    }
                }
            }
        }
 
        if (showPaletteMenu) {
            items.addElement("<- [BACK TO THEMES]");
            for (int i = 0; i < COLOR_SLOT_NAMES.length; i++) items.addElement(COLOR_SLOT_NAMES[i]);
        } else {
            items.addElement("[CUSTOM PALETTE: EDIT COLORS]");
            items.addElement("[RESET TO DEFAULT]");
        }
        selectedIdx = 0;
        scroller.jump(0);
    }

    private int rowH() { return Omega.uiFont().getHeight() + 8; }
    private int viewTop() { return Omega.uiFont().getHeight() + 12; }
    private int noticeH() { return (currentDeletedNotice.length() > 0) ? Omega.uiFont().getHeight() + 8 : 0; }
    private int viewBottom() { return getHeight() - 35 - noticeH() - 2; }
 
    private void updateScrollBounds() {
        scroller.setMax(items.size() * rowH() - 2 - (viewBottom() - viewTop()));
    }
 
    private void ensureSelVisible() {
        updateScrollBounds();
        int top = selectedIdx * rowH();
        scroller.ensureVisible(top, top + rowH() - 2, viewBottom() - viewTop());
    }
 
    protected void paint(Graphics g) {
        int w = getWidth();
        int h = getHeight();
        int bottomBarHeight = 35;
 
        g.setColor(midlet.colorBg);
        g.fillRect(0, 0, w, h);
 
        Font font = Omega.uiFont();
        g.setFont(font);
        int fh = font.getHeight() + 6;
 
        if (deleteState == 1 && System.currentTimeMillis() - deleteTimer > 3000) {
            deleteState = 0;
            deletingIndex = -1;
            currentConfirmQuote = "";
        }
 
        g.setColor(midlet.colorHeaderBg);
        g.fillRect(0, 0, w, fh + 4);
        g.setColor(midlet.colorMain);
 
        if (isEditingColor) {
            g.drawString("== ADJUST " + COLOR_SLOT_NAMES[targetSlot] + " ==", 4, 2, Graphics.TOP | Graphics.LEFT);
            drawColorWheel(g, w, h);
        } else {
            g.drawString("== OMEGA // THEMES ==", 4, 2, Graphics.TOP | Graphics.LEFT);
            int startY = fh + 8;
            int[] currentColors = {midlet.colorBg, midlet.colorMain, midlet.colorWarn, midlet.colorError};
 
            // Список рисуется со смещением в пикселях и обрезается по области между шапкой и софтбаром
            updateScrollBounds();
            int pos = scroller.getPos();
            int top = viewTop();
            int bottom = viewBottom();
            g.setClip(0, top, w, bottom - top);
 
            for (int i = 0; i < items.size(); i++) {
                int y = startY + i * (fh + 2) - pos;
                if (y - 2 + fh < top || y - 2 > bottom) continue;
 
                boolean isSel = (i == selectedIdx);
                String itemText = (String) items.elementAt(i);
 
                if (isSel) {
                    g.setColor(midlet.colorFocusBg);
                    g.fillRect(2, y - 2, w - 4, fh);
                    g.setColor(0xFFFFFF);
                } else {
                    g.setColor(midlet.colorMain);
                }
 
                boolean isCustom = !showPaletteMenu && i > 3 && i < items.size() - 2;
                if (isCustom) {
                    boolean confirming = (deleteState == 1 && deletingIndex == i);
                    String rightStr = confirming ? "[" + currentConfirmQuote + "]" : "[DEL]";
                    int rightW = font.stringWidth(rightStr) + (confirming ? 12 : 8);
                    int maxItemW = w - rightW - 8;
 
                    String renderItemText = itemText;
                    while (renderItemText.length() > 1 && font.stringWidth(renderItemText) > maxItemW) {
                        renderItemText = renderItemText.substring(0, renderItemText.length() - 1);
                    }
                    if (!renderItemText.equals(itemText)) renderItemText += "...";
 
                    g.drawString(renderItemText, 6, y, Graphics.TOP | Graphics.LEFT);
                    if (confirming) {
                        // Цвет подтверждения подбирается так, чтобы он читался на фоне строки
                        int rowBg = isSel ? midlet.colorFocusBg : midlet.colorBg;
                        g.setColor(midlet.visibleColor(midlet.colorWarn, rowBg));
                        g.drawString(rightStr, w - font.stringWidth(rightStr) - 6, y, Graphics.TOP | Graphics.LEFT);
                    } else {
                        g.setColor(isSel ? 0xFFFFFF : midlet.colorMain);
                        g.drawString(rightStr, w - rightW, y, Graphics.TOP | Graphics.LEFT);
                    }
                } else if (showPaletteMenu && i > items.size() - 5) {
                    int slotIdx = i - (items.size() - 4);
                    g.drawString(itemText, 6, y, Graphics.TOP | Graphics.LEFT);
                    g.setColor(currentColors[slotIdx]);
                    g.fillRect(w - 44, y, 32, fh - 4);
                    g.setColor(midlet.colorMain);
                    g.drawRect(w - 44, y, 32, fh - 4);
                } else {
                    g.drawString(itemText, 6, y, Graphics.TOP | Graphics.LEFT);
                }
            }
            g.setClip(0, 0, w, h);
 
            if (currentDeletedNotice.length() > 0) {
                String notice = currentDeletedNotice;
                int maxNoticeW = w - 12;
                while (notice.length() > 4 && font.stringWidth(notice) > maxNoticeW) {
                    notice = notice.substring(0, notice.length() - 1);
                }
                if (!notice.equals(currentDeletedNotice)) {
                    notice = notice.substring(0, notice.length() - 3) + "...";
                }
                g.setColor(midlet.visibleColor(midlet.colorError, midlet.colorBg));
                // Привязка к нижнему краю над софтбаром: не зависит от высоты шрифта
                g.drawString(notice, 6, h - bottomBarHeight - 4, Graphics.BOTTOM | Graphics.LEFT);
            }
        }
 
        // SOFTBAR (Left: SELECT, Right: BACK - FIXED)
        g.setColor(midlet.colorSoftbarBg);
        g.fillRect(0, h - bottomBarHeight, w, bottomBarHeight);
        g.setColor(midlet.colorMain);
        g.drawLine(0, h - bottomBarHeight, w, h - bottomBarHeight);
 
        String leftBtn = isEditingColor ? "[CANCEL]" : "[SELECT]";
        String rightBtn = isEditingColor ? "[OK]" : "[BACK]";
 
        g.drawString(leftBtn, 10, h - 26, Graphics.TOP | Graphics.LEFT);
        g.drawString(rightBtn, w - font.stringWidth(rightBtn) - 10, h - 26, Graphics.TOP | Graphics.LEFT);
    }

    private void drawColorWheel(Graphics g, int w, int h) {
        int cx = w / 2;
        int cy = h / 2 - 20;
        int radius = Math.min(w, h) / 3 - 5;
        if (radius < 10) radius = 40;

        for (int i = 0; i < 24; i++) {
            int angle = i * 15;
            g.setColor(hsvToRgb(angle, sat, val));
            int x1 = cx + (getCos(angle) * (radius - 12)) / 1000;
            int y1 = cy + (getSin(angle) * (radius - 12)) / 1000;
            int x2 = cx + (getCos(angle) * radius) / 1000;
            int y2 = cy + (getSin(angle) * radius) / 1000;
            g.drawLine(x1, y1, x2, y2);
        }

        int nearestAngle = ((hue + 7) / 15) * 15;
        if (nearestAngle >= 360) nearestAngle = 0;
        int kx = cx + (getCos(nearestAngle) * (radius - 6)) / 1000;
        int ky = cy + (getSin(nearestAngle) * (radius - 6)) / 1000;
        g.setColor(0xFFFFFF);
        g.fillRect(kx - 3, ky - 3, 7, 7);

        g.setColor(hsvToRgb(hue, sat, val));
        g.fillArc(cx - 14, cy - 14, 28, 28, 0, 360);
        g.setColor(0xFFFFFF);
        g.drawArc(cx - 14, cy - 14, 28, 28, 0, 360);

        int satY = h - 95;
        g.setColor(0x555555);
        g.fillRect(20, satY, w - 40, 8);
        g.setColor(activeHsvControl == 1 ? midlet.colorWarn : midlet.colorMain);
        g.drawRect(20, satY, w - 40, 8);
        int satX = 20 + ((w - 40) * sat) / 255;
        g.setColor(0xFFFFFF);
        g.fillRect(satX - 5, satY - 4, 10, 16);
        g.drawString("Sat: " + sat, 20, satY - 18, Graphics.TOP | Graphics.LEFT);

        int valY = h - 55;
        g.setColor(0x555555);
        g.fillRect(20, valY, w - 40, 8);
        g.setColor(activeHsvControl == 2 ? midlet.colorWarn : midlet.colorMain);
        g.drawRect(20, valY, w - 40, 8);
        int valX = 20 + ((w - 40) * val) / 255;
        g.setColor(0xFFFFFF);
        g.fillRect(valX - 5, valY - 4, 10, 16);
        g.drawString("Val: " + val, 20, valY - 18, Graphics.TOP | Graphics.LEFT);
    }

    protected void keyPressed(int keyCode) {
        int ga = getGameAction(keyCode);
        currentDeletedNotice = "";
 
        if (isEditingColor) {
            if (ga == UP) { if (activeHsvControl > 0) activeHsvControl--; repaint(); return; }
            else if (ga == DOWN) { if (activeHsvControl < 2) activeHsvControl++; repaint(); return; }
 
            if (activeHsvControl == 0) {
                if (ga == LEFT) hue = (hue - 15 + 360) % 360;
                else if (ga == RIGHT) hue = (hue + 15) % 360;
            } else if (activeHsvControl == 1) {
                if (ga == LEFT) sat = Math.max(0, sat - 15);
                else if (ga == RIGHT) sat = Math.min(255, sat + 15);
            } else if (activeHsvControl == 2) {
                if (ga == LEFT) val = Math.max(0, val - 15);
                else if (ga == RIGHT) val = Math.min(255, val + 15);
            }
 
            if (ga == FIRE || keyCode == 10 || keyCode == 13 || keyCode == -5 || keyCode == -7) {
                applyPickedColor();
                isEditingColor = false;
            } else if (keyCode == -6) {
                isEditingColor = false;
            }
            repaint();
            return;
        }
 
        if (ga == UP) {
            if (selectedIdx > 0) selectedIdx--;
            ensureSelVisible();
            repaint();
        } else if (ga == DOWN) {
            if (selectedIdx < items.size() - 1) selectedIdx++;
            ensureSelVisible();
            repaint();
        } else if (ga == FIRE || keyCode == 10 || keyCode == 13 || keyCode == -5 || keyCode == -6) {
            handleSelection();
        } else if (keyCode == -7) {
            if (showPaletteMenu) { showPaletteMenu = false; refreshThemeList(); repaint(); }
            else { midlet.switchToConfig(); }
        }
    }
 
    protected void pointerPressed(int x, int y) {
        currentDeletedNotice = "";
        // Касание, начавшееся на софтбаре, скроллом не считается
        if (y > getHeight() - 35) { pressInBar = true; return; }
        pressInBar = false;
        if (isEditingColor) { updateTouchColor(x, y); return; }
        scroller.press(y);
    }
 
    protected void pointerDragged(int x, int y) {
        if (pressInBar) return;
        if (isEditingColor) { updateTouchColor(x, y); return; }
        scroller.drag(y);
    }
 
    protected void pointerReleased(int x, int y) {
        int w = getWidth();
        int h = getHeight();
        currentDeletedNotice = "";
        boolean inBar = y > h - 35;
 
        // Кнопки софтбара срабатывают, только если касание и началось, и закончилось на нём
        if (pressInBar) {
            pressInBar = false;
            if (inBar) {
                if (x < w / 2) {
                    if (isEditingColor) isEditingColor = false;            // [CANCEL]
                    else handleSelection();                                // [SELECT]
                } else {
                    if (isEditingColor) { applyPickedColor(); isEditingColor = false; }   // [OK]
                    else if (showPaletteMenu) { showPaletteMenu = false; refreshThemeList(); }
                    else { midlet.switchToConfig(); }
                }
            }
            repaint();
            return;
        }
 
        if (isEditingColor) { repaint(); return; }
 
        // Скролл или остановка инерции - это не тап по строке
        if (scroller.release()) { repaint(); return; }
        if (inBar) return;
 
        int clickedRow = (y - viewTop() + scroller.getPos()) / rowH();
        if (y >= viewTop() && clickedRow >= 0 && clickedRow < items.size()) {
            boolean isCustom = !showPaletteMenu && clickedRow > 3 && clickedRow < items.size() - 2;
            if (isCustom && x > w - 70) { handleDeleteTheme(clickedRow); return; }
            selectedIdx = clickedRow;
            handleSelection();
        }
        repaint();
    }

    private void updateTouchColor(int x, int y) {
        int w = getWidth(); int h = getHeight(); int cx = w / 2; int cy = h / 2 - 20;
        if (y >= h - 110 && y <= h - 80) {
            activeHsvControl = 1; sat = Math.max(0, Math.min(255, ((x - 20) * 255) / (w - 40)));
        } else if (y >= h - 70 && y <= h - 40) {
            activeHsvControl = 2; val = Math.max(0, Math.min(255, ((x - 20) * 255) / (w - 40)));
        } else {
            activeHsvControl = 0;
            int radius = Math.min(w, h) / 3 - 5;
            if (radius < 10) radius = 40;
            int bestAngle = 0; int minDistSq = Integer.MAX_VALUE;
            for (int i = 0; i < 24; i++) {
                int angle = i * 15;
                int sx = cx + (getCos(angle) * (radius - 6)) / 1000;
                int sy = cy + (getSin(angle) * (radius - 6)) / 1000;
                int dx = x - sx; int dy = y - sy; int distSq = dx * dx + dy * dy;
                if (distSq < minDistSq) { minDistSq = distSq; bestAngle = angle; }
            }
            hue = bestAngle;
        }
        repaint();
    }

    private int getCos(int angle) {
        int a = (angle % 360 + 360) % 360;
        if (a == 0) return 1000; if (a == 30) return 866; if (a == 45) return 707; if (a == 60) return 500; if (a == 90) return 0;
        if (a == 120) return -500; if (a == 135) return -707; if (a == 150) return -866; if (a == 180) return -1000;
        if (a == 210) return -866; if (a == 225) return -707; if (a == 240) return -500; if (a == 270) return 0;
        if (a == 300) return 500; if (a == 315) return 707; if (a == 330) return 866;
        if (a == 15) return 965; if (a == 75) return 258; if (a == 105) return -258; if (a == 165) return -965;
        if (a == 195) return -965; if (a == 255) return -258; if (a == 285) return 258; if (a == 345) return 965;
        return 1000;
    }

    private int getSin(int angle) {
        int a = (angle % 360 + 360) % 360;
        if (a == 0) return 0; if (a == 15) return 258; if (a == 30) return 500; if (a == 45) return 707; if (a == 60) return 866;
        if (a == 75) return 965; if (a == 90) return 1000; if (a == 105) return 965; if (a == 120) return 866; if (a == 135) return 707;
        if (a == 150) return 500; if (a == 165) return 258; if (a == 180) return 0; if (a == 210) return -500; if (a == 225) return -707;
        if (a == 240) return -866; if (a == 270) return -1000; if (a == 300) return -866; if (a == 315) return -707; if (a == 330) return -500;
        if (a == 195) return -258; if (a == 255) return -965; if (a == 285) return -965; if (a == 345) return -258;
        return 0;
    }

    private void handleDeleteTheme(final int index) {
        if (deleteState == 0 || deletingIndex != index) {
            deleteState = 1;
            deletingIndex = index;
            currentConfirmQuote = CONFIRM_QUOTES[(int)(System.currentTimeMillis() % CONFIRM_QUOTES.length)];
            final long stamp = System.currentTimeMillis();
            deleteTimer = stamp;

            // Через 3 секунды сами сбрасываем подтверждение и перерисовываем экран
            new Thread(new Runnable() {
                public void run() {
                    try { Thread.sleep(3000); } catch (InterruptedException e) {}
                    if (deleteState == 1 && deletingIndex == index && deleteTimer == stamp) {
                        deleteState = 0;
                        deletingIndex = -1;
                        currentConfirmQuote = "";
                        repaint();
                    }
                }
            }).start();
        } else if (deleteState == 1 && deletingIndex == index) {
            String tName = (String) items.elementAt(index);
            try { RecordStore.deleteRecordStore("OM_THEME_" + tName); } catch (Exception e) {}
            deleteState = 0;
            deletingIndex = -1;
            currentConfirmQuote = "";
            currentDeletedNotice = "[" + tName + " " + DELETED_QUOTES[(int)(System.currentTimeMillis() % DELETED_QUOTES.length)] + "]";
            refreshThemeList();
            midlet.applyPresetTheme(0);
        }
        repaint();
    }

    private void handleSelection() {
        if (showPaletteMenu) {
            if (selectedIdx == items.size() - 5) { showPaletteMenu = false; refreshThemeList(); }
            else if (selectedIdx > items.size() - 5) {
                targetSlot = selectedIdx - (items.size() - 4);
                int currentRgb = (targetSlot == 0) ? midlet.colorBg : (targetSlot == 1) ? midlet.colorMain : (targetSlot == 2) ? midlet.colorWarn : midlet.colorError;
                rgbToHsv(currentRgb); activeHsvControl = 0; isEditingColor = true;
            }
            repaint();
            return;
        }

        if (selectedIdx == 0) promptSaveNewTheme();
        else if (selectedIdx >= 1 && selectedIdx <= 3) { midlet.applyPresetTheme(selectedIdx - 1); midlet.switchToConfig(); }
        else if (selectedIdx > 3 && selectedIdx < items.size() - 2) { loadThemeColorsByName((String) items.elementAt(selectedIdx)); midlet.switchToConfig(); }
        else if (selectedIdx == items.size() - 2) { showPaletteMenu = true; refreshThemeList(); }
        else if (selectedIdx == items.size() - 1) { midlet.applyPresetTheme(0); midlet.switchToConfig(); }
        repaint();
    }

    private void rgbToHsv(int rgb) {
        int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
        int max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b));
        int delta = max - min;
        val = max; sat = (max == 0) ? 0 : (delta * 255) / max;
        if (delta == 0) hue = 0;
        else {
            if (max == r) hue = (60 * (g - b)) / delta;
            else if (max == g) hue = 120 + (60 * (b - r)) / delta;
            else hue = 240 + (60 * (r - g)) / delta;
            if (hue < 0) hue += 360;
        }
    }

    private void loadThemeColorsByName(String name) {
        RecordStore rs = null;
        try {
            rs = RecordStore.openRecordStore("OM_THEME_" + name, false);
            if (rs.getNumRecords() > 0) {
                String data = new String(rs.getRecord(1), "UTF-8");
                int idx = 0; int[] c = new int[7];
                for (int i = 0; i < 7; i++) {
                    int nextIdx = data.indexOf('\n', idx);
                    if (nextIdx == -1) nextIdx = data.length();
                    c[i] = Integer.parseInt(data.substring(idx, nextIdx).trim());
                    idx = nextIdx + 1;
                }
                midlet.colorBg = c[0]; midlet.colorMain = c[1]; midlet.colorHeaderBg = c[2];
                midlet.colorFocusBg = c[3]; midlet.colorWarn = c[4]; midlet.colorError = c[5]; midlet.colorSoftbarBg = c[6];
                midlet.currentThemeName = name; midlet.resetDynamicStatusColors(); midlet.saveTheme(name);
            }
        } catch (Exception e) {}
        finally { if (rs != null) try { rs.closeRecordStore(); } catch (Exception e) {} }
    }

    private void promptSaveNewTheme() {
        final TextBox tb = new TextBox("Save Theme As", "Theme_" + (System.currentTimeMillis() % 1000), 24, TextField.ANY);
        tb.addCommand(new Command("Save", Command.OK, 1));
        tb.addCommand(new Command("Cancel", Command.CANCEL, 2));
        tb.setCommandListener(new CommandListener() {
            public void commandAction(Command c, Displayable d) {
                if (c.getLabel().equals("Save")) {
                    String name = tb.getString().trim();
                    if (Omega.isPresetName(name)) name = name + "_2";
                    if (name.length() > 0) { midlet.saveTheme(name); refreshThemeList(); }
                }
                midlet.safeSetCurrent(ThemeCanvas.this);
            }
        });
        midlet.safeSetCurrent(tb);
    }

    private void applyPickedColor() {
        // Правка цветов поверх пресета создаёт новую кастомную тему, а пресет остаётся нетронутым
        if (Omega.isPresetName(midlet.currentThemeName)) {
            midlet.currentThemeName = "Custom_" + (System.currentTimeMillis() % 1000);
        }

        int rgb = hsvToRgb(hue, sat, val);
        if (targetSlot == 0) midlet.colorBg = rgb;
        else if (targetSlot == 1) midlet.colorMain = rgb;
        else if (targetSlot == 2) midlet.colorWarn = rgb;
        else if (targetSlot == 3) midlet.colorError = rgb;
        midlet.colorHeaderBg = (midlet.colorBg == 0) ? 0x222222 : midlet.colorBg;
        midlet.colorSoftbarBg = midlet.colorHeaderBg;
        midlet.colorFocusBg = midlet.colorMain;
        midlet.resetDynamicStatusColors();
        midlet.saveTheme(midlet.currentThemeName);
        refreshThemeList();
    }

    private int hsvToRgb(int h, int s, int v) {
        float hf = h / 60.0f; int i = (int) Math.floor(hf); float f = hf - i;
        float pv = v * (1.0f - (s / 255.0f)); float qv = v * (1.0f - (s / 255.0f) * f); float tv = v * (1.0f - (s / 255.0f) * (1.0f - f));
        int r = 0, g = 0, b = 0;
        switch (i) {
            case 0: r = v; g = (int)tv; b = (int)pv; break;
            case 1: r = (int)qv; g = v; b = (int)pv; break;
            case 2: r = (int)pv; g = v; b = (int)tv; break;
            case 3: r = (int)pv; g = (int)qv; b = v; break;
            case 4: r = (int)tv; g = (int)pv; b = v; break;
            default: r = v; g = (int)pv; b = (int)qv; break;
        }
        return ((r & 0xFF) << 16) | ((g & 0xFF) << 8) | (b & 0xFF);
    }
}

private class TerminalCanvas extends Canvas {
    private Omega midlet;
    private String chatName;
    private Vector wrappedLines = new Vector();
    // Роль каждой строки чата: 0 - пользователь, 1 - ИИ, 2 - пустая. Строки без записи (Sys) рисуются цветом предупреждения
    private Vector lineRoles = new Vector();

    private Vector lineStyles = new Vector();               // стили символов каждой строки (byte[]; пустой массив: без стилей)
    private int inTop = 0;                                  // первая видимая строка поля ввода
    private MdLayout smd;                                   // разметка потокового ответа
    private int streamCommitted = 0;                        // сколько символов ответа уже превращено в строки экрана
    private int streamTailStart = 0;                        // с какой строки начинается ещё не завершённая строка ответа

    private boolean inManual = false;                       // окно ввода прокручено пальцем и не следует за курсором
    private boolean inDrag = false;                         // палец ведёт по полю ввода
    private boolean inDragMoved = false;
    private int inDragY = 0;

    private Vector msgLineStart = new Vector();            // с какой строки экрана начинается каждое сообщение окна
    private Vector msgAbsIdx = new Vector();                // номер сообщения во всей переписке
    private Vector msgRoleV = new Vector();                 // 0 - вопрос, 1 - ответ
    private int msgTotal = 0;                               // сколько всего сообщений в переписке
 
     // Меню чата на левой софт-клавише: рисуется поверх экрана, фон закрыт шахматным узором
    private boolean menuOpen = false;
    private Vector menuLabels = new Vector();
    private int[] menuActs = new int[12];
    private int menuSel = 0;
    private int menuScroll = 0;
    private int menuPX = 0, menuPY = 0, menuPW = 0, menuIH = 0, menuRows = 0;   // положение меню на экране (для касаний)
    private Image checkerTile = null;
    private boolean checkerFailed = false;
    private final int CHECK_CELL = 2;                       // размер клетки узора, пиксели
    private final int A_SEND = 1, A_ADD = 2, A_FULL = 3, A_REGEN = 4, A_NEXT = 5, A_PREV = 6, A_EDIT = 7, A_SEARCH = 8, A_FAST = 9;
    // Полоса прокрутки справа и режим быстрой прокрутки
    private int sbW = 2;                                    // текущая ширина полосы (плавно меняется от 2 до SB_WIDE)
    private boolean sbWant = false;                         // нужна широкая полоса (после касания в её области)
    private boolean sbDrag = false;                         // палец ведёт ползунок
    
    private long sbHold = 0;                                // до какого момента держать широкую полосу после касания
    private int sbMax = 0;                                  // на сколько пикселей можно прокрутить чат
    private boolean fastMode = false;                       // режим быстрой прокрутки: вверх/вниз прыгают по сообщениям
    private final int SB_WIDE = 16;
    private final int SB_HIT = 24;                          // ширина зоны касания у правого края
 
    private int scrollOffset = 0;
    private StringBuffer currentInput = new StringBuffer();
    private int cursorPos = 0;                       // положение курсора в строке ввода
    private boolean isLoading = false;
    private volatile String loadingStatusText = "[...]";
    private long loadStart = 0;
    private String attachedImagePath = null;
 
    private int touchStartY = 0;
    private int touchLastY = 0;
    private boolean isDragging = false;
    private final Scroller scroller = new Scroller(this);
    private boolean pressInBar = false;
    private int lastMsgCount = -1;
    private int lastWrapWidth = -1;
    private String lastBuiltChat = null;
    private final int PAGE_MSGS = 10;      // сколько последних сообщений показываем за раз
    private int shownMsgs = 10;            // сколько последних сообщений сейчас в окне
    private int loadMoreLine = -1;         // номер строки кнопки [LOAD OLDER], -1 если её нет
    private int backLine = -1;             // номер строки кнопки [BACK TO LATEST], -1 если её нет
    private int chatTopY = 0;              // область чата на экране (для тапов по кнопкам)
    private int chatBotY = 0;
 
    // Строка ввода: раскладка по экранным строкам, нужна для курсора и касаний
    private Vector inLineStarts = new Vector();
    private String inLayoutText = "";
    private int inPrefixLen = 0;
    private int inVisStart = 0;
    private int inY0 = 0;
    private int inputTop = 0;
    private int inputBottom = 0;
 
    private boolean cursorOn = true;                 // фаза мигания курсора
    private int blinkTick = 0;
    private long hintStart = 0;                      // когда показали подсказку про полноэкранный ввод
    private boolean hintWasShown = false;
    private Image hintPlate = null;
    private int hintPlateStep = -1;
    private int hintPlateW = 0;
    private volatile int repToken = 0;               // для программного автоповтора клавиш
    private int repCount = 0;
 
    private int tickerPos = 0;
    private final int SPEED_DIVISOR = 4;
    private Thread tickerThread;
    private boolean running = true;

    // Потоковый ответ
    private final Object streamLock = new Object();
    private StringBuffer streamBuf = new StringBuffer();   // весь текст, что пришёл по сети
    private volatile boolean streaming = false;            // идёт потоковый ответ
    private boolean streamDone = false;                    // сеть закончила, ответ уже сохранён в чат
    private boolean streamSaved = false;
    private volatile int streamToken = 0;                  // номер текущего потока; меняется при отмене и ошибке
    private int streamShown = 0;                           // сколько символов уже напечатано на экране
    private int streamStartLine = 0;                       // с какой строки экрана начинается ответ
    private boolean followStream = true;                   // экран следует за печатью
    private long streamLastStep = 0;
    private String streamChat = "";
    private String streamPrompt = "";
    private String streamModel = "";
    private volatile String streamSid = null;              // номер потока на прокси (для отмены)
 
    public TerminalCanvas(Omega m) {
        midlet = m;
        setFullScreenMode(true);
        tickerThread = new Thread(new Runnable() {
            public void run() {
                while (running) {
                    try { Thread.sleep(50); } catch (Exception e) {}
                    if (!isShown()) continue;
                    boolean sbAnim = sbStep();                       // плавная смена ширины полосы прокрутки
                    if (isLoading) {
                        // Ожидание: вращаем индикатор и считаем секунды
                        if (loadStart == 0) loadStart = System.currentTimeMillis();
                        tickerPos++;
                        if (streaming) stepStream();
                        repaint();
                    } else {
                        loadStart = 0;
                        boolean redraw = false;
                        blinkTick++;
                        if (blinkTick >= 10) { blinkTick = 0; cursorOn = !cursorOn; redraw = true; }   // курсор мигает раз в 0,5 с
                        if (sbAnim) redraw = true;
                        if (hintAlpha() > 0) { hintWasShown = true; redraw = true; }
                        else if (hintWasShown) { hintWasShown = false; redraw = true; }                  // последний кадр: подсказка исчезла
                        if (redraw) repaint();
                    }
                }
            }
        });
        tickerThread.start();
    }
 
    // Подсказка про полноэкранный ввод: при входе видна сразу, затем плавно гаснет. 0..100
    private int hintAlpha() {
        if (hintStart == 0) return 0;
        if (isLoading || currentInput.length() > 0 || attachedImagePath != null) {
            hintStart = 0;
            return 0;
        }
        long dt = System.currentTimeMillis() - hintStart;
        if (dt < 1500) return 100;
        if (dt >= 4500) return 0;
        return (int) (100 - (dt - 1500) * 100 / 3000);
    }
 
    protected void showNotify() {
        hintStart = System.currentTimeMillis();
        hintPlateStep = -1;
        cursorOn = true;
        blinkTick = 0;
    }
 
    protected void hideNotify() {
        repToken++;
    }

    public void initChatSession(final String cName) {
        chatName = cName;
        currentInput.setLength(0);
        cursorPos = 0;
        inTop = 0;
        attachedImagePath = null;
 
        // Сначала показываем то, что есть на телефоне, чтобы экран не ждал сеть
        rebuildWrappedLines();
        repaint();
 
        // Если есть прокси и это не новый временный чат, спрашиваем у сервера, нет ли у него более новой истории
        if (midlet.proxyUrl != null && midlet.proxyUrl.trim().length() > 0 && !cName.equals("temp_new_chat")) {
            new Thread(new Runnable() {
                public void run() {
                    HttpConnection c = null;
                    InputStream is = null;
                    boolean changed = false;
                    try {
                        String proxyBase = midlet.proxyUrl.trim();
                        if (proxyBase.endsWith("/")) proxyBase = proxyBase.substring(0, proxyBase.length() - 1);
 
                        // have: длина нашей копии. Если серверная не длиннее, сервер отвечает SAME и историю не отдаёт
                        int have = loadChatFromRms(cName).length();
                        String targetUrl = proxyBase + "/?type=getchat&chat=" + midlet.escapeUrl(cName) + "&have=" + have;
                        c = (HttpConnection) Connector.open(targetUrl, Connector.READ);
                        c.setRequestMethod(HttpConnection.GET);
                        c.setRequestProperty("User-Agent", "Omega-Symbian");
 
                        int code = c.getResponseCode();
                        if (code == HttpConnection.HTTP_OK) {
                            is = c.openInputStream();
                            ByteArrayOutputStream bos = new ByteArrayOutputStream();
                            int ch;
                            while ((ch = is.read()) != -1) bos.write(ch);
                            String serverJson = new String(bos.toByteArray(), "UTF-8").trim();
 
                            // Локальную копию заменяем, только если серверная новее (длиннее)
                            if (serverJson.startsWith("[") && serverJson.endsWith("]") && serverJson.length() > 2
                                    && serverJson.length() > have) {
                                saveChatToRms(cName, serverJson);
                                changed = true;
                            }
                        }
                    } catch (Exception e) {
                        // Нет интернета: останется локальная копия
                    } finally {
                        try { if (is != null) is.close(); } catch (Exception e) {}
                        try { if (c != null) c.close(); } catch (Exception e) {}
 
                        if (changed) {
                            display.callSerially(new Runnable() {
                                public void run() {
                                    // Если пользователь за это время ушёл в другой чат, ничего не трогаем
                                    if (chatName != null && chatName.equals(cName)) {
                                        rebuildWrappedLines();
                                        repaint();
                                    }
                                }
                            });
                        }
                    }
                }
            }).start();
        }
    }

    private void syncChatFromServer() {
        new Thread(new Runnable() {
            public void run() {
                HttpConnection c = null;
                InputStream is = null;
                try {
                    String proxyBase = midlet.proxyUrl.trim();
                    if (!proxyBase.endsWith("/")) proxyBase += "/";
                    String syncUrl = proxyBase + "?type=getchat&chat=" + midlet.escapeUrl(chatName) + "&key=" + midlet.escapeUrl(midlet.apiKey);
                    
                    c = (HttpConnection) Connector.open(syncUrl);
                    c.setRequestMethod(HttpConnection.GET);
                    if (c.getResponseCode() == HttpConnection.HTTP_OK) {
                        is = c.openInputStream();
                        ByteArrayOutputStream bos = new ByteArrayOutputStream();
                        int ch;
                        while ((ch = is.read()) != -1) bos.write(ch);
                        String serverJson = new String(bos.toByteArray(), "UTF-8").trim();
                        if (serverJson.startsWith("[") && serverJson.endsWith("]")
                                && serverJson.length() > Omega.loadChatFromRms(chatName).length()) {
                            Omega.saveChatToRms(chatName, serverJson);
                        }
                    }
                } catch (Exception e) {}
                finally {
                    try { if (is != null) is.close(); } catch (Exception e) {}
                    try { if (c != null) c.close(); } catch (Exception e) {}
                    display.callSerially(new Runnable() {
                        public void run() {
                            rebuildWrappedLines();
                            repaint();
                        }
                    });
                }
            }
        }).start();
    }

    private String cleanMarkdown(String text) {
        if (text == null) return "";
        StringBuffer sb = new StringBuffer();
        int len = text.length();
        for (int i = 0; i < len; i++) {
            char c = text.charAt(i);
            if (c == '\\' && i + 1 < len) {
                char next = text.charAt(i + 1);
                if (next == 'n') { sb.append(' '); i++; continue; }
                else if (next == 'r') { i++; continue; }
                else if (next == 'u' && i + 5 < len) { i += 5; sb.append(' '); continue; }
            }
            if (c == '*' || c == '_' || c == '#' || c == '`' || c == '$') { sb.append(' '); continue; }
            if (c == '\r' || c == '\n') { sb.append(' '); continue; }
            sb.append(c);
        }
        String res = sb.toString();
        StringBuffer cleaned = new StringBuffer();
        boolean lastWasSpace = false;
        for (int i = 0; i < res.length(); i++) {
            char c = res.charAt(i);
            if (c == ' ' || c == '\t') {
                if (!lastWasSpace) { cleaned.append(' '); lastWasSpace = true; }
            } else { cleaned.append(c); lastWasSpace = false; }
        }
        return cleaned.toString().trim();
    }

    private void rebuildWrappedLines() {
        wrappedLines.removeAllElements();
        lineRoles.removeAllElements();
        lineStyles.removeAllElements();
        msgLineStart.removeAllElements();
        msgAbsIdx.removeAllElements();
        msgRoleV.removeAllElements();
        loadMoreLine = -1;
        backLine = -1;
        String json = loadChatFromRms(chatName);
        if (json.indexOf("\"role\": \"") != -1) json = normalizeChatJson(json);
 
        // Проход 1: только начала сообщений. История хранится компактно, поэтому хватает indexOf, текст не разбираем
        String key = "{\"role\":\"";
        Vector starts = new Vector();
        int sp = 0;
        while (true) {
            int i = json.indexOf(key, sp);
            if (i == -1) break;
            starts.addElement(new Integer(i));
            sp = i + key.length();
        }
        int n = starts.size();
        int jsonEnd = json.lastIndexOf(']');
        if (jsonEnd == -1) jsonEnd = json.length();
 
        int screenWidth = getWidth() - 6;
        Font font = Omega.uiFont();
 
        boolean sameChat = chatName != null && chatName.equals(lastBuiltChat);
        boolean fromTemp = "temp_new_chat".equals(lastBuiltChat);
        boolean widthSame = (screenWidth == lastWrapWidth);
        boolean newMsgs = widthSame && ((sameChat && n > lastMsgCount) || (fromTemp && n > 0));
 
        // Другой чат или новое сообщение: снова показываем только последние
        if (!sameChat || newMsgs || streaming) shownMsgs = PAGE_MSGS;
 
        int start = n - shownMsgs;
        if (start < 0) start = 0;
 
        if (start > 0) {
            int more = (start < PAGE_MSGS) ? start : PAGE_MSGS;
            loadMoreLine = wrappedLines.size();
            wrappedLines.addElement("[ LOAD " + more + " OLDER (" + start + ") ]");
            lineRoles.addElement(new Integer(4));
            lineStyles.addElement(MdLayout.NONE);
        }
 
        // Проход 2: разбираем и раскладываем только сообщения из окна
        int lastAiLine = 0;
        boolean lastIsAi = false;
        for (int i = start; i < n; i++) {
            int a = ((Integer) starts.elementAt(i)).intValue();
            int b = (i + 1 < n) ? ((Integer) starts.elementAt(i + 1)).intValue() - 1 : jsonEnd;
            if (b <= a) continue;
            String obj = json.substring(a, b);
            int rEnd = obj.indexOf('"', key.length());
            if (rEnd == -1) continue;
            String role = obj.substring(key.length(), rEnd);
            int cIdx = obj.indexOf("\"content\":\"", rEnd);
            if (cIdx == -1) continue;
            int cs = cIdx + 11;
            int ce = obj.endsWith("\"}") ? obj.length() - 2 : findJsonStringEnd(obj, cs);
            if (ce < cs) ce = cs;
            String content = unescapeString(obj.substring(cs, ce));
 
            int before = wrappedLines.size();
            if (role.equals("user") || role.equals("assistant")) {
                msgLineStart.addElement(new Integer(before));
                msgAbsIdx.addElement(new Integer(i));
                msgRoleV.addElement(new Integer(role.equals("user") ? 0 : 1));
            }
            if (role.equals("user")) {
                addMessage(0, "U> ", content);
            } else if (role.equals("assistant")) {
                String mdl = null;
                int mi = obj.indexOf("\"model\":\"", rEnd);
                if (mi != -1 && mi < cIdx) {
                    int ms = mi + 9;
                    mdl = unescapeString(obj.substring(ms, findJsonStringEnd(obj, ms)));
                }
                addMessage(1, Omega.modelLabel(mdl) + "> ", content);
                if (i == n - 1) { lastIsAi = true; lastAiLine = before; }
            }
        }
 
        // Показываем больше десяти сообщений: внизу кнопка возврата к последним
        if (n - start > PAGE_MSGS) {
            backLine = wrappedLines.size();
            wrappedLines.addElement("[ BACK TO LATEST ]");
            lineRoles.addElement(new Integer(4));
            lineStyles.addElement(MdLayout.NONE);
        }
 
        // Идёт потоковый ответ: дописываем вопрос и ту часть ответа, что уже напечатана
        if (streaming && chatName != null && chatName.equals(streamChat)) layoutStreamTail();
 
        if (newMsgs) {
            // Пришёл новый ответ: показываем его с начала, а не с конца
            if (n > 0 && lastIsAi) scroller.jump(lastAiLine * font.getHeight());
            else scroller.jumpToEnd();
        } else if (!(sameChat && widthSame)) {
            // Открыли другой чат или изменился размер экрана: показываем последние сообщения
            scroller.jumpToEnd();
        }
        lastBuiltChat = chatName;
        lastMsgCount = n;
        msgTotal = n;
        lastWrapWidth = screenWidth;
    }
 
    // Раскладывает одно сообщение (с markdown у ответов) и добавляет пустую строку после него
    private void addMessage(int role, String label, String content) {
        MdLayout m = newMd(role);
        m.prefix = label;
        boolean md = (role == 1);
        int p = 0;
        while (true) {
            int nl = content.indexOf('\n', p);
            m.addSourceLine(nl == -1 ? content.substring(p) : content.substring(p, nl), md);
            if (nl == -1) break;
            p = nl + 1;
        }
        m.finish();
        m.separator();
    }
 
    private MdLayout newMd(int role) {
        MdLayout m = new MdLayout(Omega.uiFont(), getWidth() - 6);
        m.setTarget(wrappedLines, lineStyles, lineRoles, role);
        return m;
    }
 
    // Убирает строки экрана, начиная с номера n (во всех трёх параллельных векторах)
    private void trimLines(int n) {
        while (wrappedLines.size() > n) wrappedLines.removeElementAt(wrappedLines.size() - 1);
        while (lineRoles.size() > n) lineRoles.removeElementAt(lineRoles.size() - 1);
        while (lineStyles.size() > n) lineStyles.removeElementAt(lineStyles.size() - 1);
    }
 
    // Старые чаты могли сохраниться с пробелами после двоеточий: приводим к компактному виду
    private String normalizeChatJson(String s) {
        s = replaceAllStr(s, "\"role\": \"", "\"role\":\"");
        s = replaceAllStr(s, "\"content\": \"", "\"content\":\"");
        s = replaceAllStr(s, "\"model\": \"", "\"model\":\"");
        return s;
    }
 
    private String replaceAllStr(String s, String from, String to) {
        if (s.indexOf(from) == -1) return s;
        StringBuffer sb = new StringBuffer(s.length());
        int pos = 0;
        while (true) {
            int i = s.indexOf(from, pos);
            if (i == -1) break;
            sb.append(s.substring(pos, i)).append(to);
            pos = i + from.length();
        }
        sb.append(s.substring(pos));
        return sb.toString();
    }

        // ===== Потоковый ответ =====
 
    // Вызывается из сетевого потока, когда ответ начал приходить: готовит экран, возвращает номер потока
    // Если пользователь успел нажать стоп до начала потока, возвращает -1 и ничего не делает
    private int beginStream(int launch, String chat, String prompt, String model) {
        int tok;
        synchronized (streamLock) {
            if (launch != streamToken) return -1;
            streamToken++;
            tok = streamToken;
            streamBuf.setLength(0);
            streaming = true;
            streamDone = false;
            streamSaved = false;
            streamShown = 0;
            streamLastStep = 0;
            followStream = true;
            streamChat = chat;
            streamPrompt = prompt;
            streamModel = (model == null) ? "" : model;
        }
        display.callSerially(new Runnable() {
            public void run() {
                if (!streaming) return;
                shownMsgs = PAGE_MSGS;
                rebuildWrappedLines();          // сам допишет вопрос и начало ответа
                scroller.jumpToEnd();
                repaint();
            }
        });
        return tok;
    }
 
    // Новый кусок текста от сети
    private void streamPush(String piece) {
        synchronized (streamLock) {
            streamBuf.append(piece);
            loadingStatusText = "stream " + streamBuf.length() + " chars";
        }
    }
 
    // Сеть закончила без ошибок: ответ сразу сохраняется в чат, а на экране он допечатывается
    private void endStreamOk(int tok) {
        String full;
        synchronized (streamLock) {
            if (tok != streamToken || streamSaved) return;
            streamSaved = true;
            streamDone = true;
            full = streamBuf.toString();
        }
        streamSid = null;
        appendToChatRms(streamChat, streamPrompt, full, streamModel);
    }
 
    // Ошибка посреди потока: оставляем на экране то, что успело прийти, и пишем ошибку (в историю это не попадает)
    private void endStreamErr(final int tok, final String msg) {
        synchronized (streamLock) {
            if (tok != streamToken) return;
            streamToken++;
            streaming = false;
        }
        streamSid = null;
        display.callSerially(new Runnable() {
            public void run() {
                isLoading = false;
                loadingStatusText = "[...]";
                synchronized (streamLock) { streamShown = streamBuf.length(); }
                layoutStreamReply();
                wrapTextIntoVector("Sys: Err " + msg, Omega.uiFont(), getWidth() - 6, wrappedLines);
                repaint();
            }
        });
    }
 
    // Остановка по правой софт-клавише или кнопке [CONFIG] во время ожидания и потока
    private void stopLoading() {
        boolean was = false;
        String partial = null;
        final String sid = streamSid;
        synchronized (streamLock) {
            streamToken++;                         // запоздавший поток, который ещё не начался, начинаться не станет
            if (streaming) {
                was = true;
                streaming = false;
                if (!streamSaved && streamBuf.length() > 0) {
                    streamSaved = true;
                    partial = streamBuf.toString();
                }
            }
        }
        if (partial != null) appendToChatRms(streamChat, streamPrompt, partial, streamModel);
        streamSid = null;
        if (sid != null) sendProxyCancel(sid);
        isLoading = false;
        loadingStatusText = "[...]";
        if (was) rebuildWrappedLines();
        repaint();
    }
 
    // Просит прокси перестать читать ответ провайдера
    private void sendProxyCancel(final String sid) {
        if (midlet.proxyUrl == null || midlet.proxyUrl.trim().length() == 0) return;
        new Thread(new Runnable() {
            public void run() {
                HttpConnection c = null;
                try {
                    String base = midlet.proxyUrl.trim();
                    if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
                    c = (HttpConnection) Connector.open(base + "/?type=cancel&sid=" + sid, Connector.READ);
                    c.setRequestMethod(HttpConnection.GET);
                    c.getResponseCode();
                } catch (Exception e) {}
                finally { if (c != null) try { c.close(); } catch (Exception ex) {} }
            }
        }).start();
    }
 
    // Дописывает в конец экрана вопрос и уже напечатанную часть ответа
    private void layoutStreamTail() {
        addMessage(0, "U> ", streamPrompt);
        streamStartLine = wrappedLines.size();
        smd = newMd(1);
        smd.prefix = Omega.modelLabel(streamModel) + "> ";
        streamCommitted = 0;
        streamTailStart = wrappedLines.size();
        layoutStreamReply();
    }
 
    private void layoutStreamReply() {
        if (smd == null) return;
        trimLines(streamTailStart);                 // убираем строки ещё не завершённого хвоста
        String all;
        synchronized (streamLock) {
            all = streamBuf.toString();
            if (streamShown > all.length()) streamShown = all.length();
            all = all.substring(0, streamShown);
        }
        // Завершённые строки (до последнего перевода строки) раскладываются один раз и больше не пересчитываются
        int nl;
        while ((nl = all.indexOf('\n', streamCommitted)) != -1) {
            smd.addSourceLine(all.substring(streamCommitted, nl), true);
            streamCommitted = nl + 1;
            streamTailStart = wrappedLines.size();
        }
        // Хвост без перевода строки пересчитывается на каждом шаге: он короткий
        smd.addPartial(all.substring(streamCommitted));
    }
 
    // Вызывать в UI-потоке: обновить ответ на экране и при необходимости прокрутить
    private void relayoutStream() {
        if (!streaming) return;
        layoutStreamReply();
        if (followStream) {
            // Пока ответ короткий, показываем его конец. Когда он вырос, начало ответа остаётся наверху экрана
            int fh = Omega.uiFont().getHeight();
            int viewH = chatBotY - chatTopY;
            int target = wrappedLines.size() * fh - viewH;
            int startPx = streamStartLine * fh;
            if (target > startPx) target = startPx;
            if (target < 0) target = 0;
            scroller.jump(target);
        }
    }
 
    // Один шаг "печатной машинки" (вызывается из тикера): открывает на экране ещё несколько символов
    private void stepStream() {
        boolean doStep = false;
        boolean finished = false;
        synchronized (streamLock) {
            if (!streaming) return;
            int avail = streamBuf.length();
            long now = System.currentTimeMillis();
            if (streamShown < avail) {
                if (now - streamLastStep >= midlet.getStreamDelay() - 15) {
                    int backlog = avail - streamShown;
                    // Чем больше отставание от сети, тем быстрее печатаем, чтобы не отставать надолго
                    streamShown += midlet.getStreamStep() + backlog / 12;
                    if (streamShown > avail) streamShown = avail;
                    streamLastStep = now;
                    doStep = true;
                }
            } else if (streamDone) {
                finished = true;
            }
        }
        if (doStep) {
            display.callSerially(new Runnable() {
                public void run() { relayoutStream(); repaint(); }
            });
        } else if (finished) {
            display.callSerially(new Runnable() {
                public void run() { finishStreamView(); }
            });
        }
    }
 
    // Ответ напечатан до конца: показываем чат из сохранённой истории
    private void finishStreamView() {
        synchronized (streamLock) {
            if (!streaming || !streamDone) return;
            streaming = false;
        }
        isLoading = false;
        loadingStatusText = "[...]";
        rebuildWrappedLines();
        repaint();
    }
 
    // Ищет в строке JSON значение-строку по ключу вида "\"content\"".
    // Возвращает индекс первого символа значения, -1 если ключа нет, -2 если значение не строка (например, null)
    private int jsonStrStart(String s, String key, int from) {
        int k = s.indexOf(key, from);
        if (k == -1) return -1;
        int p = k + key.length();
        while (p < s.length() && (s.charAt(p) == ' ' || s.charAt(p) == ':')) p++;
        if (p < s.length() && s.charAt(p) == '"') return p + 1;
        return -2;
    }
 
    // Достаёт кусок текста из одной строки потока. null: в строке нет поля с текстом вообще
    private String streamPiece(String ln, int prov) {
        int from;
        if (prov == 0) {
            int k = jsonStrStart(ln, "\"response\"", 0);
            if (k == -1) return null;
            if (k < 0) return "";
            from = k;
        } else {
            int d = ln.indexOf("\"delta\"");
            if (d == -1) return null;
            int k = jsonStrStart(ln, "\"content\"", d);
            if (k < 0) return "";                       // в этом куске текста нет (роль, конец и т.п.)
            from = k;
        }
        int end = findJsonStringEnd(ln, from);
        return unescapeString(ln.substring(from, end));
    }
 
    // Если в строке сообщение об ошибке, возвращает его текст
    private String streamErrorOf(String ln) {
        if (ln.indexOf("\"error\"") == -1) return null;
        int m = jsonStrStart(ln, "\"message\"", 0);
        if (m >= 0) return unescapeString(ln.substring(m, findJsonStringEnd(ln, m)));
        return ln.length() > 100 ? ln.substring(0, 100) : ln;
    }

    private void loadMoreMessages() {
        int fh = Omega.uiFont().getHeight();
        int oldLines = wrappedLines.size();
        boolean hadBack = (backLine >= 0);
        int oldPos = scroller.getPos();
        shownMsgs += PAGE_MSGS;
        rebuildWrappedLines();
        int shift = (wrappedLines.size() - oldLines) - ((backLine >= 0 ? 1 : 0) - (hadBack ? 1 : 0));
        scroller.jump(oldPos + shift * fh);
        repaint();
    }
 
    // Сворачивает историю до последних сообщений и переходит в конец переписки
    private void backToLatest() {
        shownMsgs = PAGE_MSGS;
        rebuildWrappedLines();
        scroller.jumpToEnd();
        repaint();
    }
 
    // Экран прокручен до самого низа
    private boolean atBottom() {
        int fh = Omega.uiFont().getHeight();
        return scroller.getPos() + (chatBotY - chatTopY) >= wrappedLines.size() * fh - 2;
    }

    protected void sizeChanged(int w, int h) {
        super.sizeChanged(w, h);
        rebuildWrappedLines();
        repaint();
    }

    private void triggerBrowserBridgeSend() {
        final String filePath = attachedImagePath;
        final String promptText = currentInput.toString().trim();
 
        if (chatName.equals("temp_new_chat")) {
            // Новый чат называем по началу запроса, но не занимаем имя уже существующего чата
            chatName = Omega.uniqueChatName(Omega.sanitizeChatName(promptText, 12, "img_chat"));
            midlet.currentChat = chatName;
            midlet.saveSettings();
        }
 
        final String finalImagePrompt = (promptText.length() > 0 ? promptText : "Describe this image");
        final String rawHistory = loadChatFromRms(chatName);
 
        currentInput.setLength(0);
        cursorPos = 0;
        attachedImagePath = null;
        final String modelAtSend = midlet.selectedModel;
        isLoading = true;
        stage("read file");
 
        final boolean useProxy = (midlet.proxyMode != 0 && midlet.proxyUrl != null && midlet.proxyUrl.trim().length() > 0);
 
        if (useProxy) {
            // --- СТАРЫЙ РЕЖИМ: ОТПРАВКА ЧАНКАМИ ЧЕРЕЗ ПРОКСИ ---
            new Thread(new Runnable() {
                public void run() {
                    HttpConnection c = null;
                    OutputStream os = null;
                    InputStream is = null;
                    try {
                        FileConnection fc = (FileConnection) Connector.open(filePath, Connector.READ);
                        if (!fc.exists()) { fc.close(); failLoading("Sys: Image file not found"); return; }
                        long fileSize = fc.fileSize();
                        InputStream fis = fc.openInputStream();
                        byte[] fileBytes = new byte[(int)fileSize];
                        int readTotal = 0;
                        while (readTotal < fileSize) {
                            int r = fis.read(fileBytes, readTotal, (int)fileSize - readTotal);
                            if (r == -1) break;
                            readTotal += r;
                        }
                        fis.close(); fc.close();
 
                        String proxyBase = midlet.proxyUrl.trim();
                        if (!proxyBase.endsWith("/")) proxyBase += "/";
                        final String sid = "omega_img_" + System.currentTimeMillis();
 
                        int chunkSize = 8192;
                        int totalImageChunks = (fileBytes.length + chunkSize - 1) / chunkSize;
 
                        for (int i = 0; i < totalImageChunks; i++) {
                            int start = i * chunkSize;
                            int end = Math.min(start + chunkSize, fileBytes.length);
                            byte[] chunkBuf = new byte[end - start];
                            System.arraycopy(fileBytes, start, chunkBuf, 0, end - start);
                            boolean isImgFinal = (i == totalImageChunks - 1);
 
                            stage("img " + (i + 1) + "/" + totalImageChunks + " (" + (fileSize / 1024) + "KB) > " + hostOf(proxyBase));
 
                            StringBuffer urlSb = new StringBuffer(proxyBase);
                            urlsb_append(urlSb, sid, "image_chunk", i + 1, totalImageChunks, isImgFinal);
 
                            c = (HttpConnection) Connector.open(urlSb.toString(), Connector.READ_WRITE, true);
                            c.setRequestMethod(HttpConnection.POST);
                            c.setRequestProperty("Content-Type", "application/octet-stream");
                            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36");
 
                            os = c.openOutputStream();
                            os.write(chunkBuf);
                            os.flush(); os.close();
 
                            is = c.openInputStream();
                            ByteArrayOutputStream bos = new ByteArrayOutputStream();
                            int ch; while ((ch = is.read()) != -1) bos.write(ch);
                            is.close(); c.close();
 
                            // Сервер отвечает CHUNK_OK на каждый принятый кусок. Иначе картинка дойдёт битой
                            String ack = new String(bos.toByteArray(), "UTF-8").trim();
                            if (!ack.equals("CHUNK_OK")) {
                                throw new Exception("img chunk " + (i + 1) + ": " + (ack.length() > 100 ? ack.substring(0, 100) : ack));
                            }
                        }
 
                        StringBuffer metaJson = new StringBuffer();
                        metaJson.append("{\"prompt\":\"").append(escapeStatic(finalImagePrompt)).append("\",")
                                .append("\"sys\":\"").append(escapeStatic(midlet.systemPrompt != null ? midlet.systemPrompt : "")).append("\",")
                                .append("\"history\":").append(rawHistory != null ? rawHistory : "[]").append("}");
 
                        byte[] metaBytes = metaJson.toString().getBytes("UTF-8");
                        int totalTextChunks = (metaBytes.length + chunkSize - 1) / chunkSize;
                        if (totalTextChunks == 0) totalTextChunks = 1;
 
                        for (int i = 0; i < totalTextChunks; i++) {
                            int start = i * chunkSize;
                            int end = Math.min(start + chunkSize, metaBytes.length);
                            byte[] chunkBuf = new byte[end - start];
                            if (metaBytes.length > 0) {
                                System.arraycopy(metaBytes, start, chunkBuf, 0, end - start);
                            }
                            boolean isTextFinal = (i == totalTextChunks - 1);
 
                            stage("meta " + (i + 1) + "/" + totalTextChunks + " (" + sizeStr(chunkBuf.length) + ")");
 
                            StringBuffer urlSb = new StringBuffer(proxyBase);
                            urlSb.append("?sid=").append(sid)
                                .append("&type=image_text_chunk")
                                .append("&chat=").append(escapeUrl(chatName))
                                .append("&prov=").append(midlet.selectedProvider)
                                .append("&model=").append(escapeUrl(midlet.selectedModel))
                                .append("&key=").append(escapeUrl(midlet.apiKey))
                                .append("&chunk=").append(i + 1)
                                .append("&total=").append(totalTextChunks)
                                .append("&final=").append(isTextFinal ? "1" : "0");
 
                            c = (HttpConnection) Connector.open(urlSb.toString(), Connector.READ_WRITE, true);
                            c.setRequestMethod(HttpConnection.POST);
                            c.setRequestProperty("Content-Type", "application/octet-stream");
                            c.setRequestProperty("User-Agent", "Omega-Symbian");
                            String paramsHdr = midlet.paramsHeader();
                            if (paramsHdr.length() > 0) {
                                c.setRequestProperty("X-Omega-Params", paramsHdr);
                            }
 
                            os = c.openOutputStream();
                            os.write(chunkBuf);
                            os.flush(); os.close();
                            if (isTextFinal) stage("wait model via proxy");
 
                            is = c.openInputStream();
                            ByteArrayOutputStream bos = new ByteArrayOutputStream();
                            int ch; while ((ch = is.read()) != -1) bos.write(ch);
                            is.close(); c.close();
 
                            if (!isTextFinal) {
                                String ack = new String(bos.toByteArray(), "UTF-8").trim();
                                if (!ack.equals("CHUNK_OK")) {
                                    throw new Exception("meta chunk " + (i + 1) + ": " + (ack.length() > 100 ? ack.substring(0, 100) : ack));
                                }
                            }
 
                            if (isTextFinal) {
                                String resp = new String(bos.toByteArray(), "UTF-8").trim();
                                // Ошибки сервера и провайдера в историю чата не пишем: они сбивают модель в следующих запросах
                                if (resp.startsWith("ERR:") || resp.startsWith("<!DOCTYPE") || resp.startsWith("<html")) {
                                    throw new Exception(resp.length() > 200 ? resp.substring(0, 200) : resp);
                                }
                                final String finalResult = resp;
                                stage("save chat");
                                Omega.appendToChatRms(chatName, "[IMG] " + promptText, finalResult, modelAtSend);
                                display.callSerially(new Runnable() {
                                    public void run() {
                                        isLoading = false; loadingStatusText = "[...]";
                                        rebuildWrappedLines();
                                        repaint();
                                    }
                                });
                            }
                        }
 
                    } catch (final Throwable t) {
                        final String st = loadingStatusText;
                        display.callSerially(new Runnable() {
                            public void run() {
                                isLoading = false; loadingStatusText = "[...]";
                                String msg = t.getMessage(); if (msg == null) msg = t.getClass().getName();
                                wrapTextIntoVector("Sys: Err: " + msg + " @" + st, Omega.uiFont(), getWidth() - 6, wrappedLines);
                                repaint();
                            }
                        });
                    } finally {
                        try { if (is != null) is.close(); } catch (Exception e) {}
                        try { if (os != null) os.close(); } catch (Exception e) {}
                        try { if (c != null) c.close(); } catch (Exception e) {}
                    }
                }
            }).start();
 
        } else {
            // --- НОВЫЙ РЕЖИМ DIRECT: ЧИТАЕМ, КОДИРУЕМ В BASE64 И ШЛЕМ НАПРЯМУЮ ---
            new Thread(new Runnable() {
                public void run() {
                    InputStream fis = null;
                    try {
                        FileConnection fc = (FileConnection) Connector.open(filePath, Connector.READ);
                        if (!fc.exists()) { fc.close(); failLoading("Sys: Image file not found"); return; }
                        long fileSize = fc.fileSize();
                        if (fileSize > 2097152) {
                            throw new Exception("File > 2MB limit");
                        }
                        fis = fc.openInputStream();
                        byte[] fileBytes = new byte[(int)fileSize];
                        int readTotal = 0;
                        while (readTotal < fileSize) {
                            int r = fis.read(fileBytes, readTotal, (int)fileSize - readTotal);
                            if (r == -1) break;
                            readTotal += r;
                        }
                        fis.close(); fc.close();
 
                        stage("encode base64 " + (fileSize / 1024) + "KB");
 
                        String base64Img = encodeBase64String(fileBytes);
                        
                        // Передаем картинку в прямой отправитель
                        new Thread(new TerminalSender(finalImagePrompt, "[IMG] " + promptText, base64Img, rawHistory, chatName, modelAtSend)).start();
 
                    } catch (final Throwable t) {
                        final String st = loadingStatusText;
                        display.callSerially(new Runnable() {
                            public void run() {
                                isLoading = false; loadingStatusText = "[...]";
                                String msg = t.getMessage(); if (msg == null) msg = t.getClass().getName();
                                wrapTextIntoVector("Sys: Direct Img Err: " + msg + " @" + st, Omega.uiFont(), getWidth() - 6, wrappedLines);
                                repaint();
                            }
                        });
                    } finally {
                        try { if (fis != null) fis.close(); } catch (Exception e) {}
                    }
                }
            }).start();
        }
    }

    private void urlsb_append(StringBuffer sb, String sid, String type, int chunk, int total, boolean isFinal) {
        sb.append("?sid=").append(sid)
          .append("&type=").append(type)
          .append("&chunk=").append(chunk)
          .append("&total=").append(total)
          .append("&final=").append(isFinal ? "1" : "0");
    }

    private String parseRespStatic(String json, int type) {
        if (json == null || json.length() == 0) return "Sys: Empty response";
        if (json.indexOf("\"error\"") != -1) {
            int eIdx = json.indexOf("\"message\":\"");
            if (eIdx != -1) {
                int eEnd = json.indexOf('"', eIdx + 11);
                if (eEnd != -1) return "API Error: " + json.substring(eIdx + 11, eEnd);
            }
            return "API Error";
        }
        int msgIdx = json.indexOf("\"message\"");
        if (msgIdx != -1) {
            int cInMsg = json.indexOf("\"content\"", msgIdx);
            if (cInMsg != -1) {
                int col = json.indexOf(':', cInMsg);
                int q1 = json.indexOf('"', col);
                if (q1 != -1) { String ext = extractJsonStringValue(json, q1 + 1); if (ext.length() > 0) return ext; }
            }
        }
        String[] keys = {"\"response\"", "\"content\"", "\"text\""};
        for (int k = 0; k < keys.length; k++) {
            int kIdx = json.indexOf(keys[k]);
            if (kIdx != -1) {
                int col = json.indexOf(':', kIdx + keys[k].length());
                if (col != -1) {
                    int q1 = json.indexOf('"', col);
                    if (q1 != -1) { String ext = extractJsonStringValue(json, q1 + 1); if (ext.length() > 0) return ext; }
                }
            }
        }
        return "Raw JSON: " + (json.length() > 80 ? json.substring(0, 80) : json);
    }

    private String encodeBase64String(byte[] src) {
        byte[] tbl = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".getBytes();
        int len = src.length; StringBuffer sb = new StringBuffer((len * 4) / 3 + 4);
        int i = 0;
        while (i < len) {
            int b0 = src[i++] & 0xFF;
            int b1 = (i < len) ? (src[i++] & 0xFF) : -1;
            int b2 = (i < len) ? (src[i++] & 0xFF) : -1;
            sb.append((char) tbl[b0 >> 2]);
            sb.append((char) tbl[((b0 & 0x3) << 4) | (b1 != -1 ? (b1 >> 4) : 0)]);
            if (b1 != -1) sb.append((char) tbl[((b1 & 0xF) << 2) | (b2 != -1 ? (b2 >> 6) : 0)]); else sb.append('=');
            if (b2 != -1) sb.append((char) tbl[b2 & 0x3F]); else sb.append('=');
        }
        return sb.toString();
    }

    private void wrapTextIntoVector(String text, Font font, int maxWidth, Vector outVector) {
        if (text == null || text.length() == 0) { outVector.addElement(""); return; }
        text = text.replace('\n', ' ');
        int len = text.length(); int start = 0;
        while (start < len) {
            int w = 0, end = start, lastSp = -1;
            while (end < len) {
                char c = text.charAt(end);
                int cwd = MdLayout.charW(font, c);
                if (w + cwd > maxWidth && end > start) break;
                w += cwd;
                if (c == ' ') lastSp = end;
                end++;
            }
            int subLen = end - start;
            if (end < len && text.charAt(end) != ' ' && text.charAt(end - 1) != ' ' && lastSp > start) {
                subLen = (lastSp + 1) - start;
            }
            outVector.addElement(text.substring(start, start + subLen).trim());
            start += subLen;
            while (start < len && text.charAt(start) == ' ') start++;
        }
    }

    private int findJsonStringEnd(String s, int start) {
        for (int i = start; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '\\') { i++; continue; }   // пропускаем экранированный символ целиком
            if (ch == '"') return i;
        }
        return s.length();
    }
 
    private String unescapeString(String s) {
        StringBuffer sb = new StringBuffer();
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '\\' && i + 1 < s.length()) {
                char next = s.charAt(i + 1);
                if (next == 'n') { sb.append('\n'); i++; continue; }
                else if (next == 't') { sb.append('\t'); i++; continue; }
                else if (next == '"') { sb.append('"'); i++; continue; }
                else if (next == '\\') { sb.append('\\'); i++; continue; }
                else if (next == '/') { sb.append('/'); i++; continue; }
                else if (next == 'r') { i++; continue; }
                else if (next == 'u' && i + 6 <= s.length()) {
                    try {
                        sb.append((char) Integer.parseInt(s.substring(i + 2, i + 6), 16));
                        i += 5;
                        continue;
                    } catch (Exception e) {}
                }
            }
            sb.append(ch);
        }
        return sb.toString();
    }

    // Из тела ошибки провайдера достаёт читаемый текст. У OpenRouter настоящая причина лежит в "raw",
    // а "message" там просто "Provider returned error"
    private String shortErr(String body) {
        if (body == null) return "";
        String[] keys = { "\"raw\":\"", "\"message\":\"" };
        for (int k = 0; k < keys.length; k++) {
            int i = body.indexOf(keys[k]);
            if (i != -1) {
                String v = extractJsonStringValue(body, i + keys[k].length());
                if (v.length() > 0) return v.length() > 200 ? v.substring(0, 200) : v;
            }
        }
        return body.length() > 160 ? body.substring(0, 160) : body;
    }
 
    private String extractJsonStringValue(String json, int start) {
        StringBuffer sb = new StringBuffer();
        for (int i = start; i < json.length(); i++) {
            char ch = json.charAt(i);
            if (ch == '"') break;
            if (ch == '\\' && i + 1 < json.length()) {
                char next = json.charAt(i + 1);
                if (next == 'n') { sb.append('\n'); i++; continue; }
                else if (next == 't') { sb.append('\t'); i++; continue; }
                else if (next == '"') { sb.append('"'); i++; continue; }
                else if (next == '\\') { sb.append('\\'); i++; continue; }
                else if (next == '/') { sb.append('/'); i++; continue; }
                else if (next == 'r') { i++; continue; }
                else if (next == 'u' && i + 5 < json.length()) {
                    try {
                        sb.append((char) Integer.parseInt(json.substring(i + 2, i + 6), 16));
                        i += 5;
                        continue;
                    } catch (Exception e) {}
                }
            }
            sb.append(ch);
        }
        return sb.toString().trim();
    }

    protected void paint(Graphics g) {
        int w = getWidth();
        int h = getHeight();
 
        g.setColor(midlet.colorBg);
        g.fillRect(0, 0, w, h);
 
        Font font = Omega.uiFont();
        g.setFont(font);
        int fh = font.getHeight();
 
        int headerH = fh + 6;
        if (isLoading) headerH += fh + 2;
 
        g.setColor(midlet.colorHeaderBg);
        g.fillRect(0, 0, w, headerH);
        g.setColor(midlet.colorMain);
        g.drawString("== OMEGA // " + (chatName.equals("temp_new_chat") ? "new_chat" : chatName) + " ==", 4, 3, Graphics.TOP | Graphics.LEFT);
 
        if (isLoading) {
            // Вращающийся индикатор, этап отправки и секунды ожидания
            g.setColor(midlet.visibleColor(midlet.colorWarn, midlet.colorHeaderBg));
            String spin = "|/-\\".substring((tickerPos / 2) % 4, (tickerPos / 2) % 4 + 1);
            g.drawString(spin, 4, fh + 5, Graphics.TOP | Graphics.LEFT);
            int sx = 4 + font.stringWidth("W") + 6;
            long secs = (loadStart == 0) ? 0 : (System.currentTimeMillis() - loadStart) / 1000;
            String renderStatus = loadingStatusText + " " + secs + "s";
            if (font.stringWidth(renderStatus) > w - sx - 4) {
                int shift = (tickerPos / SPEED_DIVISOR) % (renderStatus.length() + 5);
                String padded = renderStatus + "     " + renderStatus;
                if (shift < padded.length()) renderStatus = padded.substring(shift);
            }
            g.drawString(renderStatus, sx, fh + 5, Graphics.TOP | Graphics.LEFT);
        }
 
        // --- Строка ввода: своя раскладка, чтобы курсор можно было ставить в любое место ---
        String prefix = (attachedImagePath != null ? "[IMG ATTACHED] " : "") + "> ";
        if (cursorPos > currentInput.length()) cursorPos = currentInput.length();
        if (cursorPos < 0) cursorPos = 0;
        String inText = prefix + currentInput.toString();
        int curIdx = prefix.length() + cursorPos;
        layoutInput(inText, font, w - 8, inLineStarts);
        int totalLines = inLineStarts.size();
        int cursorLine = 0;
        for (int i = 0; i < totalLines; i++) {
            if (((Integer) inLineStarts.elementAt(i)).intValue() <= curIdx) cursorLine = i;
        }
        int maxVisibleInputLines = 3;
        int renderLinesCount = Math.min(totalLines, maxVisibleInputLines);
        // Окно ввода прокручивается вслед за курсором и не прыгает, пока курсор остаётся внутри него
        if (!inManual) {
            if (cursorLine < inTop) inTop = cursorLine;
            if (cursorLine >= inTop + renderLinesCount) inTop = cursorLine - renderLinesCount + 1;
        }
        if (inTop > totalLines - renderLinesCount) inTop = totalLines - renderLinesCount;
        if (inTop < 0) inTop = 0;
        int topLine = inTop;
        int inputAreaHeight = (fh * renderLinesCount) + 6;
 
        int bottomBarHeight = 35;
        int chatAreaBottom = h - bottomBarHeight - inputAreaHeight - 4;
        int chatTop = headerH + 4;
        int viewH = chatAreaBottom - chatTop;
        chatTopY = chatTop;
        chatBotY = chatAreaBottom;
 
        // Текст чата рисуется со смещением в пикселях и обрезается по своей области
        scroller.setMax(wrappedLines.size() * fh - viewH);
        sbMax = wrappedLines.size() * fh - viewH;
        if (sbMax < 0) sbMax = 0;
        int pos = scroller.getPos();
        g.setClip(0, chatTop, w, viewH);
        int cMain = midlet.colorMain;
        int cUser = Omega.mixColor(midlet.colorMain, midlet.colorBg, 62);   // вопросы пользователя чуть тусклее ответов
        if (Omega.colorDistance(cUser, midlet.colorBg) < 110) cUser = cMain;
        int cSys = midlet.visibleColor(midlet.colorWarn, midlet.colorBg);
        int cBold = Omega.emphColor(cMain, midlet.colorBg);
        int cCode = midlet.visibleColor(midlet.colorWarn, midlet.colorBg);
        int cDim = Omega.mixColor(cMain, midlet.colorBg, 62);
        if (Omega.colorDistance(cDim, midlet.colorBg) < 110) cDim = cMain;
        int cCodeBg = Omega.mixColor(cMain, midlet.colorBg, 14);
        int y = chatTop - (pos % fh);
        for (int i = pos / fh; i < wrappedLines.size() && y < chatAreaBottom; i++) {
            int role = (i < lineRoles.size()) ? ((Integer) lineRoles.elementAt(i)).intValue() : 3;
            String ln = (String) wrappedLines.elementAt(i);
            if (role == 4) {
                // Кнопка [LOAD OLDER] / [BACK TO LATEST]: заливка и белый текст по центру
                g.setColor(midlet.colorFocusBg);
                g.fillRect(2, y, w - 4, fh);
                g.setColor(0xFFFFFF);
                g.drawString(ln, (w - font.stringWidth(ln)) / 2, y, Graphics.TOP | Graphics.LEFT);
            } else {
                byte[] sty = (i < lineStyles.size()) ? (byte[]) lineStyles.elementAt(i) : null;
                int base = (role == 0) ? cUser : ((role == 3) ? cSys : ((role == 6) ? cDim : cMain));
                if (role == 5) { g.setColor(cCodeBg); g.fillRect(0, y, w, fh); }
                if (sty == null || sty.length == 0) {
                    g.setColor(base);
                    g.drawString(ln, 4, y, Graphics.TOP | Graphics.LEFT);
                } else {
                    drawStyled(g, font, ln, sty, y, fh, base, cBold, cCode, cDim);
                }
            }
            y += fh;
        }
 
        int ha = hintAlpha();
        if (ha > 0) drawHint(g, font, w, chatTop, viewH, ha);
        drawScrollbar(g, w, chatTop, viewH);
        g.setClip(0, 0, w, h);
 
        inputTop = h - bottomBarHeight - inputAreaHeight - 2;
        inputBottom = inputTop + inputAreaHeight;
        g.setColor(midlet.colorSoftbarBg);
        g.fillRect(0, inputTop, w, inputAreaHeight);
        g.setColor(attachedImagePath != null ? midlet.colorWarn : midlet.colorMain);
 
        int inY = h - bottomBarHeight - inputAreaHeight + 2;
        for (int i = topLine; i < totalLines && (i - topLine) < maxVisibleInputLines; i++) {
            int s = ((Integer) inLineStarts.elementAt(i)).intValue();
            int e = (i + 1 < totalLines) ? ((Integer) inLineStarts.elementAt(i + 1)).intValue() : inText.length();
            while (e > s && (inText.charAt(e - 1) == '\n' || inText.charAt(e - 1) == '\r')) e--;
            g.drawString(inText.substring(s, e), 4, inY, Graphics.TOP | Graphics.LEFT);
            inY += fh;
        }
        // Метки, если часть введённого текста скрыта выше или ниже
        if (inTop > 0) g.drawString("^", w - 3, inputTop + 1, Graphics.TOP | Graphics.RIGHT);
        if (inTop + renderLinesCount < totalLines) g.drawString("v", w - 3, inputBottom - fh, Graphics.TOP | Graphics.RIGHT);
 
        // Курсор: подчёркивание под символом, мигает (во время ожидания горит постоянно)
        if ((cursorOn || isLoading) && cursorLine >= topLine && cursorLine < topLine + renderLinesCount) {
            int ls = ((Integer) inLineStarts.elementAt(cursorLine)).intValue();
            int cx = 4 + font.substringWidth(inText, ls, curIdx - ls);
            int cy = (h - bottomBarHeight - inputAreaHeight + 2) + (cursorLine - topLine) * fh;
            int cw = font.charWidth('_');
            if (curIdx < inText.length() && inText.charAt(curIdx) != '\n') {
                int cw2 = font.charWidth(inText.charAt(curIdx));
                if (cw2 >= 4) cw = cw2;
            }
            g.fillRect(cx, cy + fh - 3, cw, 2);
        }
        inLayoutText = inText;
        inPrefixLen = prefix.length();
        inVisStart = topLine;
        inY0 = h - bottomBarHeight - inputAreaHeight + 2;
 
        // SOFTBAR (Left: SEND/ADD, Right: CONFIG)
        g.setColor(midlet.colorSoftbarBg);
        g.fillRect(0, h - bottomBarHeight, w, bottomBarHeight);
        g.setColor(midlet.colorMain);
        g.drawLine(0, h - bottomBarHeight, w, h - bottomBarHeight);
 
        String leftBtnStr = fastMode ? "[EXIT FAST]" : "[MENU]";
        g.drawString(leftBtnStr, 10, h - 26, Graphics.TOP | Graphics.LEFT);
 
        String rightBtnStr = "[CONFIG]";
        g.drawString(rightBtnStr, w - font.stringWidth(rightBtnStr) - 10, h - 26, Graphics.TOP | Graphics.LEFT);
 
        if (menuOpen) drawMenu(g, font, w, h);
    }
 
 
    private void drawHint(Graphics g, Font font, int w, int chatTop, int viewH, int alpha) {
        int fh = font.getHeight();
        String l1 = "FIRE or TAP input:";
        String l2 = "full-screen. LEFT: menu";
        int tw = Math.max(font.stringWidth(l1), font.stringWidth(l2)) + 16;
        if (tw > w - 8) tw = w - 8;
        int th = fh * 2 + 12;
        int x = (w - tw) / 2;
        int y = chatTop + (viewH - th) / 2;
        int step = (alpha + 9) / 10;                 // 1..10
        if (hintPlate == null || hintPlateStep != step || hintPlateW != tw) {
            hintPlate = makeHintPlate(tw, th, step);
            hintPlateStep = step;
            hintPlateW = tw;
        }
        if (hintPlate != null) g.drawImage(hintPlate, x, y, Graphics.TOP | Graphics.LEFT);
        else if (step >= 5) { g.setColor(midlet.colorBg); g.fillRect(x, y, tw, th); }
        g.setColor(Omega.mixColor(midlet.colorMain, midlet.colorBg, alpha));
        g.drawRect(x, y, tw - 1, th - 1);
        g.drawString(l1, w / 2, y + 6, Graphics.TOP | Graphics.HCENTER);
        g.drawString(l2, w / 2, y + 6 + fh, Graphics.TOP | Graphics.HCENTER);
    }
 
    // Полупрозрачная плашка под подсказкой, чтобы текст чата не мешал читать её
    private Image makeHintPlate(int tw, int th, int step) {
        try {
            int a = step * 24;
            if (a > 235) a = 235;
            int argb = (a << 24) | (midlet.colorBg & 0xFFFFFF);
            int[] px = new int[tw * th];
            for (int i = 0; i < px.length; i++) px[i] = argb;
            return Image.createRGBImage(px, tw, th, true);
        } catch (Throwable t) {
            return null;
        }
    }
 
    // Делит текст на экранные строки, запоминая индекс начала каждой (пробелы остаются в конце предыдущей строки)
    private void layoutInput(String text, Font font, int maxW, Vector starts) {
        starts.removeAllElements();
        int len = text.length();
        if (len == 0) { starts.addElement(new Integer(0)); return; }
        int start = 0;
        while (start < len) {
            starts.addElement(new Integer(start));
            // Сколько символов влезает в строку (по таблице ширин: без вызовов шрифта на каждый символ)
            int w = 0, end = start, lastSp = -1;
            boolean hitNl = false;
            while (end < len) {
                char c = text.charAt(end);
                if (c == '\n') { hitNl = true; break; }
                int cwd = MdLayout.charW(font, c);
                if (w + cwd > maxW && end > start) break;
                w += cwd;
                if (c == ' ') lastSp = end;
                end++;
            }
            if (hitNl) { start = end + 1; continue; }     // перевод строки заканчивает строку
            if (end >= len) break;
            int next = end;
            if (text.charAt(end) != ' ' && text.charAt(end - 1) != ' ' && lastSp > start) {
                // Слово не поместилось: переносим его целиком, но только если оно влезет в строку само
                int wordEnd = text.indexOf(' ', lastSp + 1);
                int nlp = text.indexOf('\n', lastSp + 1);
                if (wordEnd == -1 || (nlp != -1 && nlp < wordEnd)) wordEnd = (nlp != -1) ? nlp : len;
                if (MdLayout.textW(font, text, lastSp + 1, wordEnd) <= maxW) next = lastSp + 1;
            }
            while (next < len && text.charAt(next) == ' ') next++;
            start = next;
        }
        if (text.charAt(len - 1) == '\n') starts.addElement(new Integer(len));
    }
 
    // Строка с разметкой: рисуется кусками с одинаковым стилем (цвет, подчёркивание)
    private void drawStyled(Graphics g, Font font, String ln, byte[] sty, int y, int fh, int base, int cBold, int cCode, int cDim) {
        int x = 4;
        int n = ln.length();
        int a = 0;
        while (a < n) {
            byte s = (a < sty.length) ? sty[a] : 0;
            int b = a + 1;
            while (b < n && ((b < sty.length) ? sty[b] : 0) == s) b++;
            int col = base;
            if ((s & MdLayout.S_CODE) != 0) col = cCode;
            else if ((s & (MdLayout.S_HEAD | MdLayout.S_BOLD)) != 0) col = cBold;
            else if ((s & MdLayout.S_QUOTE) != 0) col = cDim;
            int rw = MdLayout.textW(font, ln, a, b);
            g.setColor(col);
            g.drawSubstring(ln, a, b - a, x, y, Graphics.TOP | Graphics.LEFT);
            if ((s & (MdLayout.S_ITAL | MdLayout.S_HEAD | MdLayout.S_LINK)) != 0) {
                g.drawLine(x, y + fh - 2, x + rw - 1, y + fh - 2);
            }
            x += rw;
            a = b;
        }
    }

    private boolean moveCursorLine(int dir) {
        int total = inLineStarts.size();
        String text = inLayoutText;
        if (total < 2 || text == null) return false;
        int curIdx = inPrefixLen + cursorPos;
        int line = 0;
        for (int i = 0; i < total; i++) {
            if (((Integer) inLineStarts.elementAt(i)).intValue() <= curIdx) line = i;
        }
        int target = line + dir;
        if (target < 0 || target >= total) return false;
        int col = curIdx - ((Integer) inLineStarts.elementAt(line)).intValue();
        int ts = ((Integer) inLineStarts.elementAt(target)).intValue();
        int te = (target + 1 < total) ? ((Integer) inLineStarts.elementAt(target + 1)).intValue() : text.length();
        while (te > ts && (text.charAt(te - 1) == '\n' || text.charAt(te - 1) == '\r')) te--;
        int idx = ts + Math.min(col, te - ts);
        cursorPos = idx - inPrefixLen;
        if (cursorPos < 0) cursorPos = 0;
        if (cursorPos > currentInput.length()) cursorPos = currentInput.length();
        return true;
    }
 
    // Касание строки ввода: ставит курсор ближайшим к пальцу символом
    private void placeCursorAt(int x, int y) {
        int total = inLineStarts.size();
        if (total == 0) return;
        Font font = Omega.uiFont();
        int fh = font.getHeight();
        int line = (y < inY0) ? inVisStart : inVisStart + (y - inY0) / fh;
        if (line < 0) line = 0;
        if (line >= total) line = total - 1;
        String text = inLayoutText;
        int s = ((Integer) inLineStarts.elementAt(line)).intValue();
        int e = (line + 1 < total) ? ((Integer) inLineStarts.elementAt(line + 1)).intValue() : text.length();
        while (e > s && (text.charAt(e - 1) == '\n' || text.charAt(e - 1) == '\r')) e--;
        int idx = s;
        for (int i = s; i < e; i++) {
            int mid = 4 + (font.substringWidth(text, s, i - s) + font.substringWidth(text, s, i - s + 1)) / 2;
            if (x >= mid) idx = i + 1; else break;
        }
        cursorPos = idx - inPrefixLen;
        if (cursorPos < 0) cursorPos = 0;
        if (cursorPos > currentInput.length()) cursorPos = currentInput.length();
        wake();
    }
 
    // Показать курсор сразу после действия и перезапустить мигание
    private void wake() {
        inManual = false;          // курсор двигается: окно ввода снова следует за ним
        cursorOn = true;
        blinkTick = 0;
        repaint();
    }
 
    // Этап отправки для строки состояния (подключение, отправка, ожидание...)
    private void stage(String s) {
        loadingStatusText = s;
        repaint();
    }
 
    private String sizeStr(int bytes) {
        if (bytes < 1024) return bytes + "B";
        return (bytes / 1024) + "." + ((bytes % 1024) * 10 / 1024) + "KB";
    }
 
    private String hostOf(String url) {
        if (url == null) return "";
        int i = url.indexOf("://");
        String s = (i == -1) ? url : url.substring(i + 3);
        int sl = s.indexOf('/');
        if (sl != -1) s = s.substring(0, sl);
        return s;
    }
 
    // Показать ошибку в чате и снять индикатор ожидания
    private void failLoading(final String msg) {
        display.callSerially(new Runnable() {
            public void run() {
                isLoading = false;
                loadingStatusText = "[...]";
                wrapTextIntoVector(msg, Omega.uiFont(), getWidth() - 6, wrappedLines);
                repaint();
            }
        });
    }
 
    private void insertChar(char ch) {
        synchronized (currentInput) {
            if (currentInput.length() >= 2000) return;
            if (cursorPos > currentInput.length()) cursorPos = currentInput.length();
            if (cursorPos < 0) cursorPos = 0;
            currentInput.insert(cursorPos, ch);
            cursorPos++;
        }
        wake();
    }
 
    // Стирание перед курсором; при долгом удержании стирает быстрее
    private void backspaceKey(boolean repeat) {
        synchronized (currentInput) {
            int n = 1;
            if (repeat) {
                repCount++;
                if (repCount > 12) n = 2;
                if (repCount > 30) n = 4;
            }
            if (cursorPos > currentInput.length()) cursorPos = currentInput.length();
            if (currentInput.length() == 0) {
                if (!repeat && attachedImagePath != null) attachedImagePath = null;
            } else {
                String s = currentInput.toString();
                int from = cursorPos - n;
                if (from < 0) from = 0;
                if (cursorPos > 0) {
                    currentInput.setLength(0);
                    currentInput.append(s.substring(0, from)).append(s.substring(cursorPos));
                    cursorPos = from;
                }
            }
        }
        wake();
    }
 
    private boolean isRepeatable(int keyCode) {
        return !(keyCode == -5 || keyCode == -6 || keyCode == -7 || keyCode == 10 || keyCode == 13);
    }
 
    // Если телефон сам не присылает повторы клавиш, повторяем их программно
    private void startRepeater(final int keyCode) {
        final int token = ++repToken;
        new Thread(new Runnable() {
            public void run() {
                try { Thread.sleep(450); } catch (Exception e) {}
                while (repToken == token && isShown()) {
                    handleKey(keyCode, true);
                    try { Thread.sleep(80); } catch (Exception e) {}
                }
            }
        }).start();
    }
 
    private void handleKey(int keyCode, boolean repeat) {
        int ga = 0;
        try { ga = getGameAction(keyCode); } catch (Exception e) {}
 
        // Режим быстрой прокрутки: курсор и ввод заморожены
        if (fastMode) { fastKey(keyCode, ga, repeat); return; }
 
        // Открыто меню: все клавиши идут в него
        if (menuOpen) { menuKey(keyCode, ga, repeat); return; }
 
        if (keyCode == -6) { // Left softkey -> меню чата (Send, Add image, ...)
            if (repeat) return;
            if (!isLoading) openMenu();
            return;
        }
        if (keyCode == -7) { // Right softkey -> CONFIG
            if (repeat) return;
            if (isLoading) { stopLoading(); return; }
            midlet.switchToConfig();
            return;
        }
 
        if (isLoading) {
            // Во время потокового ответа чат можно листать
            if (streaming && keyCode < 0 && (ga == UP || ga == DOWN)) {
                followStream = false;
                scroller.glideBy(ga == UP ? -Omega.uiFont().getHeight() : Omega.uiFont().getHeight());
                repaint();
            }
            return;
        }
 
        // Стрелки и джойстик: только настоящие навигационные коды (отрицательные),
        // чтобы буквы w a s d и цифры можно было печатать
        boolean nav = keyCode < 0 && (ga == UP || ga == DOWN || ga == LEFT || ga == RIGHT);
 
        if (nav && ga == UP && moveCursorLine(-1)) {
            wake();
        } else if (nav && ga == UP) {
            // Вверху списка ещё один нажим вверх подгружает более старые сообщения
            if (loadMoreLine >= 0 && scroller.getPos() <= 0) {
                if (!repeat) loadMoreMessages();
            } else {
                scroller.glideBy(-Omega.uiFont().getHeight());
                repaint();
            }
        } else if (nav && ga == DOWN && moveCursorLine(1)) {
            wake();
        } else if (nav && ga == DOWN) {
            // Внизу развёрнутой истории ещё один нажим вниз возвращает к последним сообщениям
            if (backLine >= 0 && atBottom()) {
                if (!repeat) backToLatest();
            } else {
                scroller.glideBy(Omega.uiFont().getHeight());
                repaint();
            }
        } else if (nav && ga == LEFT) {
            if (cursorPos > currentInput.length()) cursorPos = currentInput.length();
            if (cursorPos > 0) cursorPos--;
            wake();
        } else if (nav && ga == RIGHT) {
            if (cursorPos < currentInput.length()) cursorPos++;
            wake();
        } else if (keyCode == -8 || keyCode == 8 || keyCode == 127) {
            backspaceKey(repeat);
        } else if (keyCode == -5) {
            // FIRE (центр джойстика) открывает полноэкранный ввод
            if (!repeat) openFullTextBox();
        } else if (keyCode == 10 || keyCode == 13) {
            // Enter вставляет перенос строки
            if (!repeat) insertChar('\n');
        } else if (keyCode >= 32) {
            insertChar((char) keyCode);
        }
    }

    private void openFullTextBox() {
        final TextBox tb = new TextBox("Prompt", currentInput.toString(), 2048, TextField.ANY);
        tb.addCommand(new Command("OK", Command.OK, 1));
        tb.addCommand(new Command("Cancel", Command.CANCEL, 2));
        tb.setCommandListener(new CommandListener() {
            public void commandAction(Command c, Displayable d) {
                if (c.getLabel().equals("OK")) {
                    currentInput.setLength(0);
                    String s = tb.getString();
                    if (s != null) currentInput.append(s);
                    cursorPos = currentInput.length();
                }
                display.setCurrent(TerminalCanvas.this);
            }
        });
        display.setCurrent(tb);
    }

    // ===== Меню чата (левая софт-клавиша), перегенерация с вариантами ответа, правка последнего вопроса, поиск =====
 
    // Раскрывает старые чаты с пробелами после двоеточий и возвращает историю в компактном виде
    private String loadChatCompact() {
        String json = loadChatFromRms(chatName);
        if (json.indexOf("\"role\": \"") != -1) json = normalizeChatJson(json);
        return json;
    }
 
    // Позиции начал сообщений в истории (по всему чату, не только в окне)
    private Vector chatStarts(String json) {
        String key = "{\"role\":\"";
        Vector starts = new Vector();
        int sp = 0;
        while (true) {
            int i = json.indexOf(key, sp);
            if (i == -1) break;
            starts.addElement(new Integer(i));
            sp = i + key.length();
        }
        return starts;
    }
 
    private boolean chatMsgIsUser(String json, Vector starts, int i) {
        return json.startsWith("user", ((Integer) starts.elementAt(i)).intValue() + 9);
    }
 
    private String chatMsgContent(String json, Vector starts, int i) {
        int n = starts.size();
        int a = ((Integer) starts.elementAt(i)).intValue();
        int jsonEnd = json.lastIndexOf(']');
        if (jsonEnd == -1) jsonEnd = json.length();
        int b = (i + 1 < n) ? ((Integer) starts.elementAt(i + 1)).intValue() - 1 : jsonEnd;
        if (b <= a) return "";
        String obj = json.substring(a, b);
        int rEnd = obj.indexOf('"', 9);
        if (rEnd == -1) return "";
        int cIdx = obj.indexOf("\"content\":\"", rEnd);
        if (cIdx == -1) return "";
        int cs = cIdx + 11;
        int ce = obj.endsWith("\"}") ? obj.length() - 2 : findJsonStringEnd(obj, cs);
        if (ce < cs) ce = cs;
        return unescapeString(obj.substring(cs, ce));
    }
 
    // История, обрезанная до первых keep сообщений
    private String chatCut(String json, Vector starts, int keep) {
        if (keep <= 0) return "[]";
        if (keep >= starts.size()) return json;
        return json.substring(0, ((Integer) starts.elementAt(keep)).intValue() - 1) + "]";
    }
 
    // Номер сообщения в окне по номеру строки экрана, -1 если строка вне сообщений
    private int messageAtLine(int line) {
        int r = -1;
        for (int k = 0; k < msgLineStart.size(); k++) {
            if (((Integer) msgLineStart.elementAt(k)).intValue() <= line) r = k; else break;
        }
        return r;
    }
 
    private void sysNote(String s) {
        wrapTextIntoVector("Sys: " + s, Omega.uiFont(), getWidth() - 6, wrappedLines);
        midlet.safeSetCurrent(TerminalCanvas.this);
        repaint();
    }
 
    private void openSearchBox() {
        final TextBox tb = new TextBox("Search messages", "", 64, TextField.ANY);
        tb.addCommand(new Command("OK", Command.OK, 1));
        tb.addCommand(new Command("Cancel", Command.CANCEL, 2));
        tb.setCommandListener(new CommandListener() {
            public void commandAction(Command cmd, Displayable d) {
                String q = tb.getString() == null ? "" : tb.getString().trim();
                if (!cmd.getLabel().equals("OK") || q.length() == 0) { display.setCurrent(TerminalCanvas.this); return; }
                runSearch(q);
            }
        });
        display.setCurrent(tb);
    }
 
    // Ищет текст в сообщениях всей переписки (новые сверху, до 30 совпадений) и показывает список
    private void runSearch(String q) {
        String json = loadChatCompact();
        Vector st = chatStarts(json);
        String ql = q.toLowerCase();
        final Vector idx = new Vector();
        Vector items = new Vector();
        for (int i = st.size() - 1; i >= 0 && idx.size() < 30; i--) {
            String c = chatMsgContent(json, st, i).replace('\n', ' ');
            int p = c.toLowerCase().indexOf(ql);
            if (p == -1) continue;
            int from = p - 12;
            if (from < 0) from = 0;
            int to = p + q.length() + 28;
            if (to > c.length()) to = c.length();
            items.addElement((chatMsgIsUser(json, st, i) ? "U: " : "A: ") + (from > 0 ? ".." : "") + c.substring(from, to));
            idx.addElement(new Integer(i));
        }
        String title = (idx.size() == 0) ? "Nothing found" : "Found " + idx.size();
        if (idx.size() == 0) items.addElement("[BACK]");
        midlet.openSelectionMenu(title, items, 0, new SelectCallback() {
            public void onSelected(int index, String value) {
                if (index >= 0 && index < idx.size()) jumpToMessage(((Integer) idx.elementAt(index)).intValue());
                else midlet.safeSetCurrent(TerminalCanvas.this);
            }
        }, new Runnable() { public void run() { midlet.safeSetCurrent(TerminalCanvas.this); } });
    }
 
    // Показывает сообщение с номером abs в начале экрана (подгружает старые, если надо)
    private void jumpToMessage(int abs) {
        int n = chatStarts(loadChatCompact()).size();
        int need = n - abs;
        if (need < PAGE_MSGS) need = PAGE_MSGS;
        shownMsgs = need;
        rebuildWrappedLines();
        for (int k = 0; k < msgAbsIdx.size(); k++) {
            if (((Integer) msgAbsIdx.elementAt(k)).intValue() == abs) {
                scroller.jump(((Integer) msgLineStart.elementAt(k)).intValue() * Omega.uiFont().getHeight());
                break;
            }
        }
        midlet.safeSetCurrent(TerminalCanvas.this);
        repaint();
    }
 
    // ----- Служебное хранилище (RMS) для вариантов ответа и путей к картинкам -----
    private String readRms(String name) {
        RecordStore rs = null;
        try {
            rs = RecordStore.openRecordStore(name, false);
            if (rs.getNumRecords() > 0) return new String(rs.getRecord(1), "UTF-8");
        } catch (Exception e) {}
        finally { if (rs != null) try { rs.closeRecordStore(); } catch (Exception e) {} }
        return null;
    }
 
    private void writeRms(String name, String data) {
        RecordStore rs = null;
        try { RecordStore.deleteRecordStore(name); } catch (Exception e) {}
        try {
            rs = RecordStore.openRecordStore(name, true);
            byte[] b = data.getBytes("UTF-8");
            rs.addRecord(b, 0, b.length);
        } catch (Exception e) {}
        finally { if (rs != null) try { rs.closeRecordStore(); } catch (Exception e) {} }
    }
 
    private void deleteRms(String name) {
        try { RecordStore.deleteRecordStore(name); } catch (Exception e) {}
    }
 
    // Запоминает путь к картинке, отправленной в сообщении с номером idx (для повторной отправки)
    private void rememberImage(String chat, String path, int idx) {
        if (chat == null || path == null || chat.equals("temp_new_chat")) return;
        writeRms("OM_IMG_" + chat, idx + "\n" + path);
    }
 
    private String imagePathFor(int idx) {
        String d = readRms("OM_IMG_" + chatName);
        if (d == null) return null;
        int nl = d.indexOf('\n');
        if (nl == -1 || !d.substring(0, nl).equals(String.valueOf(idx))) return null;
        return d.substring(nl + 1);
    }
 
    private boolean imageExists(String path) {
        FileConnection fc = null;
        try {
            fc = (FileConnection) Connector.open(path, Connector.READ);
            return fc.exists();
        } catch (Exception e) {
            return false;
        } finally {
            if (fc != null) try { fc.close(); } catch (Exception e) {}
        }
    }
 
    // Предупреждение перед повторной загрузкой картинки: Continue запускает действие
    private void imageWarning(final Runnable yes) {
        Form f = new Form("Attention");
        f.append(new StringItem(null, "Attention! Your image will be uploaded again, which may affect your traffic consumption. Continue?"));
        final Command ok = new Command("Continue", Command.OK, 1);
        final Command no = new Command("Cancel", Command.CANCEL, 2);
        f.addCommand(ok);
        f.addCommand(no);
        f.setCommandListener(new CommandListener() {
            public void commandAction(Command c, Displayable d) {
                display.setCurrent(TerminalCanvas.this);
                if (c == ok) yes.run();
            }
        });
        display.setCurrent(f);
    }
 
    // ----- Варианты ответа -----
    // Прошлые ответы на последний вопрос хранятся отдельно от чата. Запись действует, пока последний вопрос
    // и число сообщений в чате те же; после нового сообщения запись сама перестаёт подходить.
    private String altStoreName() { return "OM_ALT_" + chatName; }
 
    private String altKey(String json, Vector st, int n) {
        return n + "," + chatMsgContent(json, st, n - 2).hashCode();
    }
 
    private boolean hasLastPair(String json, Vector st) {
        int n = st.size();
        return n >= 2 && !chatMsgIsUser(json, st, n - 1) && chatMsgIsUser(json, st, n - 2);
    }
 
    private String lastAnswerObj(String json, Vector st) {
        int end = json.lastIndexOf(']');
        if (end == -1) end = json.length();
        return json.substring(((Integer) st.elementAt(st.size() - 1)).intValue(), end);
    }
 
    private Vector splitObjs(String arr) {
        Vector out = new Vector();
        Vector st = chatStarts(arr);
        int end = arr.lastIndexOf(']');
        if (end == -1) end = arr.length();
        for (int i = 0; i < st.size(); i++) {
            int a = ((Integer) st.elementAt(i)).intValue();
            int b = (i + 1 < st.size()) ? ((Integer) st.elementAt(i + 1)).intValue() - 1 : end;
            if (b > a) out.addElement(arr.substring(a, b));
        }
        return out;
    }
 
    // Другие варианты ответа на последний вопрос (пусто, если их нет или запись устарела)
    private Vector altObjs(String json, Vector st) {
        if (!hasLastPair(json, st)) return new Vector();
        String data = readRms(altStoreName());
        if (data == null) return new Vector();
        int nl = data.indexOf('\n');
        if (nl == -1 || !data.substring(0, nl).equals(altKey(json, st, st.size()))) return new Vector();
        return splitObjs(data.substring(nl + 1));
    }
 
    private void saveAlts(String key, Vector objs) {
        if (objs.size() == 0) { deleteRms(altStoreName()); return; }
        StringBuffer sb = new StringBuffer(key);
        sb.append("\n[");
        for (int i = 0; i < objs.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append((String) objs.elementAt(i));
        }
        sb.append(']');
        writeRms(altStoreName(), sb.toString());
    }
 
    // Показывает на месте последнего ответа другой вариант: dir 1 - следующий, -1 - предыдущий (по кругу)
    private void switchVariant(int dir) {
        if (isLoading) return;
        String json = loadChatCompact();
        Vector st = chatStarts(json);
        Vector alts = altObjs(json, st);
        if (alts.size() == 0) { sysNote("no other answers"); return; }
        int n = st.size();
        String cur = lastAnswerObj(json, st);
        String pick;
        if (dir > 0) {
            pick = (String) alts.elementAt(0);
            alts.removeElementAt(0);
            alts.addElement(cur);
        } else {
            pick = (String) alts.elementAt(alts.size() - 1);
            alts.removeElementAt(alts.size() - 1);
            alts.insertElementAt(cur, 0);
        }
        String key = altKey(json, st, n);
        int head = ((Integer) st.elementAt(n - 1)).intValue();
        saveChatToRms(chatName, json.substring(0, head) + pick + "]");
        saveAlts(key, alts);
        rebuildWrappedLines();
        if (msgLineStart.size() > 0) {
            scroller.jump(((Integer) msgLineStart.elementAt(msgLineStart.size() - 1)).intValue() * Omega.uiFont().getHeight());
        }
        repaint();
    }
 
    // ----- Перегенерация и правка -----
    // Обрезает историю до keep сообщений и отправляет text как новый вопрос (с картинкой, если imgPath не null).
    // Набранный черновик и прикреплённая картинка сохраняются
    private void resendFrom(int keep, String text, String imgPath) {
        if (isLoading || text == null) return;
        if (text.trim().length() == 0 && imgPath == null) return;
        String json = loadChatCompact();
        Vector st = chatStarts(json);
        saveChatToRms(chatName, chatCut(json, st, keep));
 
        String draft = currentInput.toString();
        int draftCur = cursorPos;
        String img = attachedImagePath;
        attachedImagePath = imgPath;
        currentInput.setLength(0);
        currentInput.append(text);
        cursorPos = currentInput.length();
 
        rebuildWrappedLines();                 // на экране уже обрезанная история
        scroller.jumpToEnd();
        triggerSend();
 
        currentInput.setLength(0);
        currentInput.append(draft);
        cursorPos = (draftCur > draft.length()) ? draft.length() : draftCur;
        attachedImagePath = img;
        wake();
    }
 
    // Новый ответ на последний вопрос. Прежний ответ остаётся в вариантах, между ними можно переключаться
    private void regenerate() {
        if (isLoading) return;
        String json = loadChatCompact();
        Vector st = chatStarts(json);
        if (!hasLastPair(json, st)) { sysNote("nothing to regenerate"); return; }
        final int n = st.size();
        String q = chatMsgContent(json, st, n - 2);
        final String key = altKey(json, st, n);
        final Vector alts = altObjs(json, st);
        alts.addElement(lastAnswerObj(json, st));
        while (alts.size() > 5) alts.removeElementAt(0);
 
        final String text;
        final String img;
        if (q.startsWith("[IMG] ")) {
            text = q.substring(6);
            img = imagePathFor(n - 2);
            if (img == null || !imageExists(img)) { sysNote("image file not found, attach it again"); return; }
        } else {
            text = q;
            img = null;
        }
        Runnable go = new Runnable() {
            public void run() {
                saveAlts(key, alts);
                resendFrom(n - 2, text, img);
            }
        };
        if (img != null) imageWarning(go); else go.run();
    }
 
    // Правка последнего вопроса: его ответ заменяется новым (варианты прежнего ответа сбрасываются)
    private void editLast() {
        if (isLoading) return;
        String json = loadChatCompact();
        Vector st = chatStarts(json);
        int n = st.size();
        final int abs;
        if (hasLastPair(json, st)) abs = n - 2;
        else if (n >= 1 && chatMsgIsUser(json, st, n - 1)) abs = n - 1;
        else { sysNote("nothing to edit"); return; }
        String c = chatMsgContent(json, st, abs);
        final String img;
        if (c.startsWith("[IMG] ")) {
            c = c.substring(6);
            img = imagePathFor(abs);
            if (img == null || !imageExists(img)) { sysNote("image file not found, attach it again"); return; }
        } else {
            img = null;
        }
        if (c.length() > 2048) c = c.substring(0, 2048);
 
        final TextBox tb = new TextBox("Edit last message", c, 2048, TextField.ANY);
        tb.addCommand(new Command("OK", Command.OK, 1));
        tb.addCommand(new Command("Cancel", Command.CANCEL, 2));
        tb.setCommandListener(new CommandListener() {
            public void commandAction(Command cmd, Displayable d) {
                String s = tb.getString() == null ? "" : tb.getString().trim();
                if (!cmd.getLabel().equals("OK") || (s.length() == 0 && img == null)) { display.setCurrent(TerminalCanvas.this); return; }
                final String text = s;
                Runnable go = new Runnable() {
                    public void run() {
                        deleteRms(altStoreName());
                        resendFrom(abs, text, img);
                    }
                };
                if (img != null) imageWarning(go);
                else { display.setCurrent(TerminalCanvas.this); go.run(); }
            }
        });
        display.setCurrent(tb);
    }

        // ----- Полоса прокрутки и быстрая прокрутка -----
    // Плавно меняет ширину полосы: широкая во время касания, в режиме быстрой прокрутки и ещё пару секунд после,
    // потом сворачивается до двух пикселей. Возвращает true, пока идёт анимация (нужна перерисовка)
    private boolean sbStep() {
        boolean want = sbWant || sbDrag || fastMode || System.currentTimeMillis() < sbHold;
        int target = want ? SB_WIDE : 2;
        if (sbW == target) return false;
        if (sbW < target) { sbW += 3; if (sbW > target) sbW = target; }
        else { sbW -= 2; if (sbW < target) sbW = target; }
        return true;
    }
 
    private int sbThumbH(int viewH) {
        int th = (viewH * viewH) / (sbMax + viewH);
        if (th < 14) th = 14;
        if (th > viewH) th = viewH;
        return th;
    }
 
    private void drawScrollbar(Graphics g, int w, int top, int viewH) {
        if (sbMax <= 0 || viewH < 24) return;
        int th = sbThumbH(viewH);
        int ty = top + (int) (((long) (viewH - th) * scroller.getPos()) / sbMax);
        int bw = sbW;
        int x = w - bw;
        if (bw > 4) {
            // Широкая полоса: дорожка с рамкой и ползунок
            g.setColor(midlet.colorSoftbarBg);
            g.fillRect(x, top, bw, viewH);
            g.setColor(Omega.mixColor(midlet.colorMain, midlet.colorBg, 62));
            g.drawLine(x, top, x, top + viewH - 1);
            g.setColor(midlet.colorMain);
            g.fillRect(x + 3, ty + 1, bw - 5, th - 2);
        } else {
            // Свёрнутая: два пикселя, только ползунок
            int c = Omega.mixColor(midlet.colorMain, midlet.colorBg, 62);
            if (Omega.colorDistance(c, midlet.colorBg) < 110) c = midlet.colorMain;
            g.setColor(c);
            g.fillRect(x, ty, bw, th);
        }
    }
 
    // Ставит позицию чата по положению пальца на полосе
    private void sbSetFromY(int y) {
        int viewH = chatBotY - chatTopY;
        if (sbMax <= 0 || viewH < 24) return;
        int th = sbThumbH(viewH);
        int range = viewH - th;
        if (range <= 0) return;
        int rel = y - chatTopY - th / 2;
        if (rel < 0) rel = 0;
        if (rel > range) rel = range;
        followStream = false;
        scroller.jump((int) (((long) rel * sbMax) / range));
        repaint();
    }
 
    private void enterFast() {
        fastMode = true;
        sbWant = true;
        repaint();
    }
 
    private void exitFast() {
        fastMode = false;
        sbWant = false;
        sbHold = System.currentTimeMillis() + 1500;
        wake();
    }
 
    // В режиме быстрой прокрутки: вверх и вниз прыгают к началу предыдущего и следующего сообщения,
    // джойстик влево, обе софт-клавиши выходят. Курсор и ввод заморожены
    private void fastKey(int keyCode, int ga, boolean repeat) {
        boolean nav = keyCode < 0;
        if (keyCode == -6 || keyCode == -7 || (nav && ga == LEFT)) {
            if (!repeat) exitFast();
            return;
        }
        if (nav && ga == UP) fastJump(-1);
        else if (nav && ga == DOWN) fastJump(1);
    }
 
    private void fastJump(int dir) {
        int fh = Omega.uiFont().getHeight();
        int pos = scroller.getPos();
        followStream = false;
        if (dir > 0) {
            for (int k = 0; k < msgLineStart.size(); k++) {
                int s = ((Integer) msgLineStart.elementAt(k)).intValue() * fh;
                if (s > pos) { scroller.jump(s); repaint(); return; }
            }
            if (backLine >= 0) { backToLatest(); return; }
            scroller.jumpToEnd();
            repaint();
        } else {
            for (int k = msgLineStart.size() - 1; k >= 0; k--) {
                int s = ((Integer) msgLineStart.elementAt(k)).intValue() * fh;
                if (s < pos) { scroller.jump(s); repaint(); return; }
            }
            if (loadMoreLine >= 0) { loadMoreMessages(); return; }
            scroller.jump(0);
            repaint();
        }
    }
 
    // ----- Меню чата на левой софт-клавише -----
    private void addMenuItem(String label, int act) {
        menuActs[menuLabels.size()] = act;
        menuLabels.addElement(label);
    }
 
    private void openMenu() {
        if (isLoading) return;
        menuLabels.removeAllElements();
        if (currentInput.length() > 0 || attachedImagePath != null) addMenuItem("Send", A_SEND);
        addMenuItem(attachedImagePath != null ? "Change image" : "Add image", A_ADD);
        addMenuItem("Full-screen input", A_FULL);
        String json = loadChatCompact();
        Vector st = chatStarts(json);
        if (hasLastPair(json, st)) {
            addMenuItem("Regenerate answer", A_REGEN);
            int k = altObjs(json, st).size();
            if (k == 1) {
                addMenuItem("Other answer", A_NEXT);
            } else if (k > 1) {
                addMenuItem("Next answer (" + k + ")", A_NEXT);
                addMenuItem("Prev answer (" + k + ")", A_PREV);
            }
        }
        if (st.size() > 0) addMenuItem("Edit last message", A_EDIT);
        if (st.size() > 0) addMenuItem("Search messages", A_SEARCH);
        addMenuItem("Fast scroll", A_FAST);
        menuSel = 0;
        menuScroll = 0;
        menuOpen = true;
        repaint();
    }
 
    private void closeMenu() {
        menuOpen = false;
        repaint();
    }
 
    private void menuRun(int idx) {
        if (idx < 0 || idx >= menuLabels.size()) return;
        int act = menuActs[idx];
        closeMenu();
        if (act == A_SEND) triggerSend();
        else if (act == A_ADD) openFileBrowser("file:///");
        else if (act == A_FULL) openFullTextBox();
        else if (act == A_REGEN) regenerate();
        else if (act == A_NEXT) switchVariant(1);
        else if (act == A_PREV) switchVariant(-1);
        else if (act == A_EDIT) editLast();
        else if (act == A_SEARCH) openSearchBox();
        else if (act == A_FAST) enterFast();
    }
 
    private void menuKey(int keyCode, int ga, boolean repeat) {
        int n = menuLabels.size();
        if (n == 0) { closeMenu(); return; }
        if (keyCode == -7) { if (!repeat) closeMenu(); return; }                       // правая софт-клавиша: назад
        if (keyCode == -5 || keyCode == -6 || keyCode == 10 || keyCode == 13) {        // выбрать
            if (!repeat) menuRun(menuSel);
            return;
        }
        if (keyCode < 0 && ga == UP) menuSel = (menuSel + n - 1) % n;
        else if (keyCode < 0 && ga == DOWN) menuSel = (menuSel + 1) % n;
        else if (keyCode >= '1' && keyCode <= '9') {                                   // цифра выбирает пункт сразу
            if (!repeat && keyCode - '1' < n) menuRun(keyCode - '1');
            return;
        } else return;
        int rows = (menuRows > 0) ? menuRows : n;
        if (menuSel < menuScroll) menuScroll = menuSel;
        if (menuSel >= menuScroll + rows) menuScroll = menuSel - rows + 1;
        repaint();
    }
 
    // Касание при открытом меню: пункт выбирается, софтбар работает как [SELECT] / [BACK], остальное закрывает меню
    private void menuPointer(int x, int y) {
        int w = getWidth();
        int h = getHeight();
        if (y > h - 35) {
            if (x < w / 2) menuRun(menuSel); else closeMenu();
            return;
        }
        if (menuIH > 0 && x >= menuPX && x < menuPX + menuPW && y >= menuPY && y < menuPY + menuRows * menuIH) {
            int i = menuScroll + (y - menuPY) / menuIH;
            if (i < menuLabels.size()) { menuSel = i; menuRun(i); return; }
        }
        closeMenu();
    }
 
    // Плотный шахматный узор: чёрные и прозрачные клетки. Рисуется плиткой из одной маленькой картинки
    private void drawChecker(Graphics g, int w, int h) {
        if (checkerTile == null && !checkerFailed) {
            try {
                int n = 16;
                int[] px = new int[n * n];
                for (int yy = 0; yy < n; yy++) {
                    for (int xx = 0; xx < n; xx++) {
                        px[yy * n + xx] = (((xx / CHECK_CELL) + (yy / CHECK_CELL)) % 2 == 0) ? 0xFF000000 : 0x00000000;
                    }
                }
                checkerTile = Image.createRGBImage(px, n, n, true);
            } catch (Throwable t) {
                checkerFailed = true;
            }
        }
        if (checkerTile != null) {
            for (int yy = 0; yy < h; yy += 16) {
                for (int xx = 0; xx < w; xx += 16) g.drawImage(checkerTile, xx, yy, Graphics.TOP | Graphics.LEFT);
            }
        } else {
            // Если телефон не умеет прозрачность: чёрные полосы через строку
            g.setColor(0x000000);
            for (int yy = 0; yy < h; yy += CHECK_CELL * 2) g.fillRect(0, yy, w, CHECK_CELL);
        }
    }
 
    // Меню: слева внизу, над софтбаром. Всё остальное закрыто узором
    private void drawMenu(Graphics g, Font font, int w, int h) {
        g.setClip(0, 0, w, h);
        drawChecker(g, w, h);
 
        int fh = font.getHeight();
        int ih = fh + 6;
        int n = menuLabels.size();
        int maxRows = (h - 35 - 10) / ih;
        if (maxRows < 1) maxRows = 1;
        int rows = (n < maxRows) ? n : maxRows;
        int pw = 90;
        for (int i = 0; i < n; i++) {
            int tw = font.stringWidth((String) menuLabels.elementAt(i)) + 24;
            if (tw > pw) pw = tw;
        }
        if (pw > w - 6) pw = w - 6;
        int px = 3;
        int py = h - 35 - 4 - rows * ih;
        menuPX = px; menuPY = py; menuPW = pw; menuIH = ih; menuRows = rows;
        if (menuSel < menuScroll) menuScroll = menuSel;
        if (menuSel >= menuScroll + rows) menuScroll = menuSel - rows + 1;
        if (menuScroll < 0) menuScroll = 0;
 
        // Софтбар: подписи меню
        g.setColor(midlet.colorSoftbarBg);
        g.fillRect(0, h - 35, w, 35);
        g.setColor(midlet.colorMain);
        g.drawLine(0, h - 35, w, h - 35);
        g.setFont(font);
        g.drawString("[SELECT]", 10, h - 26, Graphics.TOP | Graphics.LEFT);
        String back = "[BACK]";
        g.drawString(back, w - font.stringWidth(back) - 10, h - 26, Graphics.TOP | Graphics.LEFT);
 
        // Панель пунктов
        g.setColor(midlet.colorBg);
        g.fillRect(px, py, pw, rows * ih);
        for (int i = 0; i < rows; i++) {
            int idx = menuScroll + i;
            int ry = py + i * ih;
            if (idx == menuSel) {
                g.setColor(midlet.colorFocusBg);
                g.fillRect(px + 1, ry, pw - 2, ih);
                g.setColor(0xFFFFFF);
            } else {
                g.setColor(midlet.colorMain);
            }
            g.drawString((String) menuLabels.elementAt(idx), px + 8, ry + 3, Graphics.TOP | Graphics.LEFT);
        }
        g.setColor(midlet.colorMain);
        g.drawRect(px, py, pw - 1, rows * ih - 1);
        if (menuScroll > 0) g.drawString("^", px + pw - 4, py + 1, Graphics.TOP | Graphics.RIGHT);
        if (menuScroll + rows < n) g.drawString("v", px + pw - 4, py + rows * ih - fh - 1, Graphics.TOP | Graphics.RIGHT);
    }

    protected void keyPressed(int keyCode) {
        repCount = 0;
        handleKey(keyCode, false);
        if (!hasRepeatEvents() && isRepeatable(keyCode)) startRepeater(keyCode);
    }
 
    protected void keyRepeated(int keyCode) {
        handleKey(keyCode, true);
    }
 
    protected void keyReleased(int keyCode) {
        repToken++;
    }
 
    protected void pointerPressed(int x, int y) {
        if (menuOpen) { pressInBar = false; return; }      // меню открыто: касание обработается при отпускании
        // Касание, начавшееся на софтбаре, скроллом не считается
        if (y > getHeight() - 35) { pressInBar = true; return; }
        pressInBar = false;
        // Касание у правого края открывает полосу прокрутки и ведёт ползунок
        if (x >= getWidth() - SB_HIT && y >= chatTopY && y < chatBotY && sbMax > 0) {
            sbDrag = true;
            sbSetFromY(y);
            return;
        }
        sbWant = false;
        sbHold = 0;
        // Зона листания ввода шире самого поля: на строку вверх и немного вниз
        int fhp = Omega.uiFont().getHeight();
        if (!isLoading && y >= inputTop - fhp && y < inputBottom + 6 && inLineStarts.size() > 3) {
            inDrag = true;
            inDragMoved = false;
            inDragY = y;
            return;
        }
        inDrag = false;
        followStream = false;
        scroller.press(y);
    }
 
 
    protected void pointerDragged(int x, int y) {
        if (menuOpen) return;
        if (sbDrag) { sbSetFromY(y); return; }
        if (pressInBar) return;
        if (inDrag) {
            int sh = Omega.uiFont().getHeight() * 2 / 3;      // порог 2/3 строки: отзывчивее
            if (sh < 6) sh = 6;
            int steps = (inDragY - y) / sh;
            if (steps != 0) {
                int maxTop = inLineStarts.size() - 3;
                inTop += steps;
                if (inTop > maxTop) inTop = maxTop;
                if (inTop < 0) inTop = 0;
                inDragY -= steps * sh;
                inDragMoved = true;
                inManual = true;
                repaint();
            }
            return;
        }
        scroller.drag(y);
    }
 
 
    protected void pointerReleased(int x, int y) {
        int w = getWidth();
        int h = getHeight();
        boolean inBar = y > h - 35;
        if (menuOpen) { menuPointer(x, y); return; }
        if (sbDrag) {
            sbDrag = false;
            sbHold = System.currentTimeMillis() + 2000;     // широкая полоса остаётся ещё пару секунд
            repaint();
            return;
        }
 
        // Кнопки софтбара срабатывают, только если касание и началось, и закончилось на нём
        if (pressInBar) {
            pressInBar = false;
            if (inBar) {
                if (fastMode) { exitFast(); }                 // в режиме быстрой прокрутки обе кнопки выходят из него
                else if (x < w / 2) { // Left: меню чата
                    if (!isLoading) openMenu();
                } else { // Right: CONFIG
                    if (isLoading) { stopLoading(); }
                    else { midlet.switchToConfig(); }
                }
            }
            return;
        }
 
        // Палец вёл по полю ввода: если текст не листали, это тап и он ставит курсор
        if (inDrag) {
            inDrag = false;
            if (!inDragMoved && !isLoading) placeCursorAt(x, y);
            return;
        }
        // Скролл или остановка инерции - это не тап
        if (scroller.release()) { repaint(); return; }
        if (inBar) return;
        // Тап по строке ввода ставит курсор, а полноэкранный ввод открывается тапом по остальному экрану
        if (y >= inputTop && y < inputBottom) {
            if (!isLoading) {
                // Пустое поле: тап открывает полноэкранный ввод, иначе ставит курсор
                if (currentInput.length() == 0 && attachedImagePath == null) openFullTextBox();
                else placeCursorAt(x, y);
            }
            return;
        }
        // Тап по шапке: поиск по сообщениям
        if (y < chatTopY) { if (!isLoading) openSearchBox(); return; }
        // Тап по кнопке [LOAD OLDER] или [BACK TO LATEST] внутри чата
        if (y >= chatTopY && y < chatBotY && !isLoading) {
            int fhp = Omega.uiFont().getHeight();
            int tapLine = (scroller.getPos() + (y - chatTopY)) / fhp;
            if (loadMoreLine >= 0 && tapLine == loadMoreLine) { loadMoreMessages(); return; }
            if (backLine >= 0 && tapLine == backLine) { backToLatest(); return; }
            // Тап по своему вопросу: правка; по последнему ответу: перегенерация
            int mk = messageAtLine(tapLine);
            if (mk != -1 && tapLine < wrappedLines.size()
                    && ((Integer) lineRoles.elementAt(tapLine)).intValue() != 2) {
                int abs = ((Integer) msgAbsIdx.elementAt(mk)).intValue();
                int mrole = ((Integer) msgRoleV.elementAt(mk)).intValue();
                if (mrole == 0 && abs >= msgTotal - 2) { editLast(); return; }
                if (mrole == 1 && abs == msgTotal - 1) { regenerate(); return; }
            }
        }
        if (!isLoading) openFullTextBox();
    }


    private void openFileBrowser(final String path) {
        final int tok = midlet.beginAsyncNav();
        new Thread(new Runnable() {
            public void run() {
                FileConnection fc = null;
                try {
                    final String targetPath = path;
                    if (targetPath.equals("file:///")) {
                        Enumeration roots = FileSystemRegistry.listRoots();
                        final Vector rootList = new Vector();
                        while (roots.hasMoreElements()) {
                            String r = (String) roots.nextElement();
                            if (r != null && r.length() > 0) {
                                if (!r.startsWith("file://")) r = "file:///" + r;
                                rootList.addElement(r);
                            }
                        }
                        if (rootList.size() == 0) { rootList.addElement("file:///C:/"); rootList.addElement("file:///E:/"); }
                        midlet.openSelectionMenuIf(tok, "Select Root", rootList, 0, new SelectCallback() {
                            public void onSelected(int index, String value) {
                                String rp = value.trim(); if (!rp.endsWith("/")) rp += "/"; openFileBrowser(rp);
                            }
                        }, new Runnable() { public void run() { midlet.switchToChat(); } });
                        return;
                    }

                    fc = (FileConnection) Connector.open(targetPath);
                    if (!fc.isDirectory()) return;
                    Enumeration fileEnum = fc.list();
                    final Vector fileList = new Vector();
                    fileList.addElement(".. (Up)");
                    while (fileEnum.hasMoreElements()) fileList.addElement(fileEnum.nextElement());
                    fc.close();
                    fc = null;

                    midlet.openSelectionMenuIf(tok, targetPath, fileList, 0, new SelectCallback() {
                        public void onSelected(int index, String value) {
                            if (value.equals(".. (Up)")) {
                                int lastSlash = targetPath.substring(0, targetPath.length() - 1).lastIndexOf('/');
                                if (lastSlash > 7) openFileBrowser(targetPath.substring(0, lastSlash + 1)); else openFileBrowser("file:///");
                            } else if (value.endsWith("/")) openFileBrowser(targetPath + value);
                            else validateAndAttachImage(targetPath + value);
                        }
                    }, new Runnable() {
                        public void run() {
                            int lastSlash = targetPath.substring(0, targetPath.length() - 1).lastIndexOf('/');
                            if (lastSlash > 7) openFileBrowser(targetPath.substring(0, lastSlash + 1)); else openFileBrowser("file:///");
                        }
                    });
                } catch (Exception e) {
                    display.callSerially(new Runnable() {
                        public void run() {
                            if (!midlet.isNavCurrent(tok)) return;
                            wrapTextIntoVector("Sys: Err opening folder", Omega.uiFont(), getWidth() - 6, wrappedLines);
                            repaint();
                            display.setCurrent(TerminalCanvas.this);
                        }
                    });
                } finally {
                    if (fc != null) try { fc.close(); } catch (Exception ex) {}
                }
            }
        }).start();
    }

    private void validateAndAttachImage(final String filePath) {
        final int tok = midlet.beginAsyncNav();
        new Thread(new Runnable() {
            public void run() {
                FileConnection fc = null;
                String sizeError = null;
                try {
                    String safePath = encodePathSpaces(filePath);
                    fc = (FileConnection) Connector.open(safePath, Connector.READ);
                    if (fc.exists()) {
                        long size = fc.fileSize();
                        if (size > 2097152) sizeError = "Sys: File > 2MB limit";
                        else attachedImagePath = safePath;
                    }
                } catch (Exception e) {
                    attachedImagePath = null;
                } finally {
                    if (fc != null) try { fc.close(); } catch (Exception ex) {}
                }

                final String err = sizeError;
                display.callSerially(new Runnable() {
                    public void run() {
                        // Если пользователь за это время ушёл на другой экран, чат поверх не показываем
                        if (!midlet.isNavCurrent(tok)) return;
                        if (err != null) {
                            wrapTextIntoVector(err, Omega.uiFont(), getWidth() - 6, wrappedLines);
                        }
                        repaint();
                        display.setCurrent(TerminalCanvas.this);
                    }
                });
            }
        }).start();
    }

    private String encodePathSpaces(String path) {
        StringBuffer sb = new StringBuffer();
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c == ' ') sb.append("%20"); else sb.append(c);
        }
        return sb.toString();
    }

    private void triggerSend() {
        String text = currentInput.toString().trim();
        boolean hasRealImage = false;
        if (attachedImagePath != null) {
            try {
                FileConnection testFc = (FileConnection) Connector.open(attachedImagePath, Connector.READ);
                hasRealImage = testFc.exists(); testFc.close();
            } catch (Exception e) { hasRealImage = false; attachedImagePath = null; }
        }
 
        if ((text.length() > 0 || hasRealImage) && !isLoading) {
            if (hasRealImage) {
                // Запоминаем, какая картинка ушла с этим сообщением: она понадобится для перегенерации и правки
                String imgPath = attachedImagePath;
                triggerBrowserBridgeSend();
                rememberImage(chatName, imgPath, chatStarts(loadChatCompact()).size());
                return;
            }
 
            String promptForModel = text;
            currentInput.setLength(0);
            cursorPos = 0;
            isLoading = true; stage("preparing");
 
            if (chatName.equals("temp_new_chat")) {
                // Новый чат называем по началу запроса, но не занимаем имя уже существующего чата
                chatName = Omega.uniqueChatName(Omega.sanitizeChatName(text, 15, "chat"));
                midlet.currentChat = chatName;
                midlet.saveSettings();
            }
 
            String jsonHistory = loadChatFromRms(chatName);
            new Thread(new TerminalSender(promptForModel, promptForModel, null, jsonHistory, chatName, midlet.selectedModel)).start();
        }
    }

private class TerminalSender implements Runnable {
    private String prm, dispPrm, base64Img, hist, cName, mdl;
    private boolean trimmed = false;   // история в запросе была урезана по лимиту контекста
    private int myTok = -1;            // номер потокового ответа, если он начался
    private int launchTok = 0;         // номер потока на момент запуска отправки (для отмены до начала потока)
    private int directAttempt = 1;     // номер попытки прямой отправки (повторяем при 429/502/503)
    private boolean retryDirect = false;
    private int retryCode = 0;

    public TerminalSender(String p, String dispP, String img, String h, String cn, String model) {
        prm = p; dispPrm = dispP; base64Img = img; hist = h; cName = cn; mdl = model;
    }

    public void run() {
        try {
            // В запрос уходит только та часть истории, что влезает в лимит контекста.
            // Полная история остаётся в чате на телефоне
            launchTok = streamToken;
            String before = hist;
            hist = Omega.trimHistory(hist, midlet.getCtxLimit());
            trimmed = (before != null && hist != null && hist.length() != before.length());
 
            byte[] histBytes = hist != null ? hist.getBytes("UTF-8") : new byte[0];
            int histSizeKb = histBytes.length / 1024;
 
            if (midlet.proxyMode == 2 || (midlet.proxyMode == 1 && histSizeKb > 12)) {
                sendChatViaProxy(histBytes);
            } else {
                // Временные отказы провайдера (429 лимит, 502/503 перегрузка): пробуем ещё до двух раз с паузой
                for (directAttempt = 1; directAttempt <= 3; directAttempt++) {
                    retryDirect = false;
                    sendChatDirect();
                    if (!retryDirect) break;
                    int waitSec = directAttempt * 3;
                    for (int s = waitSec; s > 0; s--) {
                        if (!isLoading) return;          // ожидание отменили
                        stage("HTTP " + retryCode + ", retry " + (directAttempt + 1) + "/3 in " + s + "s");
                        try { Thread.sleep(1000); } catch (Exception e) {}
                    }
                }
            }
        } catch (final Throwable t) {
            String m0 = t.getMessage();
            if (m0 == null) m0 = t.getClass().getName();
            if (myTok != -1) {
                endStreamErr(myTok, m0);
            } else {
                final String msg = m0;
                display.callSerially(new Runnable() {
                    public void run() {
                        isLoading = false;
                        loadingStatusText = "[...]";
                        wrapTextIntoVector("Sys: Err " + msg, Omega.uiFont(), getWidth() - 6, wrappedLines);
                        repaint();
                    }
                });
            }
        }
    }

        // Тип картинки определяем по началу base64 (раньше всегда писали jpeg, даже для png/gif/webp)
    private String guessImageMime(String b64) {
        if (b64.startsWith("iVBOR")) return "image/png";
        if (b64.startsWith("R0lGOD")) return "image/gif";
        if (b64.startsWith("UklGR")) return "image/webp";
        return "image/jpeg";
    }
 
    // Пометку "[IMG] " в старых сообщениях истории убираем: сами картинки повторно не отправляются,
    // а модель, увидев пометку, может решить, что в запросе несколько изображений
    private String stripImgMarker(String s) {
        String key = "\"content\":\"[IMG] ";
        String rep = "\"content\":\"";
        StringBuffer sb = new StringBuffer();
        int pos = 0;
        while (true) {
            int i = s.indexOf(key, pos);
            if (i == -1) break;
            sb.append(s.substring(pos, i)).append(rep);
            pos = i + key.length();
        }
        sb.append(s.substring(pos));
        return sb.toString();
    }

    private void sendChatDirect() {
        HttpConnection c = null;
        OutputStream os = null;
        InputStream is = null;
        final boolean wantStream = midlet.isStreamOn() && (base64Img == null || base64Img.length() == 0);
        final String streamFlag = wantStream ? "true" : "false";
        try {
            String url = CHAT_URLS[midlet.selectedProvider];
 
            stage("connect " + hostOf(url));
            c = (HttpConnection) Connector.open(url, Connector.READ_WRITE, true);
            c.setRequestMethod(HttpConnection.POST);
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36");
            c.setRequestProperty("Accept", "application/json, text/plain, */*");
            c.setRequestProperty("Accept-Encoding", "identity");
            c.setRequestProperty("Connection", "close");
 
            if (midlet.apiKey != null && midlet.apiKey.length() > 0) {
                c.setRequestProperty("Authorization", "Bearer " + midlet.apiKey);
            }
 
            stage("build request");
            String historyInner = "";
            if (hist != null) {
                String trimmedHist = hist.trim();
                if (trimmedHist.startsWith("[") && trimmedHist.endsWith("]")) {
                    historyInner = Omega.stripModelKeys(stripImgMarker(trimmedHist.substring(1, trimmedHist.length() - 1).trim()));
                }
            }
 
            ByteArrayOutputStream body = new ByteArrayOutputStream();
 
            if (midlet.selectedProvider == 0) { // Ollama Cloud
                StringBuffer ollamaPrompt = new StringBuffer();
                if (midlet.systemPrompt != null && midlet.systemPrompt.length() > 0) {
                    ollamaPrompt.append("System: ").append(midlet.systemPrompt).append("\n\n");
                }
                if (historyInner.length() > 0) {
                    int pos = 0;
                    while (true) {
                        int rIdx = historyInner.indexOf("\"role\":\"", pos);
                        if (rIdx == -1) break;
                        int rEnd = historyInner.indexOf('"', rIdx + 8);
                        String role = historyInner.substring(rIdx + 8, rEnd);
                        int cIdx = historyInner.indexOf("\"content\":\"", rEnd);
                        if (cIdx == -1) break;
                        int cEnd = findJsonStringEnd(historyInner, cIdx + 11);
                        String content = unescapeString(historyInner.substring(cIdx + 11, cEnd));
 
                        if (role.equals("user")) ollamaPrompt.append("User: ").append(content).append("\n");
                        else if (role.equals("assistant")) ollamaPrompt.append("Assistant: ").append(content).append("\n");
                        pos = cEnd;
                    }
                }
                ollamaPrompt.append("User: ").append(prm).append("\nAssistant:");
                body.write(("{\"model\":\"" + midlet.selectedModel + "\",\"prompt\":\"" + escape(ollamaPrompt.toString()) + "\",\"stream\":" + streamFlag + midlet.paramsJson(0) + "}").getBytes("UTF-8"));
 
            } else { // OpenAI-совместимые (VoidAI, OpenCode, GroqCloud, OpenRouter)
                StringBuffer msgs = new StringBuffer("[");
                if (midlet.systemPrompt != null && midlet.systemPrompt.length() > 0) {
                    msgs.append("{\"role\":\"system\",\"content\":\"").append(escape(midlet.systemPrompt)).append("\"}");
                    if (historyInner.length() > 0 || prm != null) msgs.append(",");
                }
                if (historyInner.length() > 0) {
                    msgs.append(historyInner);
                    if (prm != null) msgs.append(",");
                }
                if (prm != null) {
                    if (base64Img != null && base64Img.length() > 0) {
                        msgs.append("{\"role\":\"user\",\"content\":[")
                            .append("{\"type\":\"text\",\"text\":\"").append(escape(prm)).append("\"},")
                            .append("{\"type\":\"image_url\",\"image_url\":{\"url\":\"data:").append(guessImageMime(base64Img)).append(";base64,").append(base64Img).append("\"}}")
                            .append("]}");
                    } else {
                        msgs.append("{\"role\":\"user\",\"content\":\"").append(escape(prm)).append("\"}");
                    }
                }
                msgs.append("]");
                String jsonBody = "{\"model\":\"" + midlet.selectedModel + "\",\"messages\": " + msgs.toString() + ",\"stream\":" + streamFlag + midlet.paramsJson(midlet.selectedProvider) + midlet.fallbackJson(midlet.selectedProvider) + "}";
                body.write(jsonBody.getBytes("UTF-8"));
            }
 
            byte[] payload = body.toByteArray();
            c.setRequestProperty("Content-Length", String.valueOf(payload.length));
 
            stage("send " + sizeStr(payload.length));
            os = c.openOutputStream();
            os.write(payload);
            os.flush();
 
            stage("wait model");
            int code = c.getResponseCode();
            if (wantStream && code == HttpConnection.HTTP_OK) {
                stage("read stream");
                is = c.openInputStream();
                readDirectStream(is);
                return;
            }
            stage("read reply HTTP " + code);
            is = c.openInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            int ch;
            while ((ch = is.read()) != -1) bos.write(ch);
            
            stage("parse " + sizeStr(bos.size()));
            String responseString = new String(bos.toByteArray(), "UTF-8").trim();
            if (responseString.startsWith("<!DOCTYPE") || responseString.startsWith("<html")) {
                throw new Exception("Server returned HTML page instead of text.");
            }
 
            if (code == HttpConnection.HTTP_OK) {
                final String resp = parseResp(responseString, midlet.selectedProvider);
                stage("save chat");
                appendToChatRms(cName, dispPrm, resp, mdl);
 
                display.callSerially(new Runnable() {
                    public void run() {
                        isLoading = false;
                        loadingStatusText = "[...]";
                        rebuildWrappedLines();
                        repaint();
                    }
                });
            } else {
                if ((code == 429 || code == 502 || code == 503) && directAttempt < 3) {
                    retryDirect = true;
                    retryCode = code;
                    return;
                }
                final String errText = "HTTP " + code + ": " + shortErr(responseString);
                display.callSerially(new Runnable() {
                    public void run() {
                        isLoading = false;
                        loadingStatusText = "[...]";
                        wrapTextIntoVector("Sys: " + errText, Omega.uiFont(), getWidth() - 6, wrappedLines);
                        repaint();
                    }
                });
            }
        } catch (final Throwable t) {
            String m0 = t.getMessage();
            if (m0 == null) m0 = t.getClass().getName();
            if (myTok != -1) {
                // Поток уже начался: показываем то, что пришло, и ошибку под ним
                endStreamErr(myTok, m0);
            } else {
                final String st = loadingStatusText;
                final String msg = m0;
                display.callSerially(new Runnable() {
                    public void run() {
                        isLoading = false;
                        loadingStatusText = "[...]";
                        if (msg.indexOf("36") != -1 || msg.indexOf("length") != -1 || msg.indexOf("size") != -1) {
                            wrapTextIntoVector("Sys: POST exceeds 16kbytes, direct connection will not work on your device properly. Please, setup proxy", Omega.uiFont(), getWidth() - 6, wrappedLines);
                        } else {
                            wrapTextIntoVector("Sys: Err " + msg + " @" + st, Omega.uiFont(), getWidth() - 6, wrappedLines);
                        }
                        repaint();
                    }
                });
            }
        } finally {
            try { if (is != null) is.close(); } catch (Exception e) {}
            try { if (os != null) os.close(); } catch (Exception e) {}
            try { if (c != null) c.close(); } catch (Exception e) {}
        }
    }

        // Читает потоковый ответ напрямую от провайдера (SSE или построчный JSON у Ollama)
    private void readDirectStream(InputStream is) throws Exception {
        final int prov = midlet.selectedProvider;
        myTok = beginStream(launchTok, cName, dispPrm, mdl);
        if (myTok == -1) return;                     // отменили до начала потока
        final int tok = myTok;
 
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        StringBuffer raw = new StringBuffer();    // пока нет ни одного куска текста, копим сырой ответ: вдруг провайдер не умеет стримить
        boolean got = false;
        int ch;
        while (tok == streamToken && (ch = is.read()) != -1) {
            if (ch != '\n') { line.write(ch); continue; }
 
            String ln = new String(line.toByteArray(), "UTF-8").trim();
            line.reset();
            if (ln.length() == 0 || ln.startsWith(":") || ln.startsWith("event:") || ln.startsWith("id:") || ln.startsWith("retry:")) continue;
            if (ln.startsWith("data:")) ln = ln.substring(5).trim();
            if (ln.equals("[DONE]")) break;
 
            String piece = streamPiece(ln, prov);
            if (piece != null) {
                if (piece.length() > 0) { got = true; streamPush(piece); }
                if (prov == 0 && ln.indexOf("\"done\":true") != -1) break;
            } else {
                String err = streamErrorOf(ln);
                if (err != null) throw new Exception(err);
                if (!got && raw.length() < 65536) raw.append(ln).append('\n');
            }
        }
        if (tok != streamToken) return;               // ответ отменили
 
        if (!got) {
            if (raw.length() == 0) throw new Exception("Empty stream");
            streamPush(parseResp(raw.toString().trim(), prov));
        }
        endStreamOk(tok);
    }
 
    // Потоковый ответ через прокси: прокси читает провайдера сам, телефон раз в ~0,7 с забирает новый текст
    private void pollProxyStream(String proxyBase, String sid) throws Exception {
        myTok = beginStream(launchTok, cName, dispPrm, mdl);
        if (myTok == -1) return;                     // отменили до начала потока
        final int tok = myTok;
        streamSid = sid;
        stage("wait model via proxy");
 
        int from = 0;
        int fails = 0;
        long lastProgress = System.currentTimeMillis();
        while (tok == streamToken) {
            try { Thread.sleep(700); } catch (Exception e) {}
            if (tok != streamToken) return;
 
            String body = null;
            HttpConnection pc = null;
            InputStream pis = null;
            try {
                pc = (HttpConnection) Connector.open(proxyBase + "/?type=poll&sid=" + sid + "&from=" + from, Connector.READ);
                pc.setRequestMethod(HttpConnection.GET);
                pc.setRequestProperty("User-Agent", "Omega-Symbian");
                pc.setRequestProperty("Connection", "close");
                if (pc.getResponseCode() == HttpConnection.HTTP_OK) {
                    pis = pc.openInputStream();
                    ByteArrayOutputStream pb = new ByteArrayOutputStream();
                    int pch;
                    while ((pch = pis.read()) != -1) pb.write(pch);
                    body = new String(pb.toByteArray(), "UTF-8");
                }
            } catch (Exception e) {
                body = null;
            } finally {
                try { if (pis != null) pis.close(); } catch (Exception e) {}
                try { if (pc != null) pc.close(); } catch (Exception e) {}
            }
 
            int nl = (body == null) ? -1 : body.indexOf('\n');
            String head = (body == null) ? "" : ((nl == -1) ? body.trim() : body.substring(0, nl).trim());
            if (head.length() == 0 || head.startsWith("<")) {
                // Нет ответа (обрыв связи или страница-заглушка): пробуем ещё раз
                fails++;
                if (fails >= 6) throw new Exception("Lost connection to proxy");
                continue;
            }
            fails = 0;
 
            String rest = (nl == -1) ? "" : body.substring(nl + 1);
            char state = head.charAt(0);
            int sp = head.indexOf(' ');
            if (sp != -1) {
                try { from = Integer.parseInt(head.substring(sp + 1).trim()); } catch (Exception e) {}
            }
            if (state == 'E') throw new Exception(rest.trim().length() > 0 ? rest.trim() : "Stream error");
            if (rest.length() > 0) {
                streamPush(rest);
                lastProgress = System.currentTimeMillis();
            }
            if (state == 'D') {
                endStreamOk(tok);
                return;
            }
            if (System.currentTimeMillis() - lastProgress > 240000L) throw new Exception("Stream stalled");
        }
    }

private String cleanHdr(String s) {
    if (s == null) return "";
    StringBuffer sb = new StringBuffer();
    for (int i = 0; i < s.length(); i++) {
        char ch = s.charAt(i);
        if (ch == '\r' || ch == '\n' || ch == '\t') sb.append(' ');
        else if (ch >= 32 && ch != 127) sb.append(ch);
    }
    return sb.toString().trim();
}

private String stripTags(String s) {
    StringBuffer sb = new StringBuffer();
    boolean in = false;
    for (int i = 0; i < s.length() && sb.length() < 80; i++) {
        char ch = s.charAt(i);
        if (ch == '<') { in = true; sb.append(' '); }
        else if (ch == '>') in = false;
        else if (!in && ch >= 32) sb.append(ch);
    }
    return sb.toString().trim();
}

    private void sendChatViaProxy(byte[] histBytes) {
        HttpConnection c = null;
        OutputStream os = null;
        InputStream is = null;
        try {
            stage("build request");
            String historyInner = "";
            if (hist != null) {
                String trimmedHist = hist.trim();
                if (trimmedHist.startsWith("[") && trimmedHist.endsWith("]")) {
                    historyInner = trimmedHist.substring(1, trimmedHist.length() - 1).trim();
                }
            }
            StringBuffer msgs = new StringBuffer("[");
            if (historyInner.length() > 0) {
                msgs.append(historyInner).append(",");
            }
            msgs.append("{\"role\":\"user\",\"content\":\"").append(escape(prm)).append("\"}]");
 
            byte[] payloadBytes = msgs.toString().getBytes("UTF-8");
            int totalLen = payloadBytes.length;
 
            int chunkSize = 8192;
            int totalChunks = (totalLen + chunkSize - 1) / chunkSize;
 
            final String sid = "omega_chat_" + System.currentTimeMillis();
            String proxyBase = midlet.proxyUrl;
            if (proxyBase == null || proxyBase.trim().length() == 0) {
                throw new Exception("Proxy URL is not set!");
            }
            if (proxyBase.endsWith("/")) {
                proxyBase = proxyBase.substring(0, proxyBase.length() - 1);
            }
            final boolean wantStream = midlet.isStreamOn();
 
            for (int i = 0; i < totalChunks; i++) {
                int start = i * chunkSize;
                int end = Math.min(start + chunkSize, totalLen);
 
                byte[] chunkBuf = new byte[end - start];
                System.arraycopy(payloadBytes, start, chunkBuf, 0, end - start);
                boolean isFinal = (i == totalChunks - 1);
 
                stage("send " + (i + 1) + "/" + totalChunks + " (" + sizeStr(chunkBuf.length) + ")" + (i == 0 ? " > " + hostOf(proxyBase) : ""));
 
                String chunkUrl = proxyBase + "/?sid=" + sid +
                                  "&type=chat" +
                                  "&chat=" + midlet.escapeUrl(cName) +
                                  "&chunk=" + (i + 1) +
                                  "&total=" + totalChunks +
                                  "&final=" + (isFinal ? "1" : "0") +
                                  "&prov=" + midlet.selectedProvider +
                                  (wantStream ? "&stream=1" : "") +
                                  (trimmed ? "&trim=1" : "");
 
                String resp = "";
                int code = 0;
                for (int attempt = 0; attempt < 2; attempt++) {
                    c = (HttpConnection) Connector.open(chunkUrl, Connector.READ_WRITE, true);
                    c.setRequestMethod(HttpConnection.POST);
                    c.setRequestProperty("Content-Type", "application/octet-stream");
                    c.setRequestProperty("Content-Length", String.valueOf(chunkBuf.length));
                    c.setRequestProperty("Connection", "close");
                    c.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36");
 
                    if (midlet.selectedModel != null) {
                        c.setRequestProperty("X-Omega-Model", cleanHdr(midlet.selectedModel));
                    }
                    if (midlet.apiKey != null) {
                        c.setRequestProperty("X-Omega-Key", cleanHdr(midlet.apiKey));
                    }
                    if (midlet.systemPrompt != null) {
                        c.setRequestProperty("X-Omega-Sys", cleanHdr(midlet.systemPrompt));
                    }
                    String paramsHdr = midlet.paramsHeader();
                    if (paramsHdr.length() > 0) {
                        c.setRequestProperty("X-Omega-Params", cleanHdr(paramsHdr));
                    }
                    String fbHdr = midlet.fallbackHeader(midlet.selectedProvider);
                    if (fbHdr.length() > 0) {
                        c.setRequestProperty("X-Omega-Fallback", cleanHdr(fbHdr));
                    }
 
                    os = c.openOutputStream();
                    os.write(chunkBuf);
                    os.flush();
                    os.close();
                    os = null;
 
                    if (isFinal) stage(attempt == 0 ? "wait model via proxy" : "retry via proxy");
                    code = c.getResponseCode();
                    if (isFinal) stage("read reply HTTP " + code);
                    is = c.openInputStream();
                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    int ch;
                    while ((ch = is.read()) != -1) bos.write(ch);
                    is.close();
                    is = null;
                    c.close();
                    c = null;
 
                    resp = new String(bos.toByteArray(), "UTF-8").trim();
 
                    // HTML-страница с 400 значит, что запрос отбили до приложения: повторяем один раз
                    if (code == 400 && resp.startsWith("<") && attempt == 0) {
                        try { Thread.sleep(700); } catch (Exception e) {}
                        continue;
                    }
                    break;
                }
 
                if (resp.startsWith("<")) {
                    throw new Exception("HTTP " + code + " " + stripTags(resp));
                }
 
                // Промежуточные куски сервер подтверждает словом CHUNK_OK. Всё остальное - ошибка
                if (!isFinal && !resp.equals("CHUNK_OK")) {
                    throw new Exception(resp.length() > 100 ? resp.substring(0, 100) : resp);
                }
 
                if (isFinal) {
                    // Сервер принял запрос и читает ответ провайдера потоком: забираем текст по мере появления
                    if (wantStream && resp.equals("STREAM_OK")) {
                        pollProxyStream(proxyBase, sid);
                        return;
                    }
 
                    if (resp.startsWith("ERR:")) {
                        throw new Exception(resp.length() > 100 ? resp.substring(0, 100) : resp);
                    }
 
                    final String finalResult = resp;
                    stage("save chat");
                    appendToChatRms(cName, dispPrm, finalResult, mdl);
 
                    display.callSerially(new Runnable() {
                        public void run() {
                            isLoading = false;
                            loadingStatusText = "[...]";
                            rebuildWrappedLines();
                            repaint();
                        }
                    });
                    break;
                }
            }
 
        } catch (final Exception e) {
            String m0 = e.getMessage();
            if (m0 == null) m0 = e.getClass().getName();
            if (myTok != -1) {
                endStreamErr(myTok, m0);
            } else {
                final String st = loadingStatusText;
                final String errMessage = m0;
                display.callSerially(new Runnable() {
                    public void run() {
                        isLoading = false;
                        loadingStatusText = "[...]";
                        wrapTextIntoVector("Sys: Proxy Err: " + errMessage + " @" + st, Omega.uiFont(), getWidth() - 6, wrappedLines);
                        repaint();
                    }
                });
            }
        } finally {
            try { if (is != null) is.close(); } catch (Exception e) {}
            try { if (os != null) os.close(); } catch (Exception e) {}
            try { if (c != null) c.close(); } catch (Exception e) {}
        }
    }

    private String escape(String s) {
        return Omega.escapeStatic(s);
    }

    private String parseResp(String json, int type) {
        if (json == null || json.length() == 0) return "Sys: Empty response";

        String targetKey = (type == 0) ? "response" : "content";

        int kIdx = json.indexOf("\"" + targetKey + "\"");
        if (kIdx == -1) kIdx = json.indexOf(targetKey);
        if (kIdx == -1) return "Raw: " + (json.length() > 80 ? json.substring(0, 80) : json);

        int colonIdx = json.indexOf(':', kIdx + targetKey.length());
        if (colonIdx == -1) return "Raw: " + (json.length() > 80 ? json.substring(0, 80) : json);

        int quoteIdx = json.indexOf('"', colonIdx);
        if (quoteIdx == -1) return "Raw: " + (json.length() > 80 ? json.substring(0, 80) : json);

        int start = quoteIdx + 1;
        StringBuffer sb = new StringBuffer();
        for (int i = start; i < json.length(); i++) {
            char ch = json.charAt(i);
            if (ch == '"' && json.charAt(i - 1) != '\\') break;
            if (ch == '\\' && i + 1 < json.length()) {
                char next = json.charAt(i + 1);
                if (next == 'n') { sb.append('\n'); i++; continue; }
                else if (next == 't') { sb.append('\t'); i++; continue; }
                else if (next == '"') { sb.append('"'); i++; continue; }
                else if (next == '\\') { sb.append('\\'); i++; continue; }
                else if (next == 'r') { i++; continue; }
            }
            sb.append(ch);
        }

        String res = sb.toString().trim();
        return res.length() == 0 ? "Raw JSON: " + (json.length() > 80 ? json.substring(0, 80) : json) : res;
    }
}
    }
}
