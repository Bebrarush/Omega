import javax.microedition.midlet.*;
import javax.microedition.lcdui.*;
import javax.microedition.io.*;
import javax.microedition.rms.*;
import javax.microedition.io.file.*;
import java.io.*;
import java.util.Vector;
import java.util.Enumeration;

public class Omega extends MIDlet {
Display display;
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


public static int emphColor(int main, int bg) {
    // Цвет для выделения: основной цвет, сдвинутый к белому на тёмном фоне и к чёрному на светлом
    int lum = (((bg >> 16) & 0xFF) * 3 + ((bg >> 8) & 0xFF) * 6 + (bg & 0xFF)) / 10;
    return mixColor(main, lum < 128 ? 0xFFFFFF : 0x000000, 45);
}

// Подпись в полосе шириной maxW. Если не влезает: у выбранной строки (scroll=true) она бежит по кругу,
// у остальных обрезается с многоточием. Возвращает true, если строка бежит (канвасу нужно перерисовываться по таймеру)
public static boolean drawFit(Graphics g, Font f, String text, int x, int y, int maxW, int tick, boolean scroll) {
    if (maxW < 8) return false;
    if (f.stringWidth(text) <= maxW) {
        g.drawString(text, x, y, Graphics.TOP | Graphics.LEFT);
        return false;
    }
    if (!scroll) {
        String r = text;
        while (r.length() > 1 && f.stringWidth(r + "...") > maxW) r = r.substring(0, r.length() - 1);
        g.drawString(r + "...", x, y, Graphics.TOP | Graphics.LEFT);
        return false;
    }
    int cx = g.getClipX(), cy = g.getClipY(), cw = g.getClipWidth(), ch = g.getClipHeight();
    g.clipRect(x, y, maxW, f.getHeight());
    int period = text.length() + 5;
    int s = (tick / 4) % (period + 12);            // 12 шагов паузы в начале, потом бег
    int shift = (s < 12) ? 0 : s - 12;
    g.drawString((text + "     " + text).substring(shift), x, y, Graphics.TOP | Graphics.LEFT);
    g.setClip(cx, cy, cw, ch);
    return true;
}

public static Font uiFont() {
    return Font.getFont(Font.FACE_MONOSPACE, Font.STYLE_BOLD, Font.SIZE_MEDIUM);
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

// ===== Чат в памяти: он лежит в RMS одной записью, поэтому держим только её байты, а разбираем лишь нужные сообщения =====

// Байты чата как есть. Нет чата или временный чат: пустой список
public static byte[] readChatBytes(String chatName) {
    if (chatName != null && !chatName.equals("temp_new_chat")) {
        RecordStore rs = null;
        try {
            rs = RecordStore.openRecordStore("OM_CHAT_" + chatName, false);
            if (rs.getNumRecords() > 0) return rs.getRecord(1);
        } catch (Exception e) {}
        finally { if (rs != null) try { rs.closeRecordStore(); } catch (Exception e) {} }
    }
    return new byte[] { '[', ']' };
}

// Записывает готовые байты чата вместо прежних
public static void saveChatBytes(String chatName, byte[] data, int off, int len) {
    if (chatName == null || chatName.equals("temp_new_chat")) return;
    RecordStore rs = null;
    try { RecordStore.deleteRecordStore("OM_CHAT_" + chatName); } catch (Exception e) {}
    try {
        rs = RecordStore.openRecordStore("OM_CHAT_" + chatName, true);
        rs.addRecord(data, off, len);
    } catch (Exception e) {}
    finally { if (rs != null) try { rs.closeRecordStore(); } catch (Exception e) {} }
}

// Позиции начал сообщений: ищем {"role" (внутри текста такие кавычки всегда экранированы, поэтому ошибиться нельзя)
public static int[] chatStartsBytes(byte[] b) {
    int cnt = 0;
    for (int i = 0; i + 7 <= b.length; i++) {
        if (b[i] == '{' && b[i + 1] == '"' && b[i + 2] == 'r' && b[i + 3] == 'o' && b[i + 4] == 'l' && b[i + 5] == 'e' && b[i + 6] == '"') cnt++;
    }
    int[] st = new int[cnt];
    int k = 0;
    for (int i = 0; i + 7 <= b.length && k < cnt; i++) {
        if (b[i] == '{' && b[i + 1] == '"' && b[i + 2] == 'r' && b[i + 3] == 'o' && b[i + 4] == 'l' && b[i + 5] == 'e' && b[i + 6] == '"') { st[k++] = i; i += 6; }
    }
    return st;
}

// Позиция закрывающей ] списка сообщений (или длина, если её нет)
public static int chatEndBytes(byte[] b) {
    for (int i = b.length - 1; i >= 0; i--) {
        if (b[i] == ']') return i;
        if (b[i] > ' ') break;
    }
    return b.length;
}

// Сколько символов (char) в строке, полученной из этих байт UTF-8
public static int utf8Chars(byte[] b, int off, int end) {
    int n = 0;
    for (int i = off; i < end; i++) {
        int c = b[i] & 0xFF;
        if ((c & 0xC0) != 0x80) {
            n++;
            if (c >= 0xF0) n++;                            // четырёхбайтный символ занимает два char
        }
    }
    return n;
}

// Длина чата в символах, как её видит сервер
public static int chatCharCount(String chatName) {
    byte[] b = readChatBytes(chatName);
    return utf8Chars(b, 0, b.length);
}

public static boolean chatTailCut = false;                  // загруженная для отправки история короче полной

// История для отправки модели: только хвост, который может влезть в лимит контекста. Полный чат целиком не разбирается.
// Флаг chatTailCut говорит, обрезан ли он, чтобы прокси знал, что получил урезанное окно
public static String loadChatForSend(String chatName, int limit) {
    chatTailCut = false;
    if (limit <= 0) return loadChatFromRms(chatName);
    byte[] raw = readChatBytes(chatName);
    int[] st = chatStartsBytes(raw);
    int end = chatEndBytes(raw);
    int n = st.length;
    if (n < 4 || end - st[0] <= limit) {                    // короткий чат: берём целиком, как раньше
        try { return fixControlChars(new String(raw, "UTF-8")); } catch (Exception e) { return "[]"; }
    }
    // Бюджет в байтах с запасом: кириллица занимает по два байта на символ, точное урезание сделает trimHistory
    int budget = limit * 2;
    int k = n - 3;
    while (k > 0 && end - st[k - 1] <= budget) k--;
    chatTailCut = k > 0;
    try {
        String tail = "[" + fixControlChars(new String(raw, st[k], end - st[k], "UTF-8")) + "]";
        if (tail.indexOf("\"role\": \"") != -1) {
            tail = replaceAllStatic(tail, "\"role\": \"", "\"role\":\"");
            tail = replaceAllStatic(tail, "\"content\": \"", "\"content\":\"");
            tail = replaceAllStatic(tail, "\"model\": \"", "\"model\":\"");
        }
        return tail;
    } catch (Exception e) {
        chatTailCut = false;
        return "[]";
    }
}

public static String replaceAllStatic(String s, String from, String to) {
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
    // Дописываем в конец байтов, не превращая весь чат в строку
    byte[] old = readChatBytes(chatName);
    int first = 0;
    while (first < old.length && old[first] <= ' ') first++;
    if (first >= old.length || old[first] != '[') old = new byte[] { '[', ']' };
    int end = chatEndBytes(old);
    boolean has = false;
    for (int i = 0; i < end; i++) {
        if (old[i] == '{') { has = true; break; }
    }
    StringBuffer sb = new StringBuffer();
    if (has) sb.append(",");
    sb.append("{\"role\":\"user\",\"content\":\"").append(escapeStatic(userMsg)).append("\"},");
    sb.append("{\"role\":\"assistant\",");
    if (model != null && model.length() > 0) sb.append("\"model\":\"").append(escapeStatic(model)).append("\",");
    sb.append("\"content\":\"").append(escapeStatic(aiResp)).append("\"}]");
    byte[] add;
    try { add = sb.toString().getBytes("UTF-8"); } catch (Exception e) { return; }
    byte[] all = new byte[end + add.length];
    System.arraycopy(old, 0, all, 0, end);
    System.arraycopy(add, 0, all, end, add.length);
    old = null;
    saveChatBytes(chatName, all, 0, all.length);
}
 

static String escapeStatic(String s) {
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
static String fixControlChars(String s) {
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

static int colorDistance(int a, int b) {
    return Math.abs(((a >> 16) & 0xFF) - ((b >> 16) & 0xFF))
         + Math.abs(((a >> 8) & 0xFF) - ((b >> 8) & 0xFF))
         + Math.abs((a & 0xFF) - (b & 0xFF));
}


}
