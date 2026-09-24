from fastapi import FastAPI, UploadFile, Form
from fastapi.staticfiles import StaticFiles
from fastapi.responses import RedirectResponse
from io import BytesIO
import logging
import os
import pdfplumber
import docx
import threading
import uuid
import requests
# Milvus 原生SDK
from pymilvus import (
    connections,
    Collection,
    FieldSchema,
    CollectionSchema,
    DataType,
    utility
)

# 屏蔽 pdfminer 对残缺字体信息的刷屏警告（不影响正常抽取文字）
logging.getLogger("pdfminer").setLevel(logging.ERROR)

app = FastAPI(title="文档解析分块&向量入库服务")
# 挂载静态页面
app.mount("/static", StaticFiles(directory="static"), name="static")

@app.get("/")
async def root():
    return RedirectResponse(url="/static/index.html")

# ==================== 全局配置 ====================
PAGE_TIMEOUT = 5
# 宿主机IP（固定你的实际地址）
HOST_IP = "localhost"
# Ollama 配置 bge-m3
OLLAMA_URL = f"http://{HOST_IP}:11434/api/embeddings"
OLLAMA_GENERATE_URL = f"http://{HOST_IP}:11434/api/generate"
OLLAMA_CHAT_URL = f"http://{HOST_IP}:11434/api/chat"
EMBED_MODEL = "bge-m3"
CHAT_MODEL = "deepseek-r1"
# bge-m3 向量维度固定 1024
EMBED_DIM = 1024

# Milvus 连接配置
MILVUS_HOST = HOST_IP
MILVUS_PORT = "19530"
connections.connect(alias="default", host=MILVUS_HOST, port=MILVUS_PORT)
COLLECTION_NAME = "rag_document_chunks"

# ==================== Milvus 集合初始化 ====================
def init_milvus_collection():
    if utility.has_collection(COLLECTION_NAME):
        coll = Collection(COLLECTION_NAME)
        coll.load()
        return coll
    # 字段定义
    fields = [
        FieldSchema(name="id", dtype=DataType.VARCHAR, max_length=64, is_primary=True),
        FieldSchema(name="content", dtype=DataType.VARCHAR, max_length=4096),
        FieldSchema(name="vector", dtype=DataType.FLOAT_VECTOR, dim=EMBED_DIM)
    ]
    schema = CollectionSchema(fields, description="RAG 文档分块向量库")
    coll = Collection(COLLECTION_NAME, schema)
    # 索引配置
    index_params = {
        "metric_type": "L2",
        "index_type": "IVF_FLAT",
        "params": {"nlist": 128}
    }
    coll.create_index(field_name="vector", index_params=index_params)
    coll.load()
    return coll

milvus_coll = init_milvus_collection()

# ==================== 文档解析逻辑（完全保留原逻辑） ====================
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

# ==================== 混合分块逻辑（完全保留原逻辑） ====================
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

# ==================== 调用 Ollama bge-m3 向量化 ====================
def get_embedding(text: str) -> list:
    """单条文本调用Ollama生成向量"""
    payload = {
        "model": EMBED_MODEL,
        "prompt": text
    }
    resp = requests.post(OLLAMA_URL, json=payload, timeout=30)
    resp.raise_for_status()
    return resp.json()["embedding"]

def batch_text_to_embedding(text_list: list) -> list:
    """批量文本转向量"""
    vec_list = []
    for text in text_list:
        vec = get_embedding(text)
        vec_list.append(vec)
    return vec_list

# ==================== Milvus 批量入库 ====================
def batch_insert_milvus(chunk_list: list) -> int:
    if not chunk_list:
        return 0
    ids = [str(uuid.uuid4()) for _ in chunk_list]
    vectors = batch_text_to_embedding(chunk_list)
    data = [ids, chunk_list, vectors]
    milvus_coll.insert(data)
    milvus_coll.flush()
    return len(chunk_list)

# ==================== 业务接口（全部保留） ====================
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
            return {
                "code": 1,
                "msg": full_text,
                "chunks": [],
                "insert_count": 0
            }
        chunks = hybrid_chunk(full_text, window_size, overlap_size, chapter_min_len)
        insert_cnt = batch_insert_milvus(chunks)
        return {
            "code": 0,
            "msg": f"解析、分块、向量入库完成，共 {insert_cnt} 条",
            "chunks": chunks,
            "insert_count": insert_cnt
        }
    except Exception as e:
        return {
            "code": 1,
            "msg": str(e),
            "chunks": [],
            "insert_count": 0
        }

@app.post("/api/vector-search")
async def vector_search(
    query: str = Form(...),
    top_k: int = Form(3)
):
    try:
        res_data = search_similar_chunks(query, top_k)
        return {"code": 0, "data": res_data}
    except Exception as e:
        return {"code": 1, "msg": str(e)}

def search_similar_chunks(query: str, top_k: int = 3) -> list:
    """从知识库检索最相似的文本块"""
    query_vec = get_embedding(query)
    search_params = {"metric_type": "L2", "params": {"nprobe": 10}}
    results = milvus_coll.search(
        data=[query_vec],
        anns_field="vector",
        param=search_params,
        limit=top_k,
        output_fields=["content"]
    )
    res_data = []
    for hit in results[0]:
        res_data.append({
            "content": hit.entity.get("content"),
            "distance": float(hit.distance)
        })
    return res_data

def ask_ollama_with_context(question: str, contexts: list) -> str:
    """用检索到的知识库内容 + 问题，调用本地大模型回答。
    deepseek-r1 默认会先“长思考”，若 num_predict 太小，最终回答会被截成半句。
    这里用 /api/chat + think=false，把额度留给真正的答案。
    """
    # 压缩资料，避免提示词太长导致更慢/更容易超时
    trimmed = []
    for i, c in enumerate(contexts[:3]):
        text = (c.get("content") or "").strip()
        if len(text) > 500:
            text = text[:500] + "…"
        trimmed.append(f"【资料{i+1}】\n{text}")
    context_text = "\n\n".join(trimmed)
    user_content = (
        "你是招投标知识库助手。只依据【资料】回答，不要编造；资料不够就说不知道。"
        "用完整中文句子回答，不要只写半句。\n\n"
        f"{context_text}\n\n"
        f"【问题】{question}"
    )
    payload = {
        "model": CHAT_MODEL,
        "messages": [
            {"role": "user", "content": user_content}
        ],
        "stream": False,
        # 顶层参数：关闭思考，避免思考占满生成额度导致答案被截断
        "think": False,
        "options": {
            "num_predict": 1024,
            "temperature": 0.2
        }
    }
    # (连接超时, 读取超时) —— CPU 推理经常超过 3 分钟
    resp = requests.post(OLLAMA_CHAT_URL, json=payload, timeout=(30, 900))
    resp.raise_for_status()
    data = resp.json()
    message = data.get("message") or {}
    answer = (message.get("content") or data.get("response") or "").strip()
    # 若模型仍夹带思考块，只保留最终回答
    end_tag = "</" + "think>"
    if end_tag in answer:
        answer = answer.split(end_tag, 1)[-1].strip()
    return answer

@app.post("/api/rag-chat")
async def rag_chat(
    query: str = Form(...),
    top_k: int = Form(3),
    with_ai: int = Form(1)
):
    """
    知识库问答：
    1) 先向量检索相关分块
    2) with_ai=1 时再调用 Ollama 大模型生成回答（模型未就绪/超时则仍返回检索结果）
    """
    try:
        hits = search_similar_chunks(query, top_k)
        answer = ""
        ai_msg = ""
        if with_ai and hits:
            try:
                answer = ask_ollama_with_context(query, hits)
                if not answer:
                    ai_msg = "模型返回了空内容，请看下方检索段落。"
            except requests.exceptions.ReadTimeout:
                ai_msg = (
                    f"对话模型 {CHAT_MODEL} 在 CPU 上生成超时（可能要 5～15 分钟）。"
                    "检索结果已返回；可先看下方段落，或取消勾选 AI 只做检索。"
                )
            except Exception as e:
                err = str(e)
                if "not found" in err.lower() or "404" in err:
                    ai_msg = f"对话模型 {CHAT_MODEL} 不可用：{err}"
                else:
                    ai_msg = f"大模型调用失败：{err}。已返回知识库检索结果。"
        elif not hits:
            ai_msg = "知识库里没搜到相关内容，请先在导入页上传文档。"
        return {
            "code": 0,
            "answer": answer,
            "hits": hits,
            "ai_msg": ai_msg
        }
    except Exception as e:
        return {"code": 1, "msg": str(e), "answer": "", "hits": [], "ai_msg": ""}

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

if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="localhost", port=9100)