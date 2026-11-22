import javax.microedition.midlet.*;
import javax.microedition.lcdui.*;
import javax.microedition.io.*;
import javax.microedition.rms.*;
import javax.microedition.io.file.*;
import java.io.*;
import java.util.Vector;
import java.util.Enumeration;

class Scroller {
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
