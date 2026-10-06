// @vitest-environment node
import { createServer as createHttpServer, type Server } from 'node:http'
import type { AddressInfo } from 'node:net'
import { createServer, type ViteDevServer, type ProxyOptions } from 'vite'
import { afterAll, beforeAll, describe, expect, it } from 'vitest'

import viteConfig from '../../vite.config'

describe('development proxy', () => {
  let upstream: Server
  let vite: ViteDevServer
  let origin: string

  beforeAll(async () => {
    upstream = createHttpServer(async (request, response) => {
      const chunks: Buffer[] = []
      for await (const chunk of request) chunks.push(Buffer.from(chunk))
      response.setHeader('Content-Type', 'application/json')
      response.end(JSON.stringify({
        host: request.headers.host,
        origin: request.headers.origin,
        method: request.method,
        path: request.url,
        idempotencyKey: request.headers['idempotency-key'],
        body: Buffer.concat(chunks).toString(),
      }))
    })
    await new Promise<void>((resolve) => upstream.listen(0, '127.0.0.1', resolve))
    const target = `http://127.0.0.1:${(upstream.address() as AddressInfo).port}`
    const proxy = Object.fromEntries(Object.entries(viteConfig.server!.proxy!).map(([path, options]) => [
      path, { ...options as ProxyOptions, target },
    ]))
    vite = await createServer({
      configFile: false,
      server: { host: '127.0.0.1', port: 0, proxy },
    })
    await vite.listen()
    origin = `http://127.0.0.1:${(vite.httpServer!.address() as AddressInfo).port}`
  })

  afterAll(async () => {
    await vite?.close()
    if (upstream) await new Promise<void>((resolve, reject) => {
      upstream.close((error) => error ? reject(error) : resolve())
    })
  })

  it.each(['/api/v1/projects', '/actuator/health'])('preserves same-origin headers for %s on a dynamic port', async (path) => {
    const response = await fetch(`${origin}${path}`, { headers: { Origin: origin } })
    expect(await response.json()).toMatchObject({
      host: new URL(origin).host,
      origin,
      path,
    })
  })

  it('forwards project submissions and idempotency keys unchanged', async () => {
    const body = JSON.stringify({ name: 'Proxy regression', entryMode: 'MATERIALS' })
    const response = await fetch(`${origin}/api/v1/projects`, {
      method: 'POST',
      headers: { Origin: origin, 'Content-Type': 'application/json', 'Idempotency-Key': 'proxy-test' },
      body,
    })
    expect(await response.json()).toMatchObject({
      host: new URL(origin).host,
      origin,
      method: 'POST',
      idempotencyKey: 'proxy-test',
      body,
    })
  })

  it('does not disguise a foreign origin as a same-origin request', async () => {
    const response = await fetch(`${origin}/api/v1/projects`, {
      headers: { Origin: 'https://untrusted.example' },
    })
    expect(await response.json()).toMatchObject({
      host: new URL(origin).host,
      origin: 'https://untrusted.example',
    })
  })
})
