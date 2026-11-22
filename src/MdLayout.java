import javax.microedition.midlet.*;
import javax.microedition.lcdui.*;
import javax.microedition.io.*;
import javax.microedition.rms.*;
import javax.microedition.io.file.*;
import java.io.*;
import java.util.Vector;
import java.util.Enumeration;

// ===== Разметка текста сообщений: markdown, перенос строк по таблице ширин символов =====
// Не зависит от экрана: получает шрифт, ширину строки и три вектора для результата.
class MdLayout {
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
