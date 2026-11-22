import javax.microedition.midlet.*;
import javax.microedition.lcdui.*;
import javax.microedition.io.*;
import javax.microedition.rms.*;
import javax.microedition.io.file.*;
import java.io.*;
import java.util.Vector;
import java.util.Enumeration;

class TerminalCanvas extends Canvas {
    Omega midlet;
    private String chatName;
    Vector wrappedLines = new Vector();
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
    boolean isLoading = false;
    volatile String loadingStatusText = "[...]";
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
    volatile int streamToken = 0;                  // номер текущего потока; меняется при отмене и ошибке
    private int streamShown = 0;                           // сколько символов уже напечатано на экране
    private int streamStartLine = 0;                       // с какой строки экрана начинается ответ
    private boolean followStream = true;                   // экран следует за печатью
    private long streamLastStep = 0;
    private String streamChat = "";
    private String streamPrompt = "";
    private String streamModel = "";
    volatile String streamSid = null;              // номер потока на прокси (для отмены)
 
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
                        int have = Omega.chatCharCount(cName);
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
                            byte[] sj = bos.toByteArray();
                            bos = null;
                            int s0 = 0, e0 = sj.length;
                            while (s0 < e0 && sj[s0] <= ' ') s0++;
                            while (e0 > s0 && sj[e0 - 1] <= ' ') e0--;

                            // Локальную копию заменяем, только если серверная новее (длиннее). Работаем с байтами, без копий строкой
                            if (e0 - s0 > 2 && sj[s0] == '[' && sj[e0 - 1] == ']' && Omega.utf8Chars(sj, s0, e0) > have) {
                                Omega.saveChatBytes(cName, sj, s0, e0 - s0);
                                changed = true;
                            }
                        }
                    } catch (Exception e) {
                        // Нет интернета: останется локальная копия
                    } finally {
                        try { if (is != null) is.close(); } catch (Exception e) {}
                        try { if (c != null) c.close(); } catch (Exception e) {}
 
                        if (changed) {
                            midlet.display.callSerially(new Runnable() {
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
                        byte[] sj = bos.toByteArray();
                        bos = null;
                        int s0 = 0, e0 = sj.length;
                        while (s0 < e0 && sj[s0] <= ' ') s0++;
                        while (e0 > s0 && sj[e0 - 1] <= ' ') e0--;
                        if (e0 - s0 > 2 && sj[s0] == '[' && sj[e0 - 1] == ']' && Omega.utf8Chars(sj, s0, e0) > Omega.chatCharCount(chatName)) {
                            Omega.saveChatBytes(chatName, sj, s0, e0 - s0);
                        }
                    }
                } catch (Exception e) {}
                finally {
                    try { if (is != null) is.close(); } catch (Exception e) {}
                    try { if (c != null) c.close(); } catch (Exception e) {}
                    midlet.display.callSerially(new Runnable() {
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

    void rebuildWrappedLines() {
        wrappedLines.removeAllElements();
        lineRoles.removeAllElements();
        lineStyles.removeAllElements();
        msgLineStart.removeAllElements();
        msgAbsIdx.removeAllElements();
        msgRoleV.removeAllElements();
        loadMoreLine = -1;
        backLine = -1;
        // В памяти держим только байты чата. Начала сообщений находим по ним, а в строки превращаем лишь сообщения из окна
        byte[] raw = Omega.readChatBytes(chatName);
        String key = "{\"role\":\"";
        int[] starts = Omega.chatStartsBytes(raw);
        int n = starts.length;
        int jsonEnd = Omega.chatEndBytes(raw);
 
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
            int a = starts[i];
            int b = (i + 1 < n) ? starts[i + 1] - 1 : jsonEnd;
            if (b <= a) continue;
            String obj;
            try { obj = new String(raw, a, b - a, "UTF-8"); } catch (Exception e) { continue; }
            obj = Omega.fixControlChars(obj);
            if (obj.indexOf("\"role\": \"") != -1) obj = normalizeChatJson(obj);
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
    int beginStream(int launch, String chat, String prompt, String model) {
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
        midlet.display.callSerially(new Runnable() {
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
    void streamPush(String piece) {
        synchronized (streamLock) {
            streamBuf.append(piece);
            loadingStatusText = "stream " + streamBuf.length() + " chars";
        }
    }
 
    // Сеть закончила без ошибок: ответ сразу сохраняется в чат, а на экране он допечатывается
    void endStreamOk(int tok) {
        String full;
        synchronized (streamLock) {
            if (tok != streamToken || streamSaved) return;
            streamSaved = true;
            streamDone = true;
            full = streamBuf.toString();
        }
        streamSid = null;
        midlet.appendToChatRms(streamChat, streamPrompt, full, streamModel);
    }
 
    // Ошибка посреди потока: оставляем на экране то, что успело прийти, и пишем ошибку (в историю это не попадает)
    void endStreamErr(final int tok, final String msg) {
        synchronized (streamLock) {
            if (tok != streamToken) return;
            streamToken++;
            streaming = false;
        }
        streamSid = null;
        midlet.display.callSerially(new Runnable() {
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
        if (partial != null) midlet.appendToChatRms(streamChat, streamPrompt, partial, streamModel);
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
            midlet.display.callSerially(new Runnable() {
                public void run() { relayoutStream(); repaint(); }
            });
        } else if (finished) {
            midlet.display.callSerially(new Runnable() {
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
    String streamPiece(String ln, int prov) {
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
    String streamErrorOf(String ln) {
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
        final String rawHistory = Omega.loadChatForSend(chatName, midlet.getCtxLimit());
        final boolean tailCut = Omega.chatTailCut;
 
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
 
                        // Картинка загружена. Дальше всё как у текстового сообщения: урезание контекста по лимиту,
                        // потоковый ответ, сохранение в чат
                        fileBytes = null;
                        TerminalSender ts = new TerminalSender(TerminalCanvas.this, finalImagePrompt, "[IMG] " + promptText, null, rawHistory, chatName, modelAtSend);
                        ts.imgSid = sid;
                        ts.preCut = tailCut;
                        ts.run();
 
                    } catch (final Throwable t) {
                        final String st = loadingStatusText;
                        midlet.display.callSerially(new Runnable() {
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
                        TerminalSender ds = new TerminalSender(TerminalCanvas.this, finalImagePrompt, "[IMG] " + promptText, base64Img, rawHistory, chatName, modelAtSend);
                        ds.preCut = tailCut;
                        new Thread(ds).start();
 
                    } catch (final Throwable t) {
                        final String st = loadingStatusText;
                        midlet.display.callSerially(new Runnable() {
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

    void wrapTextIntoVector(String text, Font font, int maxWidth, Vector outVector) {
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

    int findJsonStringEnd(String s, int start) {
        for (int i = start; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '\\') { i++; continue; }   // пропускаем экранированный символ целиком
            if (ch == '"') return i;
        }
        return s.length();
    }
 
    String unescapeString(String s) {
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
    String shortErr(String body) {
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
 
        String leftBtnStr = fastMode ? "[EXIT FAST]" : "[OPTIONS]";
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
    void stage(String s) {
        loadingStatusText = s;
        repaint();
    }
 
    String sizeStr(int bytes) {
        if (bytes < 1024) return bytes + "B";
        return (bytes / 1024) + "." + ((bytes % 1024) * 10 / 1024) + "KB";
    }
 
    String hostOf(String url) {
        if (url == null) return "";
        int i = url.indexOf("://");
        String s = (i == -1) ? url : url.substring(i + 3);
        int sl = s.indexOf('/');
        if (sl != -1) s = s.substring(0, sl);
        return s;
    }
 
    // Показать ошибку в чате и снять индикатор ожидания
    private void failLoading(final String msg) {
        midlet.display.callSerially(new Runnable() {
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
                midlet.display.setCurrent(TerminalCanvas.this);
            }
        });
        midlet.display.setCurrent(tb);
    }

    // ===== Меню чата (левая софт-клавиша), перегенерация с вариантами ответа, правка последнего вопроса, поиск =====
 
    // Раскрывает старые чаты с пробелами после двоеточий и возвращает историю в компактном виде
    private String loadChatCompact() {
        String json = midlet.loadChatFromRms(chatName);
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
                if (!cmd.getLabel().equals("OK") || q.length() == 0) { midlet.display.setCurrent(TerminalCanvas.this); return; }
                runSearch(q);
            }
        });
        midlet.display.setCurrent(tb);
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
                midlet.display.setCurrent(TerminalCanvas.this);
                if (c == ok) yes.run();
            }
        });
        midlet.display.setCurrent(f);
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
        midlet.saveChatToRms(chatName, json.substring(0, head) + pick + "]");
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
        midlet.saveChatToRms(chatName, chatCut(json, st, keep));
 
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
                if (!cmd.getLabel().equals("OK") || (s.length() == 0 && img == null)) { midlet.display.setCurrent(TerminalCanvas.this); return; }
                final String text = s;
                Runnable go = new Runnable() {
                    public void run() {
                        deleteRms(altStoreName());
                        resendFrom(abs, text, img);
                    }
                };
                if (img != null) imageWarning(go);
                else { midlet.display.setCurrent(TerminalCanvas.this); go.run(); }
            }
        });
        midlet.display.setCurrent(tb);
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
                    midlet.display.callSerially(new Runnable() {
                        public void run() {
                            if (!midlet.isNavCurrent(tok)) return;
                            wrapTextIntoVector("Sys: Err opening folder", Omega.uiFont(), getWidth() - 6, wrappedLines);
                            repaint();
                            midlet.display.setCurrent(TerminalCanvas.this);
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
                midlet.display.callSerially(new Runnable() {
                    public void run() {
                        // Если пользователь за это время ушёл на другой экран, чат поверх не показываем
                        if (!midlet.isNavCurrent(tok)) return;
                        if (err != null) {
                            wrapTextIntoVector(err, Omega.uiFont(), getWidth() - 6, wrappedLines);
                        }
                        repaint();
                        midlet.display.setCurrent(TerminalCanvas.this);
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
 
            String jsonHistory = Omega.loadChatForSend(chatName, midlet.getCtxLimit());
            TerminalSender sender = new TerminalSender(this, promptForModel, promptForModel, null, jsonHistory, chatName, midlet.selectedModel);
            sender.preCut = Omega.chatTailCut;
            new Thread(sender).start();
        }
    }

    }
