# ---- Build stage: produce the static Vite build ----
FROM node:22-alpine AS build
WORKDIR /app

COPY package*.json ./
RUN npm ci

COPY . .
# Same-origin by default: the browser calls /api, nginx proxies it to the backend.
# Override only if the API lives on a different origin.
ARG VITE_API_BASE_URL=""
ENV VITE_API_BASE_URL=$VITE_API_BASE_URL
RUN npm run build

# ---- Serve stage: nginx serving the SPA + reverse-proxying /api + TLS ----
FROM nginx:1.27-alpine
COPY --from=build /app/dist /usr/share/nginx/html
# Templated so ${DOMAIN} is substituted at container start (envsubst).
COPY nginx/app.conf.template /etc/nginx/templates/default.conf.template
EXPOSE 80 443
