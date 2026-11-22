import javax.microedition.midlet.*;
import javax.microedition.lcdui.*;
import javax.microedition.io.*;
import javax.microedition.rms.*;
import javax.microedition.io.file.*;
import java.io.*;
import java.util.Vector;
import java.util.Enumeration;

class ThemeCanvas extends Canvas {
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

        // Слоты темы: 0 фон, 1 основной, 2 шапка, 3 выделение строки, 4 нижняя панель, 5 предупреждение, 6 ошибка
    private final String[] COLOR_SLOT_NAMES = {"[BG COLOR]", "[MAIN COLOR]", "[HEADER BG]", "[FOCUS BG]", "[SOFTBAR BG]", "[WARNING COLOR]", "[ERROR COLOR]"};
    private final int NSLOT = 7;
    private final String[] CONFIRM_QUOTES = {"sure?"};
    private final String[] DELETED_QUOTES = {"was deleted.", "was destroyed.", "was obliterated."};

    private final Scroller scroller = new Scroller(this);
    private boolean pressInBar = false;

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

    public ThemeCanvas(Omega m) {
        midlet = m;
        setFullScreenMode(true);
        refreshThemeList();
        startMarquee();
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
        marqueeOn = false;
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
            int[] currentColors = {midlet.colorBg, midlet.colorMain, midlet.colorHeaderBg, midlet.colorFocusBg, midlet.colorSoftbarBg, midlet.colorWarn, midlet.colorError};
            wheelImg = null;                                // круг больше не нужен: память освобождается
 
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
                    int rowBg = isSel ? midlet.colorFocusBg : midlet.colorBg;
                    if (confirming && font.stringWidth(itemText) + rightW + 8 > w) {
                        // Не влезает: имя и реплика бегут одной строкой
                        g.setColor(midlet.visibleColor(midlet.colorWarn, rowBg));
                        if (Omega.drawFit(g, font, itemText + " " + rightStr, 6, y, w - 12, tick, true)) marqueeOn = true;
                    } else {
                        if (Omega.drawFit(g, font, itemText, 6, y, w - rightW - 8, tick, isSel)) marqueeOn = true;
                        if (confirming) {
                            g.setColor(midlet.visibleColor(midlet.colorWarn, rowBg));
                            g.drawString(rightStr, w - font.stringWidth(rightStr) - 6, y, Graphics.TOP | Graphics.LEFT);
                        } else {
                            g.setColor(isSel ? 0xFFFFFF : midlet.colorMain);
                            g.drawString(rightStr, w - rightW, y, Graphics.TOP | Graphics.LEFT);
                        }
                    }
                } else if (showPaletteMenu && i > items.size() - (NSLOT + 1)) {
                    int slotIdx = i - (items.size() - NSLOT);
                    if (Omega.drawFit(g, font, itemText, 6, y, w - 56, tick, isSel)) marqueeOn = true;
                    g.setColor(currentColors[slotIdx]);
                    g.fillRect(w - 44, y, 32, fh - 4);
                    g.setColor(midlet.colorMain);
                    g.drawRect(w - 44, y, 32, fh - 4);
                } else {
                    if (Omega.drawFit(g, font, itemText, 6, y, w - 12, tick, isSel)) marqueeOn = true;
                }
            }
            g.setClip(0, 0, w, h);
 
            if (currentDeletedNotice.length() > 0) {
                g.setColor(midlet.visibleColor(midlet.colorError, midlet.colorBg));
                if (Omega.drawFit(g, font, currentDeletedNotice, 6, h - bottomBarHeight - 4 - font.getHeight(), w - 12, tick, true)) marqueeOn = true;
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

        // ----- Цветовой круг: оттенок по углу, насыщенность по расстоянию от центра -----
    private Image wheelImg = null;                          // готовая картинка круга (создаётся при открытии, освобождается при выходе)
    private int wheelD = 0;                                 // диаметр картинки
    private int wcx = 0, wcy = 0, wR = 0;                   // центр и радиус круга на экране

    // Угол точки (dx, dy) относительно центра в градусах 0..359, считается по часовой стрелке от направления вправо.
    // В CLDC нет atan, поэтому приближение: ошибка меньше четверти градуса
    private int angleOf(int dx, int dy) {
        if (dx == 0 && dy == 0) return 0;
        int ax = dx < 0 ? -dx : dx;
        int ay = dy < 0 ? -dy : dy;
        float t = (ax >= ay) ? (float) ay / (float) ax : (float) ax / (float) ay;
        float a = 45f * t + 15.64f * t * (1f - t);
        if (ax < ay) a = 90f - a;
        if (dx >= 0 && dy < 0) a = 360f - a;
        else if (dx < 0 && dy >= 0) a = 180f - a;
        else if (dx < 0 && dy < 0) a = 180f + a;
        int r = (int) (a + 0.5f);
        return r % 360;
    }

    private int hsvInt(int h, int s, int v) {
        h = ((h % 360) + 360) % 360;
        int i = h / 60;
        int f = ((h % 60) * 255) / 60;
        int p = (v * (255 - s)) / 255;
        int q = (v * (255 - (s * f) / 255)) / 255;
        int t = (v * (255 - (s * (255 - f)) / 255)) / 255;
        int r, g, b;
        switch (i) {
            case 0: r = v; g = t; b = p; break;
            case 1: r = q; g = v; b = p; break;
            case 2: r = p; g = v; b = t; break;
            case 3: r = p; g = q; b = v; break;
            case 4: r = t; g = p; b = v; break;
            default: r = v; g = p; b = q; break;
        }
        return (r << 16) | (g << 8) | b;
    }

    // Рисует круг в картинку (яркость на круг не влияет, её задаёт ползунок Val). Вне круга пиксели прозрачные
    private Image buildWheel(int d) {
        try {
            int r = d / 2;
            int r2 = r * r;
            int[] px = new int[d * d];
            for (int y = 0; y < d; y++) {
                int dy = y - r;
                for (int x = 0; x < d; x++) {
                    int dx = x - r;
                    int dd = dx * dx + dy * dy;
                    if (dd > r2) continue;
                    int s = (int) (Math.sqrt((double) dd) * 255.0 / (double) r);
                    if (s > 255) s = 255;
                    px[y * d + x] = 0xFF000000 | hsvInt(angleOf(dx, dy), s, 255);
                }
            }
            Image im = Image.createRGBImage(px, d, d, true);
            px = null;
            return im;
        } catch (Throwable e) {
            return null;                                    // не хватило памяти: останутся ползунки
        }
    }


    private void drawColorWheel(Graphics g, int w, int h) {
        Font font = Omega.uiFont();
        int satY = h - 95;
        int valY = h - 55;
        int top = font.getHeight() + 16;
        int bottom = satY - 24;
        int d = bottom - top;
        if (d > w - 16) d = w - 16;
        if (d > 128) d = 128;                               // крупнее не нужно, а память на S40 небольшая
        if (d < 24) d = 24;
        d = d - (d % 2);
        wR = d / 2;
        wcx = w / 2;
        wcy = top + (bottom - top) / 2;

        if (wheelImg == null || wheelD != d) {
            wheelImg = buildWheel(d);
            wheelD = d;
        }
        if (wheelImg != null) {
            g.drawImage(wheelImg, wcx - wR, wcy - wR, Graphics.TOP | Graphics.LEFT);
        }
        g.setColor(midlet.colorMain);
        g.drawArc(wcx - wR, wcy - wR, d, d, 0, 360);

        // Маркер выбранной точки
        double rad = hue * Math.PI / 180.0;
        int mr = (sat * wR) / 255;
        int kx = wcx + (int) (Math.cos(rad) * mr);
        int ky = wcy + (int) (Math.sin(rad) * mr);
        g.setColor(0x000000);
        g.drawArc(kx - 5, ky - 5, 10, 10, 0, 360);
        g.setColor(0xFFFFFF);
        g.drawArc(kx - 4, ky - 4, 8, 8, 0, 360);

        int cur = hsvInt(hue, sat, val);

        // Sat: градиент от серого до чистого цвета
        g.setColor(0xFFFFFF);
        g.drawString("Sat: " + sat, 20, satY - 18, Graphics.TOP | Graphics.LEFT);
        for (int x = 20; x < w - 20; x += 2) {
            g.setColor(hsvInt(hue, ((x - 20) * 255) / (w - 40), val));
            g.fillRect(x, satY, 2, 8);
        }
        g.setColor(activeHsvControl == 1 ? midlet.colorWarn : midlet.colorMain);
        g.drawRect(20, satY, w - 40, 8);
        int satX = 20 + ((w - 40) * sat) / 255;
        g.setColor(0xFFFFFF);
        g.fillRect(satX - 3, satY - 4, 6, 16);

        // Val: градиент от чёрного до яркого
        g.setColor(0xFFFFFF);
        g.drawString("Val: " + val, 20, valY - 18, Graphics.TOP | Graphics.LEFT);
        for (int x = 20; x < w - 20; x += 2) {
            g.setColor(hsvInt(hue, sat, ((x - 20) * 255) / (w - 40)));
            g.fillRect(x, valY, 2, 8);
        }
        g.setColor(activeHsvControl == 2 ? midlet.colorWarn : midlet.colorMain);
        g.drawRect(20, valY, w - 40, 8);
        int valX = 20 + ((w - 40) * val) / 255;
        g.setColor(0xFFFFFF);
        g.fillRect(valX - 3, valY - 4, 6, 16);

        // Итоговый цвет: образец и код. Образец справа в строке Sat, код справа в строке Val
        g.setColor(cur);
        g.fillRect(w - 62, satY - 17, 42, font.getHeight() - 2);
        g.setColor(0xFFFFFF);
        g.drawRect(w - 62, satY - 17, 42, font.getHeight() - 2);
        String hex = Integer.toHexString(cur).toUpperCase();
        while (hex.length() < 6) hex = "0" + hex;
        g.drawString("#" + hex, w - 20 - font.stringWidth("#" + hex), valY - 18, Graphics.TOP | Graphics.LEFT);
        if (activeHsvControl == 0) {
            g.drawString("Hue: " + hue, 8, top - 2, Graphics.TOP | Graphics.LEFT);
        }
    }

    protected void keyPressed(int keyCode) {
        int ga = getGameAction(keyCode);
        currentDeletedNotice = "";
 
        if (isEditingColor) {
            if (ga == UP) { if (activeHsvControl > 0) activeHsvControl--; repaint(); return; }
            else if (ga == DOWN) { if (activeHsvControl < 2) activeHsvControl++; repaint(); return; }
 
            if (activeHsvControl == 0) {
                if (ga == LEFT) hue = (hue - 6 + 360) % 360;
                else if (ga == RIGHT) hue = (hue + 6) % 360;
            } else if (activeHsvControl == 1) {
                if (ga == LEFT) sat = Math.max(0, sat - 8);
                else if (ga == RIGHT) sat = Math.min(255, sat + 8);
            } else if (activeHsvControl == 2) {
                if (ga == LEFT) val = Math.max(0, val - 8);
                else if (ga == RIGHT) val = Math.min(255, val + 8);
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
        int w = getWidth(); int h = getHeight();
        if (y >= h - 110 && y <= h - 80) {
            activeHsvControl = 1; sat = Math.max(0, Math.min(255, ((x - 20) * 255) / (w - 40)));
        } else if (y >= h - 70 && y <= h - 40) {
            activeHsvControl = 2; val = Math.max(0, Math.min(255, ((x - 20) * 255) / (w - 40)));
        } else if (wR > 0) {
            // Круг: угол даёт оттенок, расстояние от центра насыщенность. Палец может выходить за круг, значения ограничиваются
            activeHsvControl = 0;
            int dx = x - wcx;
            int dy = y - wcy;
            hue = angleOf(dx, dy);
            int dist = (int) Math.sqrt((double) (dx * dx + dy * dy));
            sat = Math.min(255, (dist * 255) / wR);
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

    private int slotColor(int slot) {
        if (slot == 0) return midlet.colorBg;
        if (slot == 1) return midlet.colorMain;
        if (slot == 2) return midlet.colorHeaderBg;
        if (slot == 3) return midlet.colorFocusBg;
        if (slot == 4) return midlet.colorSoftbarBg;
        if (slot == 5) return midlet.colorWarn;
        return midlet.colorError;
    }

    private void handleSelection() {
        if (showPaletteMenu) {
            if (selectedIdx == items.size() - (NSLOT + 1)) { showPaletteMenu = false; refreshThemeList(); }
            else if (selectedIdx > items.size() - (NSLOT + 1)) {
                targetSlot = selectedIdx - (items.size() - NSLOT);
                rgbToHsv(slotColor(targetSlot)); activeHsvControl = 0; isEditingColor = true;
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
        // Шапка, нижняя панель и выделение следуют за фоном и основным цветом, пока их не задали вручную
        int oldDerived = (midlet.colorBg == 0) ? 0x222222 : midlet.colorBg;
        boolean headerFollows = (midlet.colorHeaderBg == oldDerived);
        boolean softbarFollows = (midlet.colorSoftbarBg == oldDerived);
        boolean focusFollows = (midlet.colorFocusBg == midlet.colorMain);

        if (targetSlot == 0) midlet.colorBg = rgb;
        else if (targetSlot == 1) midlet.colorMain = rgb;
        else if (targetSlot == 2) midlet.colorHeaderBg = rgb;
        else if (targetSlot == 3) midlet.colorFocusBg = rgb;
        else if (targetSlot == 4) midlet.colorSoftbarBg = rgb;
        else if (targetSlot == 5) midlet.colorWarn = rgb;
        else if (targetSlot == 6) midlet.colorError = rgb;

        if (targetSlot == 0) {
            int nd = (midlet.colorBg == 0) ? 0x222222 : midlet.colorBg;
            if (headerFollows) midlet.colorHeaderBg = nd;
            if (softbarFollows) midlet.colorSoftbarBg = nd;
        }
        if (targetSlot == 1 && focusFollows) midlet.colorFocusBg = midlet.colorMain;
        midlet.resetDynamicStatusColors();
        midlet.saveTheme(midlet.currentThemeName);
        refreshThemeList();
    }

    private int hsvToRgb(int h, int s, int v) {
        return hsvInt(h, s, v);
    }
}
