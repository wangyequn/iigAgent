@echo off
setlocal
cd /d C:\amp || exit /b 1
if exist "%~dp0local-env.cmd" call "%~dp0local-env.cmd"
set AMP_SERVER_PORT=18081
set AMP_SANDBOX_ENABLED=true
set AMP_SANDBOX_IMAGE=alpine:3.19
set DOCKER_BIN=C:\Program Files\Docker\Docker\resources\bin\docker.exe
set AMP_REDIS_URI=redis://127.0.0.1:6379
set AMP_PG_URI=jdbc:postgresql://127.0.0.1:15432/amp
set AMP_PG_USER=amp
set AMP_PG_PASSWORD=amp
set AMP_MODEL_ID=deepseek:deepseek-flash
set AMP_API_KEY=s0-demo-key
set AMP_TENANT_ID=acme
if not exist logs mkdir logs
mvn -pl platform-open-api spring-boot:run > logs\amp-open-api-18081.log 2>&1
