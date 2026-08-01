# JWT 本地验证参考

本文只维护可复制的 JWT 验证步骤；MVP 实施过程统一见 [mvp-backlog.md](./mvp-backlog.md)。

仓库内的 JWT 测试使用确定性的 RSA/JWK 测试资源，测试密钥位于 `src/test/resources`，不会进入运行时配置或发布包。运行：

```powershell
.\mvnw.cmd -pl a2a4j-gateway-spring-boot-starter -am test `
  -Dtest=GatewayJwtAuthenticationConverterTest
```

如需验证完整 Resource Server 链路，可使用本地 Keycloak、Dex 或其他 OIDC IdP：

1. 启动本地 IdP，创建 issuer、audience=`a2a-gateway` 和测试用户；管理员密码通过容器 Secret 或环境变量注入。
2. 只通过环境变量传递 `A2A_GATEWAY_JWT_ISSUER_URI`、`A2A_GATEWAY_JWT_AUDIENCE` 及客户端凭据，不把 token、私钥或密码写入仓库。
3. 启动样例 Gateway：

```powershell
$env:A2A_GATEWAY_JWT_ISSUER_URI = 'http://localhost:8080/realms/a2a'
$env:A2A_GATEWAY_JWT_AUDIENCE = 'a2a-gateway'
java -jar gateway-hello-world-0.0.1-exec.jar `
  --a2a.gateway.security.mode=jwt `
  --a2a.gateway.security.jwt.issuer-uri=$env:A2A_GATEWAY_JWT_ISSUER_URI `
  ("--a2a.gateway.security.jwt.audiences[0]=" + $env:A2A_GATEWAY_JWT_AUDIENCE)
```

4. 使用短时测试 token 调用 `/message:send`，确认无 token 返回 `401`、无租户/权限返回 `403`，并检查审计事件中的 `tenantId` 与 `requestId`，不检查或打印 token 内容。

本地 JWT 验证只覆盖单实例行为；JWKS 轮换、IdP 故障、跨实例缓存和密钥托管属于企业版验收范围。
