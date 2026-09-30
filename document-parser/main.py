"""
文档解析微服务：只负责把 PDF/DOCX/TXT 抽成纯文本。
不分块、不向量化、不写库、不调大模型——这些由 Java RAG 统一做。
"""
from __future__ import annotations

import logging
import os
import threading
from io import BytesIO

import docx
import pdfplumber
from fastapi import FastAPI, File, UploadFile
from fastapi.responses import JSONResponse

logging.getLogger("pdfminer").setLevel(logging.ERROR)

app = FastAPI(title="document-parser", version="2.0.0")
PAGE_TIMEOUT = int(os.getenv("PAGE_TIMEOUT", "5"))


def _extract_pdf(content: bytes) -> str:
    parts: list[str] = []
    with pdfplumber.open(BytesIO(content)) as pdf:
        for page in pdf.pages:
            holder: dict[str, str] = {}

            def _run(p=page, h=holder) -> None:
                h["text"] = p.extract_text(x_tolerance=2, y_tolerance=3) or ""

            t = threading.Thread(target=_run)
            t.start()
            t.join(timeout=PAGE_TIMEOUT)
            if t.is_alive():
                continue
            text = holder.get("text", "").strip()
            if text:
                parts.append(text)
    return "\n\n".join(parts).strip()


def _extract_docx(content: bytes) -> str:
    doc = docx.Document(BytesIO(content))
    return "\n".join(p.text for p in doc.paragraphs if p.text and p.text.strip()).strip()


def extract_text(content: bytes, filename: str) -> str:
    ext = os.path.splitext(filename or "")[-1].lower()
    if ext == ".pdf":
        return _extract_pdf(content)
    if ext in {".docx", ".doc"}:
        return _extract_docx(content)
    return content.decode("utf-8", errors="replace").strip()


@app.get("/health")
def health():
    return {"status": "ok", "service": "document-parser"}


@app.post("/api/parse")
async def parse(file: UploadFile = File(...)):
    """解析文档，只返回全文。"""
    try:
        raw = await file.read()
        name = file.filename or "unknown"
        text = extract_text(raw, name)
        if not text:
            return JSONResponse(
                {"code": 1, "msg": "未能提取到文本（可能是扫描件/图片 PDF，后续可接 OCR）", "text": "", "filename": name},
                status_code=200,
            )
        return {"code": 0, "msg": "ok", "text": text, "filename": name, "chars": len(text)}
    except Exception as e:
        return JSONResponse({"code": 1, "msg": str(e), "text": "", "filename": file.filename or ""}, status_code=200)


if __name__ == "__main__":
    import uvicorn

    uvicorn.run(app, host="0.0.0.0", port=int(os.getenv("PORT", "9099")))
