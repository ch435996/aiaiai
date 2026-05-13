#!/bin/bash
# 反幻觉护栏 — 检索分数分布采集脚本
# 用法: bash test-antihallucination.sh
# 前提: JAVA_HOME 已设置，环境变量已配置

set -e

APP_LOG="app-test-$(date +%Y%m%d_%H%M%S).log"
RESULT_FILE="retrieval-scores-$(date +%Y%m%d_%H%M%S).txt"
BASE_URL="http://localhost:8080"

cleanup() {
    if [ -n "$APP_PID" ]; then
        kill "$APP_PID" 2>/dev/null || true
    fi
}
trap cleanup EXIT

echo "=== 1. 启动应用 ==="
mvn spring-boot:run > "$APP_LOG" 2>&1 &
APP_PID=$!

echo "等待应用就绪..."
for i in $(seq 1 60); do
    if curl -s "$BASE_URL/api/sessions" > /dev/null 2>&1; then
        echo "应用已启动 (${i}s)"
        break
    fi
    sleep 2
done

echo "=== 2. 创建测试 session ==="
SESSION_RESP=$(curl -s -X POST "$BASE_URL/api/chat" \
    -H "Content-Type: application/json" \
    -d '{"sessionId":null,"message":"你好"}')
SESSION_ID=$(echo "$SESSION_RESP" | sed 's/.*"sessionId":"\([^"]*\)".*/\1/')
echo "Session: $SESSION_ID"

echo "=== 3. 发送测试问题 ==="

# ── 高相关（预期 >0.80）──
echo "[高相关-1] SnowflakeNet 雪花点反卷积的核心思想是什么？"
curl -s -X POST "$BASE_URL/api/chat" \
    -H "Content-Type: application/json" \
    -d "{\"sessionId\":\"$SESSION_ID\",\"message\":\"SnowflakeNet 的 Snowflake Point Deconvolution 是如何工作的？\"}" > /dev/null
sleep 3

echo "[高相关-2] PoinTr 几何感知变换器"
curl -s -X POST "$BASE_URL/api/chat" \
    -H "Content-Type: application/json" \
    -d "{\"sessionId\":\"$SESSION_ID\",\"message\":\"PoinTr 论文中提出的几何感知变换器（Geometry-Aware Transformer）的核心机制是什么？\"}" > /dev/null
sleep 3

echo "[高相关-3] FoldingNet 折叠解码器"
curl -s -X POST "$BASE_URL/api/chat" \
    -H "Content-Type: application/json" \
    -d "{\"sessionId\":\"$SESSION_ID\",\"message\":\"FoldingNet 的 folding-based decoder 是如何从 2D grid 重建 3D 点云的？\"}" > /dev/null
sleep 3

echo "[高相关-4] PCN 点云补全网络"
curl -s -X POST "$BASE_URL/api/chat" \
    -H "Content-Type: application/json" \
    -d "{\"sessionId\":\"$SESSION_ID\",\"message\":\"PCN（Point Completion Network）的网络结构和训练策略是什么？\"}" > /dev/null
sleep 3

# ── 中相关（预期 0.60-0.80）──
echo "[中相关-1] 损失函数通用问题"
curl -s -X POST "$BASE_URL/api/chat" \
    -H "Content-Type: application/json" \
    -d "{\"sessionId\":\"$SESSION_ID\",\"message\":\"点云补全任务中常用的损失函数有哪些？Chamfer Distance 和 Earth Mover Distance 的优缺点对比？\"}" > /dev/null
sleep 3

echo "[中相关-2] Transformer 在点云补全中的应用"
curl -s -X POST "$BASE_URL/api/chat" \
    -H "Content-Type: application/json" \
    -d "{\"sessionId\":\"$SESSION_ID\",\"message\":\"Transformer 架构在点云补全领域有哪些应用和改进？\"}" > /dev/null
sleep 3

echo "[中相关-3] 粗到细补全策略"
curl -s -X POST "$BASE_URL/api/chat" \
    -H "Content-Type: application/json" \
    -d "{\"sessionId\":\"$SESSION_ID\",\"message\":\"点云补全中 coarse-to-fine 的策略有哪些代表性方法？各有什么特点？\"}" > /dev/null
sleep 3

# ── 弱相关（预期 0.35-0.60）──
echo "[弱相关-1] 三维重建评价指标"
curl -s -X POST "$BASE_URL/api/chat" \
    -H "Content-Type: application/json" \
    -d "{\"sessionId\":\"$SESSION_ID\",\"message\":\"三维重建任务常用的评价指标有哪些？如何计算 F-score？\"}" > /dev/null
sleep 3

echo "[弱相关-2] 点云上采样 vs 补全"
curl -s -X POST "$BASE_URL/api/chat" \
    -H "Content-Type: application/json" \
    -d "{\"sessionId\":\"$SESSION_ID\",\"message\":\"点云上采样（upsampling）和点云补全（completion）有什么区别和联系？\"}" > /dev/null
sleep 3

echo "[弱相关-3] 自监督学习 in 3D"
curl -s -X POST "$BASE_URL/api/chat" \
    -H "Content-Type: application/json" \
    -d "{\"sessionId\":\"$SESSION_ID\",\"message\":\"自监督学习在三维视觉中有哪些应用？与点云补全有什么关系？\"}" > /dev/null
sleep 3

# ── 越界（预期 <0.35）──
echo "[越界-1] 强化学习"
curl -s -X POST "$BASE_URL/api/chat" \
    -H "Content-Type: application/json" \
    -d "{\"sessionId\":\"$SESSION_ID\",\"message\":\"什么是深度强化学习？DQN 算法的基本原理是什么？\"}" > /dev/null
sleep 3

echo "[越界-2] Web 开发"
curl -s -X POST "$BASE_URL/api/chat" \
    -H "Content-Type: application/json" \
    -d "{\"sessionId\":\"$SESSION_ID\",\"message\":\"如何用 Spring Boot 构建一个 RESTful API？\"}" > /dev/null
sleep 3

# ── 边界模糊 ──
echo "[边界-1] 点云配准"
curl -s -X POST "$BASE_URL/api/chat" \
    -H "Content-Type: application/json" \
    -d "{\"sessionId\":\"$SESSION_ID\",\"message\":\"ICP 点云配准算法的原理是什么？\"}" > /dev/null
sleep 3

echo "[边界-2] 3D Gaussian Splatting"
curl -s -X POST "$BASE_URL/api/chat" \
    -H "Content-Type: application/json" \
    -d "{\"sessionId\":\"$SESSION_ID\",\"message\":\"3D Gaussian Splatting 与传统点云表示有什么不同？\"}" > /dev/null
sleep 3

echo ""
echo "=== 4. 测试完成，停止应用 ==="
kill "$APP_PID" 2>/dev/null || true
sleep 3

echo ""
echo "=== 5. 提取检索分数 ==="
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━" > "$RESULT_FILE"
echo "  检索分数分布采集结果 — $(date '+%Y-%m-%d %H:%M:%S')" >> "$RESULT_FILE"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━" >> "$RESULT_FILE"

grep "检索 '" "$APP_LOG" | while read -r line; do
    query=$(echo "$line" | sed 's/.*检索 '\''\(.*\)'\'':.*/\1/')
    scores=$(echo "$line" | sed 's/.*scores=\[\(.*\)\]/\1/')
    echo "" >> "$RESULT_FILE"
    echo "查询: $query" >> "$RESULT_FILE"
    echo "分数: [$scores]" >> "$RESULT_FILE"
done

echo "" >> "$RESULT_FILE"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━" >> "$RESULT_FILE"
echo "原始日志: $APP_LOG" >> "$RESULT_FILE"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━" >> "$RESULT_FILE"

echo ""
echo "完成！结果文件: $RESULT_FILE"
echo "原始日志: $APP_LOG"
echo ""
echo "──── 快速预览 ────"
cat "$RESULT_FILE"
