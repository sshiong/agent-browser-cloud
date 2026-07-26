FROM node:20-bookworm-slim AS build
WORKDIR /workspace
ARG VITE_AUTH_MODE
ARG VITE_LOCAL_ROLES
ARG VITE_TENANT_ID
ARG VITE_ACTOR_ID
ARG VITE_ENABLE_FIXTURES
ENV VITE_AUTH_MODE=${VITE_AUTH_MODE}
ENV VITE_LOCAL_ROLES=${VITE_LOCAL_ROLES}
ENV VITE_TENANT_ID=${VITE_TENANT_ID}
ENV VITE_ACTOR_ID=${VITE_ACTOR_ID}
ENV VITE_ENABLE_FIXTURES=${VITE_ENABLE_FIXTURES}
RUN corepack enable
COPY apps/web-console/package.json apps/web-console/pnpm-lock.yaml ./
RUN pnpm install --frozen-lockfile
COPY apps/web-console ./
RUN pnpm build

FROM nginxinc/nginx-unprivileged:1.27-alpine
COPY apps/web-console/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /workspace/dist /usr/share/nginx/html
EXPOSE 8080
