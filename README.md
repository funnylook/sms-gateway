# 📱 SMS Gateway — Docker 版

短信网关：接收短信 → 网页显示，网页发短信 → 手机发送。统一端口 **8989**，Docker 单容器部署。

## 架构

```
┌─────────────────────────────────┐
│  Docker Container (sms-gateway)  │
│                                 │
│  :8989 →  Nginx (Web UI)       │
│         →  /api/* → Flask :8988 │
│                                 │
│  /app/data/sms.db (SQLite)      │
└─────────────────────────────────┘
```

| 组件 | 端口 | 职责 |
|------|------|------|
| Nginx | **8989**（对外） | 网页 UI + 反向代理 API |
| Flask | 8988（内部） | 短信收发 API |
| SQLite | - | 短信记录存储 |

> **只用记一个端口 8989**：浏览器访问是它，手机 App 连接也是它。

---

## 一键部署

### 环境要求
- Docker + Docker Compose
- 开放 8989 端口（HTTP）

### 部署

```bash
# 1. 克隆项目
git clone https://github.com/funnylook/sms-gateway.git
cd sms-gateway

# 2. 启动
docker compose up -d

# 3. 验证
curl http://localhost:8989/api/status
# 返回: {"status":"ok","time":"..."}
```

启动完成后浏览器打开 `http://服务器IP:8989`

---

## 手动部署（不用 compose）

```bash
# 构建镜像
docker build -t sms-gateway .

# 启动（数据持久化到 ./data 目录）
docker run -d \
  --name sms-gateway \
  --restart unless-stopped \
  -p 8989:8989 \
  -v ./data:/app/data \
  sms-gateway
```

---

## 配置说明

| 文件 | 作用 |
|------|------|
| `Dockerfile` | 构建镜像：nginx:alpine + Python3 + Flask |
| `nginx.conf` | Nginx 配置（8989 端口 + API 代理） |
| `start.sh` | 容器启动脚本 |
| `sms_server.py` | Flask 后端 API |
| `docker-compose.yml` | Compose 声明 |
| `app/data/sms.db` | SQLite 数据（自动创建） |

---

## API 接口

| 接口 | 方法 | 说明 |
|------|------|------|
| `/api/status` | GET | 健康检查 |
| `/api/sms/send` | POST | 发短信：`{"number","message","slot","phone_id"}` |
| `/api/sms/list` | GET | 短信记录：`?page=1&per_page=30` |
| `/api/sms/receive` | POST | 收到短信（App 回调） |
| `/api/sms/pending` | GET | App 拉取待发命令 |
| `/api/sms/done` | POST | App 上报发送结果 |
| `/api/phones` | GET | 设备列表 |

> slot: `0`=卡1, `1`=卡2

---

## 数据备份

短信记录在 `data/sms.db`，定期备份即可：

```bash
# 备份
cp data/sms.db sms-backup-$(date +%Y%m%d).db
```

---

## 停止/重启

```bash
docker compose down          # 停止
docker compose up -d         # 启动
docker compose restart       # 重启
```

---

## 外网访问

- **域名反代**：用 Nginx/HAProxy 把 `sms.yourdomain.com` → `8989`
- **Cloudflare Tunnel**：配置 `sms.yourdomain.com` → `http://localhost:8989`
- **端口映射**：服务器防火墙开放 8989/TCP

---

## License

```
