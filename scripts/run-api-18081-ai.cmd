@echo off
set AMP_SERVER_PORT=18081
set AMP_REDIS_URI=redis://127.0.0.1:6379
set AMP_PG_URI=jdbc:postgresql://127.0.0.1:15432/amp
set AMP_PG_USER=amp
set AMP_PG_PASSWORD=amp
set AMP_MODEL_ID=deepseek:deepseek-flash
set AMP_API_KEY=s0-demo-key
set AMP_TENANT_ID=acme
set AMP_NACOS_AI_ENABLED=true
set AMP_NACOS_AI_PROMPT_ENABLED=true
set AMP_NACOS_SERVER=127.0.0.1:8848
set AMP_NACOS_AI_IDENTITY_KEY=serverIdentity
set AMP_NACOS_AI_IDENTITY_VALUE=security
cd /d "%~dp0.."
"D:\Program Files (x86)\apache-maven-3.8.8\bin\mvn.cmd" -pl platform-open-api spring-boot:run
