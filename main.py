import time
import json
import httpx
from fastapi import FastAPI, Request
from fastapi.responses import PlainTextResponse

app = FastAPI()

sessions = {}

def cleanup_sessions():
    now = time.time() * 1000
    expired = [sid for sid, sess in sessions.items() if now - sess["timestamp"] > 120000]
    for sid in expired:
        del sessions[sid]

@app.api_route("/", methods=["GET", "POST"])
async def home(request: Request):
    url_params = dict(request.query_params)
    if url_params.get("type") == "models":
        try:
            prov = int(url_params.get("prov", 1))
            api_key = url_params.get("key", "")
            models_resp = await fetch_models_from_provider(prov, api_key)
            return PlainTextResponse(models_resp, media_type="application/json; charset=utf-8")
        except Exception as e:
            return PlainTextResponse(f"ERR: {str(e)}", status_code=500)

    return PlainTextResponse("Omega Render Proxy Active.")

async def fetch_models_from_provider(prov: int, api_key: str):
    headers = {}
    if prov == 0:
        endpoint = "https://ollama.com/api/tags"
        if api_key:
            headers["Authorization"] = f"Bearer {api_key}"
    elif prov == 1:
        endpoint = "https://api.voidai.app/v1/models"
        if api_key:
            headers["Authorization"] = f"Bearer {api_key}"
    elif prov == 2:
        endpoint = "https://opencodezen.com/api/v1/models"
        if api_key:
            headers["Authorization"] = f"Bearer {api_key}"
    elif prov == 3:
        endpoint = "https://api.navyai.ru/v1/models"
        if api_key:
            headers["Authorization"] = f"Bearer {api_key}"
    elif prov == 4:
        endpoint = f"https://generativelanguage.googleapis.com/v1beta/models?key={api_key}"
    else:
        endpoint = "https://api.voidai.app/v1/models"

    async with httpx.AsyncClient(timeout=30.0) as client:
        res = await client.get(endpoint, headers=headers)
        return res.text

@app.api_route("/api/chat", methods=["POST"])
async def handle_chat(request: Request):
    cleanup_sessions()
    
    url_params = dict(request.query_params)
    sid = url_params.get("sid")
    
    if not sid:
        return PlainTextResponse("ERR: Missing session ID (sid)", status_code=400)
    
    try:
        chat_type = url_params.get("type", "image")
        chunk_index = int(url_params.get("chunk", 1))
        total_chunks = int(url_params.get("total", 1))
        is_final = url_params.get("final") == "1"
        
        prov = int(url_params.get("prov", 1))
        model = url_params.get("model", "gpt-4o-mini")
        api_key = url_params.get("key", "")
        prompt = url_params.get("prompt", "")
        system_prompt = url_params.get("sys", "")
        history_str = url_params.get("history", "")
        
        chunk_data = (await request.body()).decode("utf-8", errors="ignore")
        
        sess = sessions.get(sid)
        if not sess:
            sess = {
                "chunks": [None] * total_chunks,
                "total": total_chunks,
                "receivedCount": 0,
                "timestamp": time.time() * 1000,
                "prov": prov,
                "model": model,
                "apiKey": api_key,
                "prompt": prompt,
                "systemPrompt": system_prompt,
                "historyStr": history_str,
                "type": chat_type
            }
            sessions[sid] = sess
            
        if history_str: sess["historyStr"] = history_str
        if system_prompt: sess["systemPrompt"] = system_prompt
        if prompt: sess["prompt"] = prompt
            
        if 0 <= (chunk_index - 1) < len(sess["chunks"]):
            if sess["chunks"][chunk_index - 1] is None:
                sess["chunks"][chunk_index - 1] = chunk_data
                sess["receivedCount"] += 1
                
        sess["timestamp"] = time.time() * 1000
        
        if sess["receivedCount"] >= sess["total"] or is_final:
            full_payload = "".join([c for c in sess["chunks"] if c is not None])
            del sessions[sid]
            
            if sess["type"] == "image":
                ai_response = await send_image_to_neural_network(
                    sess["prov"], sess["model"], sess["apiKey"], 
                    sess["prompt"], sess["systemPrompt"], sess["historyStr"], full_payload
                )
            else:
                ai_response = await send_chat_to_neural_network(
                    sess["prov"], sess["model"], sess["apiKey"], 
                    sess["systemPrompt"], full_payload
                )
                
            return PlainTextResponse(ai_response, media_type="text/plain; charset=utf-8")
            
        return PlainTextResponse("CHUNK_OK", media_type="text/plain; charset=utf-8")
        
    except Exception as err:
        if sid in sessions:
            del sessions[sid]
        return PlainTextResponse(f"ERR: {str(err)}", status_code=500, media_type="text/plain; charset=utf-8")

async def send_image_to_neural_network(prov, model, apiKey, prompt, systemPrompt, historyStr, base64Img):
    headers = {"Content-Type": "application/json"}
    history = []
    if historyStr:
        try: history = json.loads(historyStr)
        except: pass

    if prov == 0:
        endpoint = "https://ollama.com/api/generate"
        if apiKey: headers["Authorization"] = f"Bearer {apiKey}"
        prompt_text = ""
        for msg in history:
            role_name = "User" if msg.get("role") == "user" else "Assistant"
            prompt_text += f"{role_name}: {msg.get('content', '')}\n"
        prompt_text += f"User: {prompt}\nAssistant:"
        payload = {"model": model, "prompt": prompt_text, "images": [base64Img], "stream": False}
        if systemPrompt: payload["system"] = systemPrompt

    elif prov == 4:
        endpoint = f"https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent?key={apiKey}"
        contents = []
        for m in history:
            r = "model" if m.get("role") == "assistant" else "user"
            contents.append({"role": r, "parts": [{"text": m.get("content", "")}]})
        contents.append({
            "role": "user",
            "parts": [
                {"text": prompt},
                {"inline_data": {"mime_type": "image/jpeg", "data": base64Img}}
            ]
        })
        payload = {"contents": contents}
        if systemPrompt: payload["system_instruction"] = {"parts": [{"text": systemPrompt}]}

    else:
        endpoint = "https://api.voidai.app/v1/chat/completions"
        if prov == 2: endpoint = "https://opencodezen.com/api/v1/chat/completions"
        elif prov == 3: endpoint = "https://api.navyai.ru/v1/chat/completions"

        if apiKey: headers["Authorization"] = f"Bearer {apiKey}"
        messages = []
        if systemPrompt: messages.append({"role": "system", "content": systemPrompt})
        messages.extend(history)
        messages.append({
            "role": "user",
            "content": [
                {"type": "text", "text": prompt},
                {"type": "image_url", "image_url": {"url": f"data:image/jpeg;base64,{base64Img}"}}
            ]
        })
        payload = {"model": model, "messages": messages, "stream": False}

    async with httpx.AsyncClient(timeout=45.0) as client:
        res = await client.post(endpoint, headers=headers, json=payload)
        raw_text = res.text
        if raw_text.strip().startswith("<"):
            raise Exception(f"Provider returned HTML error (HTTP {res.status_code})")
        try: data = res.json()
        except: raise Exception(f"Invalid JSON from provider: {raw_text[:100]}")
        if "error" in data:
            err_val = data["error"]
            raise Exception(err_val if isinstance(err_val, str) else json.dumps(err_val))

        if prov == 4: return data.get("candidates", [{}])[0].get("content", {}).get("parts", [{}])[0].get("text", "Sys: Empty Gemini response")
        if prov == 0: return data.get("response") or data.get("message", {}).get("content", "Sys: Empty Ollama response")
        return data.get("choices", [{}])[0].get("message", {}).get("content", "Sys: Empty OpenAI-like response")

async def send_chat_to_neural_network(prov, model, apiKey, systemPrompt, historyJsonStr):
    headers = {"Content-Type": "application/json"}
    try: history = json.loads(historyJsonStr)
    except: history = []

    if prov == 0:
        endpoint = "https://ollama.com/api/generate"
        if apiKey: headers["Authorization"] = f"Bearer {apiKey}"
        prompt_text = ""
        for msg in history:
            role_name = "User" if msg.get("role") == "user" else "Assistant"
            prompt_text += f"{role_name}: {msg.get('content', '')}\n"
        prompt_text += "Assistant:"
        payload = {"model": model, "prompt": prompt_text, "stream": False}
        if systemPrompt: payload["system"] = systemPrompt

    elif prov == 4:
        endpoint = f"https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent?key={apiKey}"
        contents = []
        for m in history:
            r = "model" if m.get("role") == "assistant" else "user"
            contents.append({"role": r, "parts": [{"text": m.get("content", "")}]})
        payload = {"contents": contents}
        if systemPrompt: payload["system_instruction"] = {"parts": [{"text": systemPrompt}]}

    else:
        endpoint = "https://api.voidai.app/v1/chat/completions"
        if prov == 2: endpoint = "https://opencodezen.com/api/v1/chat/completions"
        elif prov == 3: endpoint = "https://api.navyai.ru/v1/chat/completions"

        if apiKey: headers["Authorization"] = f"Bearer {apiKey}"
        messages = []
        if systemPrompt: messages.append({"role": "system", "content": systemPrompt})
        messages.extend(history)
        payload = {"model": model, "messages": messages, "stream": False}

    async with httpx.AsyncClient(timeout=45.0) as client:
        res = await client.post(endpoint, headers=headers, json=payload)
        raw_text = res.text
        if raw_text.strip().startswith("<"):
            raise Exception(f"Provider returned HTML error (HTTP {res.status_code})")
        try: data = res.json()
        except: raise Exception(f"Invalid JSON from provider: {raw_text[:100]}")
        if "error" in data:
            err_val = data["error"]
            raise Exception(err_val if isinstance(err_val, str) else json.dumps(err_val))

        if prov == 4: return data.get("candidates", [{}])[0].get("content", {}).get("parts", [{}])[0].get("text", "Sys: Empty Gemini response")
        if prov == 0: return data.get("response") or data.get("message", {}).get("content", "Sys: Empty Ollama response")
        return data.get("choices", [{}])[0].get("message", {}).get("content", "Sys: Empty OpenAI-like response")
