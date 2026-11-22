import javax.microedition.midlet.*;
import javax.microedition.lcdui.*;
import javax.microedition.io.*;
import javax.microedition.rms.*;
import javax.microedition.io.file.*;
import java.io.*;
import java.util.Vector;
import java.util.Enumeration;

class ConfigCanvas extends Canvas {
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
            "[PROVIDER: " + midlet.PROVIDER_NAMES[midlet.selectedProvider] + "]",
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
            midlet.notifyDestroyed();
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
                else midlet.notifyDestroyed(); // Right: EXIT
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
            for (int i = 0; i < midlet.PROVIDER_NAMES.length; i++) provs.addElement(midlet.PROVIDER_NAMES[i]);
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
            Form aboutForm = new Form("Omega v1.0.13");
            aboutForm.append(new StringItem("", "Universal J2ME AI Client.\n"));
            
            aboutForm.append(new StringItem("Shortcuts:", "- D-Pad Up / Down: Scroll\n- Fire: Full text input\n- Left Softkey: Chat / Select\n- Right Softkey: Exit / Back\n- Key '0': Delete chat\n"));

            aboutForm.append(new StringItem("\nStart log:", midlet.bootLast() + "\n"));

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
            String targetUrl = midlet.PROVIDER_URLS[midlet.selectedProvider];
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
