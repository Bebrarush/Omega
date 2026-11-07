import base64
import json
import requests
from fastapi import FastAPI, Request
from fastapi.responses import PlainTextResponse

app = FastAPI()

# Вставьте ваш API ключ от Google AI Studio / Gemini / Ollama
GEMINI_API_KEY = "YOUR_GEMINI_API_KEY"

@app.get("/")
def home():
    return PlainTextResponse("Omega J2ME Proxy Relay is Running!")

@app.all("/api/chat")
async def handle_chat(request: Request):
    # 1. Забираем метаданные из URL (системный промпт, история)
    params = dict(request.query_params)
    sys_prompt = params.get("sys", "You are Omega AI helper for J2ME.")
    history_raw = params.get("history", "")
    
    # 2. Читаем body (Base64 чанки изображения или текст)
    body_bytes = await request.body()
    body_str = body_bytes.decode("utf-8", errors="ignore")
    
    user_prompt = params.get("prompt", body_str if not body_str.startswith("DATA:") else "Опиши это изображение.")
    
    # 3. Собираем запрос к Gemini 1.5 Flash / 2.0
    url = f"https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent?key={GEMINI_API_KEY}"
    
    contents = []
    
    # Добавляем системный контекст
    if sys_prompt:
        contents.append({"role": "user", "parts": [{"text": f"System Instruction: {sys_prompt}"}]})
        contents.append({"role": "model", "parts": [{"text": "Understood."}]})

    # Если передана картинка в Base64
    if body_str.startswith("DATA:"):
        base64_data = body_str.split("DATA:")[1]
        contents.append({
            "role": "user",
            "parts": [
                {"text": user_prompt},
                {
                    "inline_data": {
                        "mime_type": "image/jpeg",
                        "data": base64_data
                    }
                }
            ]
        })
    else:
        contents.append({"role": "user", "parts": [{"text": user_prompt}]})

    payload = {"contents": contents}
    
    try:
        resp = requests.post(url, json=payload, timeout=30)
        data = resp.json()
        
        # Извлекаем ответ модели
        answer = data["candidates"][0]["content"]["parts"][0]["text"]
        
        # Отдаем чистый plain text для J2ME без лишнего JSON-мусора
        return PlainTextResponse(answer.strip())
        
    except Exception as e:
        return PlainTextResponse(f"Proxy Error: {str(e)}", status_code=500)
