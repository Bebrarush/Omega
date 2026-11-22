import javax.microedition.midlet.*;
import javax.microedition.lcdui.*;
import javax.microedition.io.*;
import javax.microedition.rms.*;
import javax.microedition.io.file.*;
import java.io.*;
import java.util.Vector;
import java.util.Enumeration;

class TerminalSender implements Runnable {
    private String prm, dispPrm, base64Img, hist, cName, mdl;
    private boolean trimmed = false;   // история в запросе была урезана по лимиту контекста
    private int myTok = -1;            // номер потокового ответа, если он начался
    private int launchTok = 0;         // номер потока на момент запуска отправки (для отмены до начала потока)
    private int directAttempt = 1;     // номер попытки прямой отправки (повторяем при 429/502/503)
    private boolean retryDirect = false;
    private int retryCode = 0;
    boolean preCut = false;          // историю при загрузке уже урезали до хвоста (полный чат не разбирался)
    String imgSid = null;      // номер сеанса, под которым на прокси уже загружена картинка (отправка через прокси)


    private TerminalCanvas tc;          // экран чата, который запустил отправку
    private Omega midlet;

    public TerminalSender(TerminalCanvas canvas, String p, String dispP, String img, String h, String cn, String model) {
        tc = canvas; midlet = canvas.midlet;
        prm = p; dispPrm = dispP; base64Img = img; hist = h; cName = cn; mdl = model;
    }

    public void run() {
        try {
            // В запрос уходит только та часть истории, что влезает в лимит контекста.
            // Полная история остаётся в чате на телефоне
            launchTok = tc.streamToken;
            String before = hist;
            hist = Omega.trimHistory(hist, midlet.getCtxLimit());
            trimmed = preCut || (before != null && hist != null && hist.length() != before.length());
 
            byte[] histBytes = hist != null ? hist.getBytes("UTF-8") : new byte[0];
            int histSizeKb = histBytes.length / 1024;
 
            if (imgSid != null || midlet.proxyMode == 2 || (midlet.proxyMode == 1 && histSizeKb > 12)) {
                sendChatViaProxy(histBytes);
            } else {
                // Временные отказы провайдера (429 лимит, 502/503 перегрузка): пробуем ещё до двух раз с паузой
                for (directAttempt = 1; directAttempt <= 3; directAttempt++) {
                    retryDirect = false;
                    sendChatDirect();
                    if (!retryDirect) break;
                    int waitSec = directAttempt * 3;
                    for (int s = waitSec; s > 0; s--) {
                        if (!tc.isLoading) return;          // ожидание отменили
                        tc.stage("HTTP " + retryCode + ", retry " + (directAttempt + 1) + "/3 in " + s + "s");
                        try { Thread.sleep(1000); } catch (Exception e) {}
                    }
                }
            }
        } catch (final Throwable t) {
            String m0 = t.getMessage();
            if (m0 == null) m0 = t.getClass().getName();
            if (myTok != -1) {
                tc.endStreamErr(myTok, m0);
            } else {
                final String msg = m0;
                midlet.display.callSerially(new Runnable() {
                    public void run() {
                        tc.isLoading = false;
                        tc.loadingStatusText = "[...]";
                        tc.wrapTextIntoVector("Sys: Err " + msg, Omega.uiFont(), tc.getWidth() - 6, tc.wrappedLines);
                        tc.repaint();
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
        final boolean wantStream = midlet.isStreamOn();
        final String streamFlag = wantStream ? "true" : "false";
        try {
            String url = midlet.CHAT_URLS[midlet.selectedProvider];
 
            tc.stage("connect " + tc.hostOf(url));
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
 
            tc.stage("build request");
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
                        int cEnd = tc.findJsonStringEnd(historyInner, cIdx + 11);
                        String content = tc.unescapeString(historyInner.substring(cIdx + 11, cEnd));
 
                        if (role.equals("user")) ollamaPrompt.append("User: ").append(content).append("\n");
                        else if (role.equals("assistant")) ollamaPrompt.append("Assistant: ").append(content).append("\n");
                        pos = cEnd;
                    }
                }
                ollamaPrompt.append("User: ").append(prm).append("\nAssistant:");
                body.write(("{\"model\":\"" + midlet.selectedModel + "\",\"prompt\":\"" + escape(ollamaPrompt.toString()) + "\",\"stream\":" + streamFlag + ((base64Img != null && base64Img.length() > 0) ? ",\"images\":[\"" + base64Img + "\"]" : "") + midlet.paramsJson(0) + "}").getBytes("UTF-8"));
 
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
 
            tc.stage("send " + tc.sizeStr(payload.length));
            os = c.openOutputStream();
            os.write(payload);
            os.flush();
 
            tc.stage("wait model");
            int code = c.getResponseCode();
            if (wantStream && code == HttpConnection.HTTP_OK) {
                tc.stage("read stream");
                is = c.openInputStream();
                readDirectStream(is);
                return;
            }
            tc.stage("read reply HTTP " + code);
            is = c.openInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            int ch;
            while ((ch = is.read()) != -1) bos.write(ch);
            
            tc.stage("parse " + tc.sizeStr(bos.size()));
            String responseString = new String(bos.toByteArray(), "UTF-8").trim();
            if (responseString.startsWith("<!DOCTYPE") || responseString.startsWith("<html")) {
                throw new Exception("Server returned HTML page instead of text.");
            }
 
            if (code == HttpConnection.HTTP_OK) {
                final String resp = parseResp(responseString, midlet.selectedProvider);
                tc.stage("save chat");
                midlet.appendToChatRms(cName, dispPrm, resp, mdl);
 
                midlet.display.callSerially(new Runnable() {
                    public void run() {
                        tc.isLoading = false;
                        tc.loadingStatusText = "[...]";
                        tc.rebuildWrappedLines();
                        tc.repaint();
                    }
                });
            } else {
                if ((code == 429 || code == 502 || code == 503) && directAttempt < 3) {
                    retryDirect = true;
                    retryCode = code;
                    return;
                }
                final String errText = "HTTP " + code + ": " + tc.shortErr(responseString);
                midlet.display.callSerially(new Runnable() {
                    public void run() {
                        tc.isLoading = false;
                        tc.loadingStatusText = "[...]";
                        tc.wrapTextIntoVector("Sys: " + errText, Omega.uiFont(), tc.getWidth() - 6, tc.wrappedLines);
                        tc.repaint();
                    }
                });
            }
        } catch (final Throwable t) {
            String m0 = t.getMessage();
            if (m0 == null) m0 = t.getClass().getName();
            if (myTok != -1) {
                // Поток уже начался: показываем то, что пришло, и ошибку под ним
                tc.endStreamErr(myTok, m0);
            } else {
                final String st = tc.loadingStatusText;
                final String msg = m0;
                midlet.display.callSerially(new Runnable() {
                    public void run() {
                        tc.isLoading = false;
                        tc.loadingStatusText = "[...]";
                        if (msg.indexOf("36") != -1 || msg.indexOf("length") != -1 || msg.indexOf("size") != -1) {
                            tc.wrapTextIntoVector("Sys: POST exceeds 16kbytes, direct connection will not work on your device properly. Please, setup proxy", Omega.uiFont(), tc.getWidth() - 6, tc.wrappedLines);
                        } else {
                            tc.wrapTextIntoVector("Sys: Err " + msg + " @" + st, Omega.uiFont(), tc.getWidth() - 6, tc.wrappedLines);
                        }
                        tc.repaint();
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
        myTok = tc.beginStream(launchTok, cName, dispPrm, mdl);
        if (myTok == -1) return;                     // отменили до начала потока
        final int tok = myTok;
 
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        StringBuffer raw = new StringBuffer();    // пока нет ни одного куска текста, копим сырой ответ: вдруг провайдер не умеет стримить
        boolean got = false;
        int ch;
        while (tok == tc.streamToken && (ch = is.read()) != -1) {
            if (ch != '\n') { line.write(ch); continue; }
 
            String ln = new String(line.toByteArray(), "UTF-8").trim();
            line.reset();
            if (ln.length() == 0 || ln.startsWith(":") || ln.startsWith("event:") || ln.startsWith("id:") || ln.startsWith("retry:")) continue;
            if (ln.startsWith("data:")) ln = ln.substring(5).trim();
            if (ln.equals("[DONE]")) break;
 
            String piece = tc.streamPiece(ln, prov);
            if (piece != null) {
                if (piece.length() > 0) { got = true; tc.streamPush(piece); }
                if (prov == 0 && ln.indexOf("\"done\":true") != -1) break;
            } else {
                String err = tc.streamErrorOf(ln);
                if (err != null) throw new Exception(err);
                if (!got && raw.length() < 65536) raw.append(ln).append('\n');
            }
        }
        if (tok != tc.streamToken) return;               // ответ отменили
 
        if (!got) {
            if (raw.length() == 0) throw new Exception("Empty stream");
            tc.streamPush(parseResp(raw.toString().trim(), prov));
        }
        tc.endStreamOk(tok);
    }
 
    // Потоковый ответ через прокси: прокси читает провайдера сам, телефон раз в ~0,7 с забирает новый текст
    private void pollProxyStream(String proxyBase, String sid) throws Exception {
        myTok = tc.beginStream(launchTok, cName, dispPrm, mdl);
        if (myTok == -1) return;                     // отменили до начала потока
        final int tok = myTok;
        tc.streamSid = sid;
        tc.stage("wait model via proxy");
 
        int from = 0;
        int fails = 0;
        long lastProgress = System.currentTimeMillis();
        while (tok == tc.streamToken) {
            try { Thread.sleep(700); } catch (Exception e) {}
            if (tok != tc.streamToken) return;
 
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
                tc.streamPush(rest);
                lastProgress = System.currentTimeMillis();
            }
            if (state == 'D') {
                tc.endStreamOk(tok);
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
            tc.stage("build request");
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
 
            final String sid = (imgSid != null) ? imgSid : "omega_chat_" + System.currentTimeMillis();
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
 
                tc.stage("send " + (i + 1) + "/" + totalChunks + " (" + tc.sizeStr(chunkBuf.length) + ")" + (i == 0 ? " > " + tc.hostOf(proxyBase) : ""));
 
                String chunkUrl = proxyBase + "/?sid=" + sid +
                                  "&type=chat" +
                                  "&chat=" + midlet.escapeUrl(cName) +
                                  "&chunk=" + (i + 1) +
                                  "&total=" + totalChunks +
                                  "&final=" + (isFinal ? "1" : "0") +
                                  "&prov=" + midlet.selectedProvider +
                                  (wantStream ? "&stream=1" : "") +
                                  (imgSid != null ? "&img=1" : "") +
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
 
                    if (isFinal) tc.stage(attempt == 0 ? "wait model via proxy" : "retry via proxy");
                    code = c.getResponseCode();
                    if (isFinal) tc.stage("read reply HTTP " + code);
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
                    tc.stage("save chat");
                    midlet.appendToChatRms(cName, dispPrm, finalResult, mdl);
 
                    midlet.display.callSerially(new Runnable() {
                        public void run() {
                            tc.isLoading = false;
                            tc.loadingStatusText = "[...]";
                            tc.rebuildWrappedLines();
                            tc.repaint();
                        }
                    });
                    break;
                }
            }
 
        } catch (final Exception e) {
            String m0 = e.getMessage();
            if (m0 == null) m0 = e.getClass().getName();
            if (myTok != -1) {
                tc.endStreamErr(myTok, m0);
            } else {
                final String st = tc.loadingStatusText;
                final String errMessage = m0;
                midlet.display.callSerially(new Runnable() {
                    public void run() {
                        tc.isLoading = false;
                        tc.loadingStatusText = "[...]";
                        tc.wrapTextIntoVector("Sys: Proxy Err: " + errMessage + " @" + st, Omega.uiFont(), tc.getWidth() - 6, tc.wrappedLines);
                        tc.repaint();
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
