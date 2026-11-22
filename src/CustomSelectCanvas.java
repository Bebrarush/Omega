import javax.microedition.midlet.*;
import javax.microedition.lcdui.*;
import javax.microedition.io.*;
import javax.microedition.rms.*;
import javax.microedition.io.file.*;
import java.io.*;
import java.util.Vector;
import java.util.Enumeration;

class CustomSelectCanvas extends Canvas {
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

    private int tick = 0;                      // счётчик для бегущих строк
    private boolean marqueeOn = false;            // на экране сейчас есть бегущая строка

    // Таймер бегущих строк: перерисовывает экран только пока есть что двигать
    private void startMarquee() {
        new Thread(new Runnable() {
            public void run() {
                while (true) {
                    try { Thread.sleep(50); } catch (Exception e) {}
                    if (marqueeOn && isShown()) { tick++; repaint(); }
                }
            }
        }).start();
    }

    public CustomSelectCanvas(Omega m) {
        midlet = m;
        setFullScreenMode(true);
        startMarquee();
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

    private String delStage = "";                           // где сейчас застрял запрос (для диагностики)
 
    // Каждый запрос идёт в своём потоке с ограничением по времени: если телефон завис на открытии соединения,
    // этот поток бросаем и пробуем следующий способ. Возвращает пустую строку, текст ошибки или "timeout"
    private String limitedSend(final String base, final String cName, final int mode) {
        final String[] res = new String[1];
        final boolean[] fin = new boolean[1];
        new Thread(new Runnable() {
            public void run() {
                String e = sendDeleteRequest(base, cName, mode);
                synchronized (res) { res[0] = e; fin[0] = true; res.notifyAll(); }
            }
        }).start();
        synchronized (res) {
            long end = System.currentTimeMillis() + 8000;
            while (!fin[0]) {
                long left = end - System.currentTimeMillis();
                if (left <= 0) break;
                try { res.wait(left); } catch (InterruptedException e) {}
            }
        }
        return fin[0] ? res[0] : "timeout@" + delStage;
    }
 
    // То же для проверки по списку чатов: 1 есть, 0 нет, -1 не удалось (в том числе по времени)
    private int limitedCheck(final String base, final String cName) {
        final int[] res = new int[1];
        final boolean[] fin = new boolean[1];
        new Thread(new Runnable() {
            public void run() {
                int r = chatOnServer(base, cName);
                synchronized (res) { res[0] = r; fin[0] = true; res.notifyAll(); }
            }
        }).start();
        synchronized (res) {
            long end = System.currentTimeMillis() + 8000;
            while (!fin[0]) {
                long left = end - System.currentTimeMillis();
                if (left <= 0) break;
                try { res.wait(left); } catch (InterruptedException e) {}
            }
        }
        return fin[0] ? res[0] : -1;
    }
 
    // Порядок способов: сначала GET только на чтение (так же ходит рабочий запрос списка чатов), потом остальные.
    // Номера соответствуют режимам в sendDeleteRequest: 1 GET-R, 0 GET, 2 POST, 3 DELETE
    private void deleteChatOnServer(final String cName) {
        if (midlet.proxyUrl == null || midlet.proxyUrl.trim().length() == 0) { showDelInfo(cName, "server: no proxy URL"); return; }
        String base = midlet.proxyUrl.trim();
        if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        delStage = "start";
        showDelInfo(cName, "server: sending...");
        int[] order = { 1, 0, 2, 3 };
        String[] names = { "GET", "GET-R", "POST", "DELETE" };
        StringBuffer trace = new StringBuffer();
        for (int n = 0; n < order.length; n++) {
            int mode = order[n];
            String err = limitedSend(base, cName, mode);
            int st = limitedCheck(base, cName);
            if (st == 0) { showDelInfo(cName, "server: ok (" + names[mode] + ")"); return; }
            if (st == -1 && !err.startsWith("timeout")) { showDelInfo(cName, "server: check failed (" + names[mode] + (err.length() > 0 ? " " + err : "") + ")"); return; }
            trace.append(names[mode]).append(err.length() > 0 ? ":" + err : ":sent").append(' ');
        }
        showDelInfo(cName, "server: NOT deleted [" + trace.toString().trim() + "]");
    }
 
    // Показывает итог под списком. Прежний текст "server: ..." заменяется новым
    private void showDelInfo(final String cName, final String info) {
        midlet.display.callSerially(new Runnable() {
            public void run() {
                if (currentDeletedNotice.length() > 0 && currentDeletedNotice.indexOf(cName) >= 0) {
                    String cur = currentDeletedNotice;
                    int k = cur.indexOf(" server:");
                    if (k >= 0) cur = cur.substring(0, k);
                    currentDeletedNotice = cur + " " + info;
                    repaint();
                }
            }
        });
    }
 
    // Один запрос на удаление. mode 0: GET как раньше; 1: GET только на чтение; 2: POST; 3: метод DELETE
    private String sendDeleteRequest(String base, String cName, int mode) {
        HttpConnection c = null;
        OutputStream os = null;
        InputStream is = null;
        String err = "";
        try {
            String url = base + "/?type=delete&chat=" + midlet.escapeUrl(cName);
            delStage = "open " + mode;
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
            delStage = "reply " + mode;
            int rc = c.getResponseCode();
            if (rc != 200) err = "HTTP" + rc;
            delStage = "read " + mode;
            is = c.openInputStream();
            while (is.read() != -1) { }        // дочитываем ответ до конца, чтобы запрос точно завершился
        } catch (Throwable e) {
            // Не получилось этим способом: проверка по списку покажет, остался ли чат на сервере
            String m = String.valueOf(e);
            err = m.length() > 30 ? m.substring(0, 30) : m;
        } finally {
            try { if (is != null) is.close(); } catch (Exception e) {}
            try { if (os != null) os.close(); } catch (Exception e) {}
            try { if (c != null) c.close(); } catch (Exception e) {}
        }
        return err;
    }
 
    // Есть ли чат в списке на сервере: 1 да, 0 нет, -1 проверить не удалось
    private int chatOnServer(String base, String cName) {
        HttpConnection c = null;
        InputStream is = null;
        try {
            delStage = "list open";
            c = (HttpConnection) Connector.open(base + "/?type=list", Connector.READ);
            c.setRequestMethod(HttpConnection.GET);
            c.setRequestProperty("User-Agent", "Omega-Symbian");
            delStage = "list reply";
            if (c.getResponseCode() != HttpConnection.HTTP_OK) return -1;
            delStage = "list read";
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
                        midlet.display.callSerially(new Runnable() {
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
        marqueeOn = false;
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
                        int rowBg = isSel ? midlet.colorFocusBg : midlet.colorBg;
                        int nameW = font.stringWidth(itemText);
                        if (nameW + quoteW + 20 <= w) {
                            // Всё влезает: имя слева, реплика справа
                            g.drawString(itemText, 6, y, Graphics.TOP | Graphics.LEFT);
                            g.setColor(midlet.visibleColor(midlet.colorWarn, rowBg));
                            g.drawString(quoteStr, w - quoteW - 6, y, Graphics.TOP | Graphics.LEFT);
                        } else {
                            // Не влезает: имя и реплика бегут одной строкой, обрезать реплику нельзя
                            g.setColor(midlet.visibleColor(midlet.colorWarn, rowBg));
                            if (Omega.drawFit(g, font, itemText + " " + quoteStr, 6, y, w - 12, tick, true)) marqueeOn = true;
                        }
                    } else {
                        String btnStr = "[DEL]";
                        int btnW = font.stringWidth(btnStr) + 8;
                        int maxItemW = w - btnW - 14;
 
                        if (Omega.drawFit(g, font, itemText, 6, y, maxItemW, tick, isSel)) marqueeOn = true;
                        g.setColor(isSel ? 0xFFFFFF : midlet.colorMain);
                        g.drawString(btnStr, w - btnW + 4, y, Graphics.TOP | Graphics.LEFT);
                    }
                } else {
                    if (Omega.drawFit(g, font, itemText, 6, y, w - 12, tick, isSel)) marqueeOn = true;
                }
            }
            g.setClip(0, 0, w, h);
        }
 
        if (currentDeletedNotice.length() > 0) {
            g.setColor(midlet.visibleColor(midlet.colorError, midlet.colorBg));
            // Над софтбаром; длинная реплика бежит по строке, а не обрезается
            if (Omega.drawFit(g, font, currentDeletedNotice, 6, h - bottomBarHeight - 4 - font.getHeight(), w - 12, tick, true)) marqueeOn = true;
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
            public void run() {
                try { deleteChatOnServer(cName); }
                catch (Throwable e) { showDelInfo(cName, "server: crash " + e); }
            }
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
