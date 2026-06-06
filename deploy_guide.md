# AWS EC2 Ubuntu Docker Compose 배포 가이드 (`deploy_guide.md`)

본 가이드는 `yeonam_tester` 프로젝트를 AWS EC2 Ubuntu 인스턴스 환경에 Docker Compose와 Nginx 리버스 프록시를 적용해 배포하고, 비용 최소화 정책(NAT Gateway 배제, 프론트엔드 정적 서빙 통합) 및 IAM Role 기반 Bedrock 연동을 안전하게 활성화하는 DevOps 절차를 설명합니다.

---

## 🏗️ 배포 아키텍처 개요

```text
                     [ Internet Traffic (Port 80/443) ]
                                     │
                                     ▼
                        [ EC2 (Public Subnet Only) ]
                        ┌─────────────────────────┐
                        │     Nginx Container     │ (Vite Static 서빙)
                        └────────────┬────────────┘
                                     │ (Docker Bridge Net)
                       ┌─────────────┴─────────────┐
                       ▼                           ▼
            [ Backend Container ]        [ RAG Server Container ]
                 (Java Boot)                   (FastAPI Python)
                       │                           │
         (H2 Database local volume)         (MinIO/S3 storage bucket)
                       │                           │
                       └─────────────┬─────────────┘
                                     ▼
                            [ Internet Gateway ]
                                     │
                 ┌───────────────────┴───────────────────┐
                 ▼                                       ▼
       [ AWS Bedrock Runtime ]                  [ AWS S3 Bucket ]
     (Claude 3 IAM Role 인증)               (데이터 영속성 확보)
```

### 💰 비용 최적화 설계 핵심
1. **NAT Gateway 배제:** 비싼 고정 비용이 청구되는 NAT Gateway 대신, 퍼블릭 서브넷의 단일 EC2가 **인터넷 게이트웨이(IGW)**를 통해 직접 AWS API 엔드포인트(Bedrock, S3)와 통신하여 고정 네트워크 요금을 `$0`으로 만듭니다.
2. **프론트엔드 정적 서빙 통합:** Vite 개발용 Node.js 컨테이너를 상용에 올리지 않고, 빌드된 HTML/JS/CSS 정적 파일(`dist`)을 Nginx 가 직접 서빙하게 함으로써 EC2의 RAM/CPU 소모를 최소화(t3.micro 또는 t3.small 사양으로 기동 가능)합니다.
3. **IAM Role 기반 보안:** EC2에 적절한 **IAM Instance Profile**을 부착하여 Bedrock 및 S3 접근 자격 증명을 임시 토큰으로 자동 갱신 및 처리하게 설계합니다.

---

## 1. 멀티 컨테이너 구성 파일 (`docker-compose.yml`)

EC2 홈 디렉토리의 `/home/ubuntu/app/docker-compose.yml` 경로에 생성합니다.

```yaml
version: '3.8'

services:
  nginx:
    image: nginx:stable-alpine
    container_name: yeonam-nginx
    ports:
      - "80:80"
    volumes:
      - ./nginx/nginx.conf:/etc/nginx/nginx.conf:ro
      - ./frontend/dist:/usr/share/nginx/html:ro
    depends_on:
      - backend
      - rag-server
    networks:
      - yeonam-network
    restart: always

  backend:
    build:
      context: ./backend
      dockerfile: Dockerfile
    container_name: yeonam-backend
    expose:
      - "8080"
    env_file:
      - .env
    volumes:
      - ./backend/data:/app/data
    networks:
      - yeonam-network
    restart: always

  rag-server:
    build:
      context: ./rag_server
      dockerfile: Dockerfile
    container_name: yeonam-rag
    expose:
      - "8081"
    environment:
      - HOST=0.0.0.0
      - PORT=8081
      - MOCK_RAG=false
      - EMBEDDING_PROVIDER=local
      - EMBEDDING_MODEL=all-MiniLM-L6-v2
    volumes:
      - ./rag_server/knowledge_base:/app/knowledge_base
    networks:
      - yeonam-network
    restart: always

networks:
  yeonam-network:
    driver: bridge
```

---

## 2. Nginx 리버스 프록시 설정 (`nginx.conf`)

`/home/ubuntu/app/nginx/nginx.conf` 경로에 작성합니다.

```nginx
user nginx;
worker_processes auto;
error_log /var/log/nginx/error.log warn;
pid /var/run/nginx.pid;

events {
    worker_connections 1024;
}

http {
    include /etc/nginx/mime.types;
    default_type application/octet-stream;

    log_format main '$remote_addr - $remote_user [$time_local] "$request" '
                    '$status $body_bytes_sent "$http_referer" '
                    '"$http_user_agent" "$http_x_forwarded_for"';

    access_log /var/log/nginx/access_log main;
    sendfile on;
    keepalive_timeout 65;

    server {
        listen 80;
        server_name localhost;

        # 1. Frontend React SPA Static Serve
        location / {
            root /usr/share/nginx/html;
            index index.html index.htm;
            try_files $uri $uri/ /index.html; # React SPA 라우팅 폴백 설정
            
            # Static resources caching
            location ~* \.(?:css|js|jpg|jpeg|gif|png|ico|svg|woff2)$ {
                expires 1d;
                access_log off;
                add_header Cache-Control "public";
            }
        }

        # 2. Backend API Proxy Pass
        location /api/ {
            proxy_pass http://backend:8080/api/;
            proxy_http_version 1.1;
            proxy_set_header Upgrade $http_upgrade;
            proxy_set_header Connection 'upgrade';
            proxy_set_header Host $host;
            proxy_cache_bypass $http_upgrade;
            proxy_set_header X-Real-IP $remote_addr;
            proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
            proxy_set_header X-Forwarded-Proto $scheme;
            
            # File Upload size extension
            client_max_body_size 20M;
        }

        # 3. RAG Server API Proxy Pass
        location /api/analysis/ {
            proxy_pass http://rag-server:8081/;
            proxy_http_version 1.1;
            proxy_set_header Host $host;
            proxy_set_header X-Real-IP $remote_addr;
            proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
            proxy_set_header X-Forwarded-Proto $scheme;
            
            client_max_body_size 20M;
        }
    }
}
```

---

## 3. Spring Boot Dockerfile

`/home/ubuntu/app/backend/Dockerfile` 경로에 작성합니다. 빌드 속도 및 이미지 경량화를 위해 **Multi-stage build** 형식을 취합니다.

```dockerfile
# Stage 1: Build stage
FROM maven:3.8.5-openjdk-17-slim AS build
WORKDIR /app

# Copy Maven POM and dependency cache layer
COPY pom.xml .
RUN mvn dependency:go-offline -B

# Copy actual source code and build package
COPY src ./src
RUN mvn clean package -DskipTests -B

# Stage 2: Runtime stage
FROM openjdk:17-jdk-slim
WORKDIR /app

# Create database volume directory
RUN mkdir -p /app/data

# Copy built artifact from build stage
COPY --from=build /app/target/*.jar app.jar

EXPOSE 8080

# Run container as non-root user for security best practices
RUN useradd -u 1001 appuser && chown -R appuser:appuser /app
USER appuser

ENTRYPOINT ["java", "-jar", "app.jar"]
```

---

## 4. 환경 변수 설정 템플릿 (`.env.example`)

`/home/ubuntu/app/.env.example` 및 `.env` 파일 설정 구조입니다.

```bash
# --------------------------------------------------
# LLM / RAG 연동 라우팅 설정
# --------------------------------------------------
# llm.provider: mock / bedrock
LLM_PROVIDER=bedrock

# llm.direct: true (백엔드 자체 비동기 Bedrock 연동) / false (FastAPI RAG 위임)
LLM_DIRECT=true

# --------------------------------------------------
# AWS Bedrock / S3 연동 자격 증명 (양방향 분기 구조 설계)
# --------------------------------------------------
AWS_REGION=us-east-1
AWS_BEDROCK_MODEL_ID=anthropic.claude-3-haiku-20240307-v1:0

# [방법 A] EC2 IAM Role 사용 시 (권장)
# - 아래의 ACCESS_KEY_ID와 SECRET_ACCESS_KEY 변수 값을 비워두면
# - AWS SDK가 자동으로 EC2 Instance Profile 자격 증명을 획득해 작동합니다.
AWS_ACCESS_KEY_ID=
AWS_SECRET_ACCESS_KEY=

# [방법 B] 직접 API 키 입력 배포 시
# - IAM Role 사용이 제한적인 경우에만 활성화하여 기입하십시오.
# AWS_ACCESS_KEY_ID=AKIAIOSFODNN7EXAMPLE
# AWS_SECRET_ACCESS_KEY=wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY

# --------------------------------------------------
# AWS S3 Storage Buckets
# --------------------------------------------------
AWS_S3_ENDPOINT=http://localhost:9000
AWS_S3_BUCKET_DOCUMENTS=yeonam-documents
AWS_S3_BUCKET_REPORTS=yeonam-reports
```

---

## 5. EC2 Ubuntu 배포 터미널 명령어

EC2 Ubuntu 인스턴스에 SSH 로그인한 후 수행하는 쉘 명령어 세트입니다.

```bash
# 1. 패키지 업데이트 및 기본 도구 설치
sudo apt-get update -y
sudo apt-get upgrade -y
sudo apt-get install git curl -y

# 2. Docker Engine 및 Docker Compose V2 설치
sudo mkdir -p /etc/apt/keyrings
curl -fsSL https://download.docker.com/linux/ubuntu/gpg | sudo gpg --dearmor -o /etc/apt/keyrings/docker.gpg
echo \
  "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] https://download.docker.com/linux/ubuntu \
  $(lsb_release -cs) stable" | sudo tee /etc/apt/sources.list.d/docker.list > /dev/null

sudo apt-get update -y
sudo apt-get install docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin -y

# 3. 현재 유저를 docker 그룹에 등록하여 sudo 없이 권한 부여 (재로그인 필요)
sudo usermod -aG docker $USER
newgrp docker

# 4. node/npm 설치 (프론트엔드 정적 파일 빌드용)
curl -fsSL https://deb.nodesource.com/setup_20.x | sudo -E bash -
sudo apt-get install -y nodejs

# 5. 프로젝트 소스 복사 및 폴더 구성
mkdir -p ~/app && cd ~/app
git clone https://github.com/your-repo/yeonam_tester.git .

# 6. 프론트엔드 정적 빌드 수행 (Nginx 서빙용)
cd ~/app/frontend
npm install
npm run build

# 7. 설정 파일 복사 및 환경변수 셋업
cd ~/app
cp .env.example .env
nano .env # IAM Role 사용 시 AWS 키 비우고 모델/리전 설정 후 저장

# 8. Docker Compose 컨테이너 오케스트레이션 실행 (백그라운드)
docker compose up -d --build
```

---

## 6. GitHub Actions 자동 배포 워크플로우 예시

`.github/workflows/deploy.yml` 경로에 저장하여 원격 커밋 시 EC2로 무중단 SSH 배포를 수행하는 파이프라인 스키마입니다.

```yaml
name: CI/CD Pipeline

on:
  push:
    branches: [ "main" ]

jobs:
  deploy:
    runs-on: ubuntu-latest

    steps:
    - name: Checkout Code
      uses: actions/checkout@v3

    # 프론트엔드 정적 파일 빌드
    - name: Set up Node.js
      uses: actions/setup-node@v3
      with:
        node-version: 20
        cache: 'npm'
        cache-dependency-path: frontend/package-lock.json

    - name: Build Frontend
      run: |
        cd frontend
        npm install
        npm run build

    # SSH 통신을 통한 EC2 배포 트리거
    - name: Copy build artifacts and redeploy via SSH
      uses: appleboy/scp-action@master
      with:
        host: ${{ secrets.EC2_HOST }}
        username: ubuntu
        key: ${{ secrets.EC2_SSH_KEY }}
        source: "frontend/dist/**"
        target: "/home/ubuntu/app"

    - name: Execute Remote SSH Commands
      uses: appleboy/ssh-action@master
      with:
        host: ${{ secrets.EC2_HOST }}
        username: ubuntu
        key: ${{ secrets.EC2_SSH_KEY }}
        script: |
          cd /home/ubuntu/app
          git pull origin main
          # 이전 프론트 빌드 dist 이동
          cp -r /home/ubuntu/app/frontend/dist ~/app/frontend/
          # 컨테이너 무중단 재빌드 및 배포
          docker compose up -d --build backend nginx
          docker image prune -f
```
