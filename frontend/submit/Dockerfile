FROM node:22-alpine AS build
WORKDIR /app/frontend/submit
COPY frontend/submit/package*.json ./
RUN npm ci
COPY frontend/submit/ ./
COPY frontend/shared/ /app/frontend/shared/
RUN npm run build

FROM nginx:1.27-alpine
COPY --from=build /app/frontend/submit/dist /usr/share/nginx/html
COPY infra/submit-nginx.conf /etc/nginx/conf.d/default.conf
