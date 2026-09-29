@echo off
chcp 65001 >nul
cd /d "
D:\sinosoft\江苏二期\基于JAVA的智能体应用平台
"
if exist "%~dp0local-env.cmd" call "%~dp0local-env.cmd"
if "%DEEPSEEK_API_KEY%"=="" echo WARN: DEEPSEEK_API_KEY not set (scripts/local-env.cmd)
set AMP_MODEL_ID=deepseek:deepseek-flash
set AMP_SERVER_PORT=18081
set AMP_REDIS_URI=redis://127.0.0.1:6379
set AMP_API_KEY=s0-demo-key
set AMP_TENANT_ID=acme
set AMP_PG_URI=jdbc:postgresql://127.0.0.1:15432/amp
set AMP_PG_USER=amp
set AMP_PG_PASSWORD=amp
if not exist logs mkdir logs
mvn -pl platform-open-api spring-boot:run > logs\amp-open-api.log 2>&1
