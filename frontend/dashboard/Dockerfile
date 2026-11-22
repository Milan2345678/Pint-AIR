FROM node:22-alpine AS build
WORKDIR /app/frontend/dashboard
COPY frontend/dashboard/package*.json ./
RUN npm ci
COPY frontend/dashboard/ ./
COPY frontend/shared/ /app/frontend/shared/
RUN npm run build

FROM nginx:1.27-alpine
COPY --from=build /app/frontend/dashboard/dist /usr/share/nginx/html
COPY infra/dashboard-nginx.conf /etc/nginx/conf.d/default.conf
