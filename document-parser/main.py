from fastapi import FastAPI, UploadFile, Form
from fastapi.staticfiles import StaticFiles
from fastapi.responses import RedirectResponse
from io import BytesIO
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
EMBED_MODEL = "bge-m3"
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
                "distance": hit.distance
            })
        return {"code": 0, "data": res_data}
    except Exception as e:
        return {"code": 1, "msg": str(e)}

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