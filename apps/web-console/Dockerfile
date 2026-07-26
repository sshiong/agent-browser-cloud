FROM node:20-bookworm-slim AS build
WORKDIR /workspace
RUN corepack enable
COPY apps/web-console/package.json apps/web-console/pnpm-lock.yaml ./
RUN pnpm install --frozen-lockfile
COPY apps/web-console ./
RUN pnpm build

FROM nginxinc/nginx-unprivileged:1.27-alpine
COPY apps/web-console/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /workspace/dist /usr/share/nginx/html
EXPOSE 8080
