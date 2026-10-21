# Frontend (Next.js 16) — build multi-etapa con pnpm, runtime standalone.
#
# NEXT_PUBLIC_API_URL se inyecta como build-ARG: Next.js inlinea las variables
# NEXT_PUBLIC_* en el bundle del browser en tiempo de BUILD, así que no existe
# como variable runtime (un configmap/env en k8s no la cambiaría):
#   compose / kind local : default http://localhost:8080 (gateway publicado o
#                          port-forward svc/gateway 8080:80)
#   producción           : docker build --build-arg NEXT_PUBLIC_API_URL=https://<dominio>
# Cambiar la URL = rebuild de la imagen.

FROM node:22-alpine AS deps
# pnpm vía npm en vez de corepack: evita la rotación de claves de verificación
# de corepack en imágenes base que no lo actualizan. Versión = packageManager
# de package.json.
RUN npm install -g pnpm@12.3.4
WORKDIR /app
COPY package.json pnpm-lock.yaml pnpm-workspace.yaml ./
RUN pnpm install --frozen-lockfile

FROM deps AS build
WORKDIR /app
COPY . .
ARG NEXT_PUBLIC_API_URL=http://localhost:8080
ENV NEXT_PUBLIC_API_URL=$NEXT_PUBLIC_API_URL
ENV NEXT_TELEMETRY_DISABLED=1
RUN pnpm build

FROM node:22-alpine AS runner
ENV NODE_ENV=production
ENV NEXT_TELEMETRY_DISABLED=1
# Mismo uid 1001 no-root que los microservicios Java (ver services/*/Dockerfile).
RUN addgroup --system --gid 1001 nodejs \
    && adduser --system --uid 1001 nextjs
WORKDIR /app

COPY --from=build --chown=nextjs:nodejs /app/.next/standalone ./
COPY --from=build --chown=nextjs:nodejs /app/.next/static ./.next/static
COPY --from=build --chown=nextjs:nodejs /app/public ./public

USER nextjs
EXPOSE 3000
ENV PORT=3000
ENV HOSTNAME=0.0.0.0

CMD ["node", "server.js"]
