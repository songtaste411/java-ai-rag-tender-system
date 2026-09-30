FROM swr.cn-north-4.myhuaweicloud.com/ddn-k8s/docker.io/python:3.13-slim
WORKDIR /app
COPY requirements.txt .
RUN pip install --upgrade pip setuptools wheel -i https://pypi.tuna.tsinghua.edu.cn/simple \
 && pip install -i https://pypi.tuna.tsinghua.edu.cn/simple -r requirements.txt
COPY main.py .
EXPOSE 9099
CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "9099"]
