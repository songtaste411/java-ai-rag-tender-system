from fastapi import FastAPI, UploadFile, Form
from io import BytesIO
import os
import pdfplumber
import docx
import threading

app = FastAPI()

# 单页解析最大超时时间(秒)，超时直接跳过当前页，防止卡死
PAGE_TIMEOUT = 5

def extract_single_page_text(page) -> str:
    """单独提取单页文本，给超时线程调用"""
    return page.extract_text(x_tolerance=2, y_tolerance=3)

def extract_text(file_content: bytes, filename: str) -> str:
    ext = os.path.splitext(filename)[-1].lower()

    try:
        # PDF 解析：逐页+超时隔离，单页卡死不整体挂掉
        if ext == ".pdf":
            text = ""
            with pdfplumber.open(BytesIO(file_content)) as pdf:
                for page in pdf.pages:
                    page_text = ""
                    # 线程执行单页解析，设置超时
                    t = threading.Thread(target=lambda: globals().update({"res": extract_single_page_text(page)}))
                    t.start()
                    t.join(timeout=PAGE_TIMEOUT)
                    if t.is_alive():
                        # 超时：放弃当前页
                        continue
                    page_text = globals().get("res", "")
                    if page_text:
                        text += page_text + "\n\n"
            return text.strip()

        # DOCX 解析
        elif ext == ".docx":
            doc = docx.Document(BytesIO(file_content))
            return "\n".join([para.text for para in doc.paragraphs])

        # 普通文本
        else:
            return file_content.decode("utf-8", errors="replace")

    except Exception as e:
        return f"解析异常：{str(e)}"

# 文本切片
def split_text(text: str, chunk_size: int = 512):
    chunks = []
    if not text:
        return chunks
    while len(text) > chunk_size:
        chunks.append(text[:chunk_size])
        text = text[chunk_size:]
    if text:
        chunks.append(text)
    return chunks

# 接口
@app.post("/parse-document")
async def parse_document(
    file: UploadFile,
    chunk_size: int = Form(512)
):
    try:
        content = await file.read()
        text = extract_text(content, file.filename)
        chunks = split_text(text, chunk_size)
        return {"code": 0, "chunks": chunks}
    except Exception as e:
        return {"code": 1, "msg": f"请求异常: {


        str(e)}"}