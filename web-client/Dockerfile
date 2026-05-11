# syntax=docker/dockerfile:1.6
FROM docker.io/library/node:20-alpine AS build
WORKDIR /src
COPY web-client/package.json web-client/package-lock.json* ./
RUN --mount=type=cache,target=/root/.npm npm install
COPY web-client ./
RUN npm run build

FROM docker.io/library/nginx:1.27-alpine
COPY web-client/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /src/dist /usr/share/nginx/html
EXPOSE 80
