/**
 * 本地 Mock OIDC IdP（联调用，非生产）。
 * 提供 discovery / JWKS / mint token，私钥仅存内存。
 *
 *   node scripts/mock-oidc-server.mjs
 *   GET  http://127.0.0.1:18090/realms/amp/.well-known/openid-configuration
 *   GET  http://127.0.0.1:18090/realms/amp/jwks
 *   POST http://127.0.0.1:18090/realms/amp/mint  {sub,tenant,roles}
 *   GET  http://127.0.0.1:18090/mint?sub=alice&tenant=acme&roles=developer
 */
import http from 'node:http'
import crypto from 'node:crypto'
import { URL } from 'node:url'

const PORT = Number(process.env.MOCK_OIDC_PORT || 18090)
const HOST = process.env.MOCK_OIDC_HOST || '127.0.0.1'
const BASE = process.env.MOCK_OIDC_BASE || `http://${HOST}:${PORT}`
const REALM = 'amp'
const ISSUER = `${BASE}/realms/${REALM}`

const { publicKey, privateKey } = crypto.generateKeyPairSync('rsa', {
  modulusLength: 2048,
  publicKeyEncoding: { type: 'spki', format: 'jwk' },
  privateKeyEncoding: { type: 'pkcs8', format: 'jwk' },
})

const kid = 'mock-oidc-1'
const jwk = {
  ...publicKey,
  kid,
  use: 'sig',
  alg: 'RS256',
}

const privateKeyObj = crypto.createPrivateKey({ key: privateKey, format: 'jwk' })

function b64url(input) {
  return Buffer.from(input).toString('base64url')
}

function signJwt(claims, expiresInSec = 3600) {
  const now = Math.floor(Date.now() / 1000)
  const header = { alg: 'RS256', typ: 'JWT', kid }
  const payload = {
    iss: ISSUER,
    aud: 'amp-console',
    azp: 'amp-console',
    iat: now,
    exp: now + expiresInSec,
    ...claims,
  }
  const signingInput = `${b64url(JSON.stringify(header))}.${b64url(JSON.stringify(payload))}`
  const sig = crypto.sign('RSA-SHA256', Buffer.from(signingInput), privateKeyObj)
  return `${signingInput}.${sig.toString('base64url')}`
}

function json(res, status, body) {
  const text = JSON.stringify(body, null, 2)
  res.writeHead(status, {
    'Content-Type': 'application/json;charset=UTF-8',
    'Access-Control-Allow-Origin': '*',
    'Access-Control-Allow-Headers': '*',
    'Access-Control-Allow-Methods': 'GET,POST,OPTIONS',
  })
  res.end(text)
}

function readBody(req) {
  return new Promise((resolve) => {
    const chunks = []
    req.on('data', (c) => chunks.push(c))
    req.on('end', () => {
      const raw = Buffer.concat(chunks).toString('utf8')
      try {
        resolve(raw ? JSON.parse(raw) : {})
      } catch {
        resolve({})
      }
    })
  })
}

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, BASE)
  const path = url.pathname
  if (req.method === 'OPTIONS') {
    return json(res, 204, {})
  }
  // pathname only (no host)
  const discoveryPaths = new Set([
    '/.well-known/openid-configuration',
    '/realms/amp/.well-known/openid-configuration',
  ])
  const jwksPaths = new Set(['/jwks', '/realms/amp/jwks'])
  const mintPaths = new Set(['/mint', '/realms/amp/mint'])
  if (discoveryPaths.has(path)) {
    return json(res, 200, {
      issuer: ISSUER,
      authorization_endpoint: `${ISSUER}/protocol/openid-connect/auth`,
      token_endpoint: `${ISSUER}/protocol/openid-connect/token`,
      jwks_uri: `${ISSUER}/jwks`,
      id_token_signing_alg_values_supported: ['RS256'],
      response_types_supported: ['code'],
      subject_types_supported: ['public'],
      grant_types_supported: ['authorization_code', 'client_credentials', 'password', 'refresh_token'],
    })
  }
  if (jwksPaths.has(path)) {
    return json(res, 200, { keys: [jwk] })
  }
  if (path === '/health') {
    return json(res, 200, { ok: true, issuer: ISSUER })
  }
  if (mintPaths.has(path)) {
    let body = {}
    if (req.method === 'POST') body = await readBody(req)
    else {
      body = {
        sub: url.searchParams.get('sub'),
        tenant: url.searchParams.get('tenant'),
        roles: url.searchParams.get('roles'),
      }
    }
    const roles = Array.isArray(body.roles)
      ? body.roles
      : String(body.roles || 'developer').split(',').map((s) => s.trim()).filter(Boolean)
    const sub = body.sub || body.userId || 'alice'
    const tenant = body.tenant || body.tenantId || 'acme'
    const accessToken = signJwt({
      sub,
      tenant,
      preferred_username: sub,
      name: sub,
      roles,
      realm_access: { roles },
      resource_access: { 'amp-console': { roles } },
    })
    const idToken = signJwt({
      sub,
      tenant,
      preferred_username: sub,
      name: sub,
      roles,
      realm_access: { roles },
    })
    return json(res, 200, {
      access_token: accessToken,
      id_token: idToken,
      token_type: 'Bearer',
      expires_in: 3600,
      roles,
      sub,
      tenant,
    })
  }
  json(res, 404, { error: 'not_found', path })
})

server.listen(PORT, HOST, () => {
  console.log(`[mock-oidc] listening ${BASE} issuer=${ISSUER}`)
  console.log(`[mock-oidc] mint: GET ${BASE}/mint?sub=alice&tenant=acme&roles=developer`)
})
