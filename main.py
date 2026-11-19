import asyncio
import base64
import json
import os
import threading
import time
from typing import Any, Optional
from urllib.parse import quote, unquote

import httpx
from fastapi import FastAPI, Request
from fastapi.responses import PlainTextResponse, Response

app = FastAPI()

# Хранилища в памяти процесса (аналог глобальных Map в воркере)
sessions: dict[str, dict[str, Any]] = {}
server_chats: dict[str, str] = {}
streams: dict[str, dict[str, Any]] = {}   # потоковые ответы: sid -> состояние

# Чаты хранятся ещё и в файле рядом с main.py, чтобы переживать Reload и перезапуски
CHATS_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "omega_chats.json")


def load_chats() -> None:
    try:
        with open(CHATS_FILE, "r", encoding="utf-8") as f:
            data = json.load(f)
        if isinstance(data, dict):
            for k, v in data.items():
                if isinstance(k, str) and isinstance(v, str):
                    server_chats[k] = v
    except Exception:
        pass


def save_chats() -> None:
    try:
        tmp = CHATS_FILE + ".tmp"
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump(server_chats, f, ensure_ascii=False)
        os.replace(tmp, CHATS_FILE)
    except Exception:
        pass


load_chats()

DESKTOP_HEADERS = {
    "Content-Type": "application/json",
    "User-Agent": (
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
        "(KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
    ),
}

SESSION_TTL = 120  # секунд

# Временные отказы провайдера (лимит, перегрузка): повторяем запрос через паузу, телефону ждать не нужно
RETRY_STATUS = (429, 502, 503)
RETRY_DELAYS = (2.0, 5.0)

ALLOWED_PARAMS = ("temperature", "top_p", "max_tokens", "frequency_penalty", "presence_penalty")

CHAT_ENDPOINTS = {
    1: "https://api.voidai.app/v1/chat/completions",
    2: "https://opencode.ai/inference/openai/v1/chat/completions",
    3: "https://api.groq.com/openai/v1/chat/completions",
}
DEFAULT_CHAT_ENDPOINT = "https://openrouter.ai/api/v1/chat/completions"

MODELS_ENDPOINTS = {
    0: "https://ollama.com/api/tags",
    1: "https://api.voidai.app/v1/models",
    2: "https://opencode.ai/inference/v1/models",
    3: "https://api.groq.com/openai/v1/models",
    4: "https://openrouter.ai/api/v1/models",
}


def text_response(body: str, status: int = 200) -> Response:
    return PlainTextResponse(body, status_code=status, media_type="text/plain; charset=utf-8")


def json_response(body: str) -> Response:
    return Response(body, media_type="application/json; charset=utf-8")


# Журнал последних запросов (без ключей): помогает понять, что именно шлёт телефон. Смотреть: /?type=log
REQ_LOG: list = []
LOG_TYPES = ("delete", "del", "rm", "remove", "list", "getchat", "update", "jad", "jar")


def note_request(request: Request, req_type: Optional[str]) -> None:
    try:
        if req_type not in LOG_TYPES and request.method != "DELETE":
            return
        q = request.query_params
        entry = "{} {} {} type={} chat={} ua={}".format(
            time.strftime("%H:%M:%S"), request.method, request.url.path, req_type or "-",
            (q.get("chat") or "-")[:40], (request.headers.get("user-agent") or "-")[:40])
        REQ_LOG.append(entry)
        del REQ_LOG[:-60]
    except Exception:
        pass


# Обновление приложения с GitHub: файлы берутся только с raw.githubusercontent.com и только по проверенным именам
GITHUB_RAW = "https://raw.githubusercontent.com"
MAX_JAR = 4_000_000


def _ok(value: str, allowed: str, maxlen: int) -> bool:
    return len(value) <= maxlen and ".." not in value and all(c.isalnum() or c in allowed for c in value)


def update_params(q) -> Optional[dict]:
    repo = (q.get("repo") or "").strip()
    branch = (q.get("branch") or "main").strip()
    folder = (q.get("folder") or "").strip().strip("/")
    name = (q.get("name") or "Omega").strip()
    if repo.count("/") != 1 or not _ok(repo, "/_.-", 160) or not all(repo.split("/")):
        return None
    if not branch or not _ok(branch, "/_.-", 100) or not _ok(folder, "/_.-", 100):
        return None
    if not name or not _ok(name, "_.-", 60):
        return None
    return {"repo": repo, "branch": branch, "folder": folder, "name": name}


def update_file_url(p: dict, ext: str) -> str:
    parts = [p["repo"], p["branch"]] + ([p["folder"]] if p["folder"] else []) + [p["name"] + ext]
    return GITHUB_RAW + "/" + "/".join(quote(x, safe="/") for x in parts)


async def fetch_github(url: str):
    async with httpx.AsyncClient(timeout=60, follow_redirects=True) as client:
        return await client.get(url, headers={"User-Agent": "Omega-Proxy"})


def rewrite_jad(jad: str, jar_url: str) -> str:
    """Подменяет MIDlet-Jar-URL на адрес, по которому JAR отдаёт этот же прокси."""
    out = []
    done = False
    for line in jad.splitlines():
        if line.lower().startswith("midlet-jar-url:"):
            out.append("MIDlet-Jar-URL: " + jar_url)
            done = True
        elif line.strip():
            out.append(line)
    if not done:
        out.append("MIDlet-Jar-URL: " + jar_url)
    return "\r\n".join(out) + "\r\n"


def clean_chat_name(param: Optional[str]) -> str:
    if not param:
        return "default_chat"
    return unquote(param.replace("+", " ")).strip()


def fix_header(value: Optional[str]) -> str:
    """Starlette декодирует заголовки как latin-1; если клиент слал UTF-8 (кириллица), чиним."""
    if not value:
        return ""
    try:
        return value.encode("latin-1").decode("utf-8")
    except (UnicodeEncodeError, UnicodeDecodeError):
        return value


def to_int(value: Optional[str], default: int) -> int:
    try:
        return int(value) if value is not None else default
    except ValueError:
        return default


def parse_params(raw: str) -> dict:
    """Параметры генерации из заголовка/метаданных: только известные имена и только числа.
    Понимает оба формата: temperature=0.7;max_tokens=256 и {"temperature":0.7}."""
    if not raw:
        return {}
    raw = raw.strip()
    data: dict[str, Any] = {}
    if raw.startswith("{"):
        try:
            parsed = json.loads(raw)
            if isinstance(parsed, dict):
                data = parsed
        except Exception:
            data = {}
    else:
        for part in raw.split(";"):
            if "=" not in part:
                continue
            k, v = part.split("=", 1)
            try:
                num = float(v.strip().replace(",", "."))
            except ValueError:
                continue
            data[k.strip()] = int(num) if num == int(num) and "." not in v else num
    out: dict[str, Any] = {}
    for k in ALLOWED_PARAMS:
        v = data.get(k)
        if isinstance(v, (int, float)) and not isinstance(v, bool):
            out[k] = v
    return out


def parse_fallbacks(raw: str, model: str) -> list:
    """Запасные модели OpenRouter из заголовка: id через запятую, не больше 5, только допустимые символы."""
    out: list[str] = []
    for part in (raw or "").split(","):
        m = part.strip()
        if m and len(m) <= 80 and m != model and m not in out and all(c.isalnum() or c in "/-_.:~" for c in m):
            out.append(m)
    return out[:5]


def apply_params(payload: dict, prov: int, params: dict) -> None:
    """Добавляет параметры в тело запроса. Если параметров нет, тело не меняется."""
    if not params:
        return
    if prov == 0:  # Ollama: параметры лежат в options, max_tokens называется num_predict
        payload["options"] = {("num_predict" if k == "max_tokens" else k): v for k, v in params.items()}
    else:
        payload.update(params)


def sniff_mime(data: bytes) -> str:
    """Тип картинки по первым байтам (клиент всегда говорил jpeg, даже для png/gif/webp)."""
    if data.startswith(b"\xff\xd8\xff"):
        return "image/jpeg"
    if data.startswith(b"\x89PNG\r\n\x1a\n"):
        return "image/png"
    if data[:6] in (b"GIF87a", b"GIF89a"):
        return "image/gif"
    if data[:4] == b"RIFF" and data[8:12] == b"WEBP":
        return "image/webp"
    return "image/jpeg"


def clean_history(history: Any) -> list:
    """Убирает из истории то, что сбивает модель: пометку [IMG] и записи о неудачных запросах."""
    out: list[dict] = []
    if not isinstance(history, list):
        return out
    for m in history:
        if not isinstance(m, dict):
            continue
        role = m.get("role") or "user"
        content = m.get("content")
        if not isinstance(content, str):
            content = json.dumps(content, ensure_ascii=False)
        if role == "assistant" and content.startswith(("ERR:", "API Error:", "Sys:")):
            if out and out[-1]["role"] == "user":
                out.pop()  # вместе с вопросом, на который пришла ошибка
            continue
        if role == "user" and content.startswith("[IMG] "):
            content = content[6:]
        out.append({"role": role, "content": content})
    return out


def dump_chat(hist: Any) -> str:
    """История чата для клиента: компактный JSON без пробелов, клиент разбирает его простым поиском по тексту."""
    return json.dumps(hist, ensure_ascii=False, separators=(",", ":"))


def check_chunks(chunks: dict, total: int, what: str) -> None:
    """Если куски потерялись по дороге, говорим об этом прямо, а не отправляем битые данные."""
    if total > 0 and set(chunks) != set(range(1, total + 1)):
        raise RuntimeError(f"{what}: got {len(chunks)} of {total} chunks (proxy lost data)")


def make_headers(api_key: str) -> dict[str, str]:
    headers = dict(DESKTOP_HEADERS)
    if api_key:
        headers["Authorization"] = f"Bearer {api_key}"
    return headers


async def fetch_models_from_provider(prov: int, api_key: str) -> str:
    endpoint = MODELS_ENDPOINTS.get(prov, "")
    async with httpx.AsyncClient(timeout=60) as client:
        res = await client.get(endpoint, headers=make_headers(api_key))
    return res.text


def error_text(err: Any, status: int = 0) -> str:
    """Читаемый текст ошибки провайдера. У OpenRouter настоящая причина лежит в metadata.raw."""
    if isinstance(err, str):
        return err
    if isinstance(err, dict):
        msg = err.get("message")
        raw = (err.get("metadata") or {}).get("raw") if isinstance(err.get("metadata"), dict) else None
        code = err.get("code") or status
        parts = [str(p) for p in (f"HTTP {code}" if code else "", raw if isinstance(raw, str) else "", msg if isinstance(msg, str) and not raw else "") if p]
        if parts:
            return ": ".join(parts)
    return json.dumps(err, ensure_ascii=False)


def extract_reply(raw: str, status: int, prov: int, empty_msg: str) -> str:
    """Достаёт текст ответа из обычного (не потокового) JSON провайдера."""
    if raw.strip().startswith("<"):
        raise RuntimeError(f"Provider returned HTML error (HTTP {status})")

    data = json.loads(raw)
    if isinstance(data, dict) and data.get("error"):
        raise RuntimeError(error_text(data["error"], status))

    if prov == 0:
        return data.get("response") or (data.get("message") or {}).get("content") or empty_msg
    choices = data.get("choices") or [{}]
    return (choices[0].get("message") or {}).get("content") or empty_msg


async def post_to_provider(endpoint: str, headers: dict, payload: dict, prov: int,
                           empty_msg: str) -> str:
    async with httpx.AsyncClient(timeout=300) as client:
        for attempt in range(len(RETRY_DELAYS) + 1):
            res = await client.post(endpoint, headers=headers, content=json.dumps(payload))
            if res.status_code in RETRY_STATUS and attempt < len(RETRY_DELAYS):
                await asyncio.sleep(RETRY_DELAYS[attempt])
                continue
            break
    return extract_reply(res.text, res.status_code, prov, empty_msg)


async def send_image_to_network(prov: int, model: str, api_key: str, prompt: str,
                                system_prompt: str, history_str: str, base64_img: str,
                                mime: str = "image/jpeg", params: Optional[dict] = None) -> str:
    headers = make_headers(api_key)

    history: list[dict] = []
    if history_str:
        try:
            history = clean_history(json.loads(history_str, strict=False))
        except Exception:
            pass

    if prov == 0:
        endpoint = "https://ollama.com/api/generate"
        prompt_text = ""
        if system_prompt:
            prompt_text += f"System: {system_prompt}\n\n"
        for msg in history:
            prompt_text += f"{'User' if msg['role'] == 'user' else 'Assistant'}: {msg['content']}\n"
        prompt_text += f"User: {prompt}\nAssistant:"
        payload = {"model": model, "prompt": prompt_text, "images": [base64_img], "stream": False}
        if system_prompt:
            payload["system"] = system_prompt
    else:
        endpoint = CHAT_ENDPOINTS.get(prov, DEFAULT_CHAT_ENDPOINT)
        messages = []
        if system_prompt:
            messages.append({"role": "system", "content": system_prompt})
        messages.extend({"role": m["role"], "content": m["content"]} for m in history)
        messages.append({
            "role": "user",
            "content": [
                {"type": "text", "text": prompt},
                {"type": "image_url", "image_url": {"url": f"data:{mime};base64,{base64_img}"}},
            ],
        })
        payload = {"model": model, "messages": messages, "stream": False}

    apply_params(payload, prov, params or {})
    return await post_to_provider(endpoint, headers, payload, prov, "Sys: Empty response")


def build_chat_request(prov: int, model: str, api_key: str, system_prompt: str,
                       history_json_str: str, params: Optional[dict] = None,
                       fallbacks: Optional[list] = None):
    """Адрес, заголовки и тело запроса к провайдеру (stream выключен: потоковый режим включает вызывающий)."""
    headers = make_headers(api_key)
    # strict=False: в старых чатах клиента могли остаться «сырые» табы и переводы строк внутри текста
    history = clean_history(json.loads(history_json_str, strict=False))

    if prov == 0:
        endpoint = "https://ollama.com/api/generate"
        prompt_text = ""
        for msg in history:
            prompt_text += f"{'User' if msg.get('role') == 'user' else 'Assistant'}: {msg.get('content')}\n"
        prompt_text += "Assistant:"
        payload = {"model": model, "prompt": prompt_text, "stream": False}
        if system_prompt:
            payload["system"] = system_prompt
    else:
        endpoint = CHAT_ENDPOINTS.get(prov, DEFAULT_CHAT_ENDPOINT)
        messages = []
        if system_prompt:
            messages.append({"role": "system", "content": system_prompt})
        messages.extend(history)
        payload = {"model": model, "messages": messages, "stream": False}
        if prov == 4 and fallbacks:
            payload["models"] = list(fallbacks)   # OpenRouter сам пробует их по очереди, если основная модель недоступна

    apply_params(payload, prov, params or {})
    return endpoint, headers, payload


async def send_chat_to_network(prov: int, model: str, api_key: str, system_prompt: str,
                               history_json_str: str, params: Optional[dict] = None,
                               fallbacks: Optional[list] = None) -> str:
    endpoint, headers, payload = build_chat_request(prov, model, api_key, system_prompt, history_json_str, params, fallbacks)
    return await post_to_provider(endpoint, headers, payload, prov, "Empty response")


def msg_key(m: Any):
    """Пара (роль, текст) для сравнения сообщений: служебное поле model не учитывается."""
    return (m.get("role"), m.get("content")) if isinstance(m, dict) else None


def save_reply_to_chat(chat_name: str, sent: list, model: str, reply: str, trimmed: bool) -> None:
    """Сохраняет чат на сервере. sent - то, что клиент отправил (история плюс новый вопрос).
    Если клиент урезал историю (trimmed), старые сообщения берём из серверной копии.
    Если на сервере после отправленного окна остались лишние сообщения (клиент перегенерировал ответ
    или поправил вопрос), они отбрасываются."""
    item = {"role": "assistant", "model": model, "content": reply}
    if trimmed and sent and chat_name in server_chats:
        try:
            base = json.loads(server_chats[chat_name], strict=False)
            if isinstance(base, list):
                window = sent[:-1]
                cut = len(base)
                if window:
                    keys = [msg_key(m) for m in window]
                    for j in range(len(base) - len(window), -1, -1):
                        if [msg_key(m) for m in base[j:j + len(window)]] == keys:
                            cut = j + len(window)
                            break
                server_chats[chat_name] = dump_chat(base[:cut] + sent[-1:] + [item])
                save_chats()
                return
        except Exception:
            pass
    # "model" стоит перед "content": так его ищет клиент, чтобы подписать ответ именем модели
    server_chats[chat_name] = dump_chat(list(sent) + [item])
    save_chats()


def parse_stream_line(line: str, prov: int):
    """Одна строка потока. Возвращает None для служебных строк, иначе (текст, конец, строка_json)."""
    line = line.strip()
    if not line or line.startswith(":") or line.startswith(("event:", "id:", "retry:")):
        return None
    if line.startswith("data:"):
        line = line[5:].strip()
    if line == "[DONE]":
        return ("", True, None)
    try:
        obj = json.loads(line)
    except Exception:
        return ("", False, line)
    if not isinstance(obj, dict):
        return ("", False, line)
    err = obj.get("error")
    if err:
        raise RuntimeError(error_text(err))
    if prov == 0:
        text = obj.get("response") or ""
        return (text if isinstance(text, str) else "", bool(obj.get("done")), line)
    choices = obj.get("choices") or []
    first = choices[0] if choices and isinstance(choices[0], dict) else {}
    delta = first.get("delta") or {}
    text = delta.get("content") if isinstance(delta, dict) else ""
    return (text if isinstance(text, str) else "", False, line)


def run_stream_job(sid: str, prov: int, model: str, api_key: str, system_prompt: str,
                   full_payload: str, params: dict, chat_name: str, trimmed: bool,
                   fallbacks: Optional[list] = None) -> None:
    """Фоновый поток: читает потоковый ответ провайдера и складывает текст в streams[sid]."""
    st = streams[sid]
    try:
        endpoint, headers, payload = build_chat_request(prov, model, api_key, system_prompt, full_payload, params, fallbacks)
        payload["stream"] = True
        got_text = False
        raw: list[str] = []
        raw_len = 0
        status = 200
        with httpx.Client(timeout=httpx.Timeout(300, connect=30)) as client:
            for attempt in range(len(RETRY_DELAYS) + 1):
                retry = False
                with client.stream("POST", endpoint, headers=headers, content=json.dumps(payload)) as res:
                    status = res.status_code
                    if status >= 400:
                        body = res.read().decode("utf-8", "replace")
                        if status in RETRY_STATUS and attempt < len(RETRY_DELAYS):
                            retry = True   # лимит или перегрузка: подождём и повторим
                        else:
                            if body.strip().startswith("<"):
                                raise RuntimeError(f"Provider returned HTML error (HTTP {status})")
                            try:
                                data = json.loads(body)
                                err = data.get("error") if isinstance(data, dict) else None
                            except Exception:
                                err = None
                            if err:
                                raise RuntimeError(error_text(err, status))
                            raise RuntimeError(f"HTTP {status}: {body[:100]}")
                    else:
                        for line in res.iter_lines():
                            if st["cancel"]:
                                break
                            parsed = parse_stream_line(line, prov)
                            if parsed is None:
                                continue
                            text, finished, body_line = parsed
                            if body_line is not None and not got_text and raw_len < 200000:
                                raw.append(body_line + "\n")
                                raw_len += len(body_line)
                            if text:
                                got_text = True
                                with st["lock"]:
                                    st["text"] += text
                            if finished:
                                break
                if retry:
                    time.sleep(RETRY_DELAYS[attempt])
                    continue
                break
        if not got_text and not st["cancel"]:
            # Провайдер не стал отдавать поток и прислал обычный ответ целиком
            reply = extract_reply("".join(raw), status, prov, "Empty response")
            with st["lock"]:
                st["text"] += reply
        text_all = st["text"]
        if text_all:
            try:
                sent = json.loads(full_payload, strict=False)
                if not isinstance(sent, list):
                    sent = []
            except Exception:
                sent = []
            save_reply_to_chat(chat_name, sent, model, text_all, trimmed)
        st["state"] = "D"
    except Exception as err:
        st["err"] = str(err) or err.__class__.__name__
        st["state"] = "E"
    finally:
        st["ts"] = time.time()


def prune_streams() -> None:
    now = time.time()
    for key in [k for k, v in streams.items()
                if (v["state"] != "R" and now - v["ts"] > 600) or now - v["ts"] > 3600]:
        streams.pop(key, None)


def new_session() -> dict[str, Any]:
    return {
        "chunks": {},        # index -> bytes
        "text_chunks": {},   # index -> bytes
        "chat_chunks": {},   # index -> bytes
        "img_total": 0,      # сколько кусков картинки заявил клиент
        "timestamp": time.time(),
    }


def join_chunks(chunks: dict[int, bytes]) -> bytes:
    return b"".join(chunks[i] for i in sorted(chunks))


@app.api_route("/", methods=["GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS", "HEAD"])
@app.api_route("/{path:path}", methods=["GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS", "HEAD"])
async def handler(request: Request, path: str = ""):
    q = request.query_params
    req_type = q.get("type")
    note_request(request, req_type)

    # 0. Журнал последних запросов: что именно приходит от телефона
    if req_type == "log":
        return text_response("\n".join(REQ_LOG) or "(empty)")

    # 1. Удаление чата. Принимается любым методом (GET, POST, DELETE) и под несколькими именами команды
    if req_type in ("delete", "del", "rm", "remove") or (request.method == "DELETE" and q.get("chat")):
        raw_chat = q.get("chat")
        server_chats.pop(clean_chat_name(raw_chat), None)
        if raw_chat:
            server_chats.pop(raw_chat, None)
        save_chats()
        return text_response("OK")

    # 1c. Обновление приложения: версия (update), дескриптор JAD (jad) и сам JAR (jar) с GitHub
    if req_type in ("update", "jad", "jar") or path.lower().endswith((".jad", ".jar")):
        kind = req_type or ("jar" if path.lower().endswith(".jar") else "jad")
        params = update_params(q)
        if params is None:
            return text_response("ERR: bad repo, branch or folder", 400)
        try:
            if kind == "jar":
                res = await fetch_github(update_file_url(params, ".jar"))
                if res.status_code != 200:
                    return text_response(f"ERR: GitHub HTTP {res.status_code}", 502)
                if len(res.content) > MAX_JAR:
                    return text_response("ERR: JAR is too large", 502)
                return Response(res.content, media_type="application/java-archive")
            res = await fetch_github(update_file_url(params, ".jad"))
            if res.status_code != 200:
                return text_response(f"ERR: GitHub HTTP {res.status_code}", 502)
            jad = res.content.decode("utf-8", "replace")
            if kind == "update":
                return text_response("OK\n" + jad)
            base = (q.get("base") or "").strip().rstrip("/")
            if not base.startswith(("http://", "https://")):
                host = request.headers.get("host") or ""
                base = ("https://" + host) if host else ""
            jar_url = "{}/{}.jar?type=jar&repo={}&branch={}&folder={}&name={}".format(
                base, params["name"], quote(params["repo"], safe="/"), quote(params["branch"], safe="/"),
                quote(params["folder"], safe="/"), quote(params["name"]))
            return Response(rewrite_jad(jad, jar_url).encode("utf-8"), media_type="text/vnd.sun.j2me.app-descriptor")
        except Exception as err:
            return text_response(f"ERR: {err}", 502)

    # 1b. Список чатов на сервере (по одному имени в строке)
    if req_type == "list":
        names = [k for k in server_chats if k and k != "default_chat"]
        return text_response("\n".join(names))

    # 2. Получение истории чата
    if req_type == "getchat":
        raw_chat = q.get("chat")
        chat = clean_chat_name(raw_chat)
        history = "[]"
        if chat in server_chats:
            history = server_chats[chat]
        elif raw_chat and raw_chat in server_chats:
            history = server_chats[raw_chat]
        else:
            for k, v in server_chats.items():
                if (k == chat
                        or quote(k, safe="-_.!~*'()") == raw_chat
                        or "_".join(k.split()) == "_".join(chat.split())):
                    history = v
                    break
        # Клиент присылает длину своей копии (have). Если серверная не длиннее, историю отдавать незачем
        have = to_int(q.get("have"), -1)
        if have >= 0 and len(history.encode("utf-16-le", "surrogatepass")) // 2 <= have:
            return text_response("SAME")
        return json_response(history)

    # 3. Список моделей
    if req_type == "models":
        try:
            prov = to_int(q.get("prov"), 1)
            api_key = q.get("key") or ""
            return json_response(await fetch_models_from_provider(prov, api_key))
        except Exception as err:
            return text_response(f"ERR: {err}", 500)

    # 3b. Потоковый ответ: клиент забирает новый текст (type=poll) или отменяет запрос (type=cancel)
    if req_type == "poll":
        st = streams.get(q.get("sid") or "")
        if st is None:
            return text_response("E 0\nStream not found (server restarted?)")
        with st["lock"]:
            text = st["text"]
            state = st["state"]
            err = st["err"]
        st["ts"] = time.time()
        data = text.encode("utf-16-le", "surrogatepass")
        total = len(data) // 2
        start = min(max(to_int(q.get("from"), 0), 0), total)
        delta = data[start * 2:].decode("utf-16-le", "replace")
        if state == "E" and not delta:
            return text_response(f"E {total}\n{err}")
        if state == "D":
            return text_response(f"D {total}\n{delta}")
        return text_response(f"R {total}\n{delta}")

    if req_type == "cancel":
        st = streams.get(q.get("sid") or "")
        if st is not None:
            st["cancel"] = True
        return text_response("OK")

    sid = q.get("sid")
    if not sid:
        return text_response("Omega Worker Active.")

    # Очистка сессий старше 2 минут
    now = time.time()
    for key in [k for k, s in sessions.items() if now - s["timestamp"] > SESSION_TTL]:
        sessions.pop(key, None)

    if request.method != "POST":
        return text_response("Omega Worker Active.")

    try:
        chat_type = req_type or "image_chunk"

        sess = sessions.get(sid)
        if sess is None:
            sess = new_session()
            sessions[sid] = sess
        sess["timestamp"] = time.time()

        # ШАГ 1: бинарные чанки изображения
        if chat_type == "image_chunk":
            chunk_index = to_int(q.get("chunk"), 1)
            sess["chunks"][chunk_index] = await request.body()
            sess["img_total"] = to_int(q.get("total"), 0)
            return text_response("CHUNK_OK")

        # ШАГ 2: обычный текстовый чат через прокси.
        # Клиент режет тело на куски по 8 КБ (chunk/total/final), поэтому собираем их все
        # и обрабатываем только после последнего. Запрос без параметров chunk/final
        # (одним куском) работает как раньше.
        if chat_type == "chat":
            chunk_index = to_int(q.get("chunk"), 1)
            is_final = q.get("final", "1") == "1"
            sess["chat_chunks"][chunk_index] = await request.body()

            if not is_final:
                return text_response("CHUNK_OK")

            check_chunks(sess["chat_chunks"], to_int(q.get("total"), 0), "chat")
            body = join_chunks(sess["chat_chunks"])
            prov = to_int(q.get("prov"), 1)
            model = fix_header(request.headers.get("X-Omega-Model")) or "gpt-4o-mini"
            api_key = fix_header(request.headers.get("X-Omega-Key"))
            system_prompt = fix_header(request.headers.get("X-Omega-Sys"))
            params = parse_params(fix_header(request.headers.get("X-Omega-Params")))
            fallbacks = parse_fallbacks(fix_header(request.headers.get("X-Omega-Fallback")), model)
            chat_name = clean_chat_name(q.get("chat"))

            full_payload = body.decode("utf-8")
            trimmed = q.get("trim") == "1"

            if q.get("stream") == "1":
                # Потоковый режим: запускаем фоновый поток и сразу отвечаем, текст клиент заберёт через type=poll
                prune_streams()
                streams[sid] = {"text": "", "state": "R", "err": "", "cancel": False,
                                "lock": threading.Lock(), "ts": time.time()}
                threading.Thread(
                    target=run_stream_job,
                    args=(sid, prov, model, api_key, system_prompt, full_payload, params, chat_name, trimmed, fallbacks),
                    daemon=True,
                ).start()
                sessions.pop(sid, None)
                return text_response("STREAM_OK")

            ai_response = await send_chat_to_network(prov, model, api_key, system_prompt, full_payload, params, fallbacks)

            try:
                sent = json.loads(full_payload, strict=False)
                if not isinstance(sent, list):
                    sent = []
            except Exception:
                sent = []
            save_reply_to_chat(chat_name, sent, model, ai_response, trimmed)

            sessions.pop(sid, None)
            return text_response(ai_response)

        # ШАГ 3: текстовые чанки метаданных и истории
        if chat_type == "image_text_chunk":
            chunk_index = to_int(q.get("chunk"), 1)
            is_final = q.get("final") == "1"
            sess["text_chunks"][chunk_index] = await request.body()

            if not is_final:
                return text_response("CHUNK_OK")

            prov = to_int(q.get("prov"), 1)
            model = q.get("model") or "gpt-4o-mini"
            api_key = q.get("key") or ""
            chat_name = clean_chat_name(q.get("chat"))

            check_chunks(sess["chunks"], sess["img_total"], "image")
            check_chunks(sess["text_chunks"], to_int(q.get("total"), 0), "meta")
            full_image = join_chunks(sess["chunks"])
            full_text = join_chunks(sess["text_chunks"])
            params = parse_params(fix_header(request.headers.get("X-Omega-Params")))
            sessions.pop(sid, None)

            try:
                meta = json.loads(full_text.decode("utf-8"), strict=False)
            except Exception:
                meta = {"prompt": "Describe this image", "sys": "", "history": []}

            base64_img = base64.b64encode(full_image).decode("ascii")

            mime = sniff_mime(full_image)
            try:
                ai_response = await send_image_to_network(
                    prov,
                    model,
                    api_key,
                    meta.get("prompt") or "Describe this image",
                    meta.get("sys") or "",
                    json.dumps(meta.get("history") or [], ensure_ascii=False),
                    base64_img,
                    mime,
                    params,
                )
            except RuntimeError as err:
                # Добавляем сведения о картинке: сразу видно, дошла ли она целиком
                raise RuntimeError(f"{err} [img {len(full_image)} B, {mime}]") from err

            updated_hist = meta.get("history") if isinstance(meta.get("history"), list) else []
            if meta.get("prompt"):
                updated_hist.append({"role": "user", "content": "[IMG] " + meta["prompt"]})
            updated_hist.append({"role": "assistant", "model": model, "content": ai_response})
            server_chats[chat_name] = dump_chat(updated_hist)
            save_chats()

            return text_response(ai_response)

        return text_response("OK")

    except Exception as err:
        sessions.pop(sid, None)
        return text_response(f"ERR: {err}", 500)


# Запуск: uvicorn main:app --host 0.0.0.0 --port 8000


if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=8000)
