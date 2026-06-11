from fastapi import FastAPI, UploadFile, Form
from fastapi.staticfiles import StaticFiles
from io import BytesIO
import os
import pdfplumber
import docx
import threading

app = FastAPI()

# 挂载静态文件夹，访问 /static/xxx 就能加载页面/资源
app.mount("/static", StaticFiles(directory="static"), name="static")

# 访问根路径直接跳转到静态页面
@app.get("/")
async def root():
    from fastapi.responses import RedirectResponse
    return RedirectResponse(url="/static/index.html")

# 原有配置
PAGE_TIMEOUT = 5

def extract_single_page_text(page) -> str:
    return page.extract_text(x_tolerance=2, y_tolerance=3)

def extract_text(file_content: bytes, filename: str) -> str:
    ext = os.path.splitext(filename)[-1].lower()
    try:
        if ext == ".pdf":
            text = ""
            with pdfplumber.open(BytesIO(file_content)) as pdf:
                for page in pdf.pages:
                    t = threading.Thread(target=lambda: globals().update({"res": extract_single_page_text(page)}))
                    t.start()
                    t.join(timeout=PAGE_TIMEOUT)
                    if t.is_alive():
                        continue
                    page_text = globals().get("res", "")
                    if page_text:
                        text += page_text + "\n\n"
            return text.strip()
        elif ext == ".docx":
            doc = docx.Document(BytesIO(file_content))
            return "\n".join([para.text for para in doc.paragraphs])
        else:
            return file_content.decode("utf-8", errors="replace")
    except Exception as e:
        return f"解析异常：{str(e)}"

# 混合分块：章节 + 滑动窗口
def split_by_chapter(text: str):
    chapters = []
    raw_blocks = [b.strip() for b in text.split("\n\n") if b.strip()]
    current_chapter = ""
    title_prefix = ("第", "一、", "二、", "1.", "2.", "(1)")
    for block in raw_blocks:
        is_title = block.startswith(title_prefix)
        if is_title and current_chapter:
            chapters.append(current_chapter)
            current_chapter = block
        else:
            current_chapter += "\n" + block
    if current_chapter:
        chapters.append(current_chapter)
    return chapters

def split_by_sliding_window(text: str, window_size: int, overlap_size: int):
    chunks = []
    start = 0
    total_len = len(text)
    while start < total_len:
        end = start + window_size
        chunks.append(text[start:end])
        start = end - overlap_size
    return chunks

def hybrid_chunk(text: str, window_size: int, overlap_size: int, chapter_min_len: int):
    chapter_list = split_by_chapter(text)
    final_chunks = []
    for chap in chapter_list:
        if len(chap) <= chapter_min_len:
            final_chunks.append(chap)
        else:
            slide_chunks = split_by_sliding_window(chap, window_size, overlap_size)
            final_chunks.extend(slide_chunks)
    return final_chunks

# 解析+分块接口（前端调用）
@app.post("/api/parse-chunk")
async def parse_and_chunk(
    file: UploadFile,
    window_size: int = Form(800),
    overlap_size: int = Form(150),
    chapter_min_len: int = Form(300)
):
    try:
        content = await file.read()
        full_text = extract_text(content, file.filename)
        if full_text.startswith("解析异常"):
            return {"code": 1, "msg": full_text, "chunks": []}
        chunks = hybrid_chunk(full_text, window_size, overlap_size, chapter_min_len)
        return {"code": 0, "msg": "解析成功", "chunks": chunks}
    except Exception as e:
        return {"code": 1, "msg": str(e), "chunks": []}

# 保留旧接口兼容
@app.post("/parse-document")
async def parse_document(file: UploadFile, chunk_size: int = Form(512)):
    try:
        content = await file.read()
        text = extract_text(content, file.filename)
        chunks = []
        if text:
            while len(text) > chunk_size:
                chunks.append(text[:chunk_size])
                text = text[chunk_size:]
            if text:
                chunks.append(text)
        return {"code": 0, "chunks": chunks}
    except Exception as e:
        return {"code": 1, "msg": str(e)}