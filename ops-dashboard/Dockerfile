# 빌드와 런타임을 분리한다. 최종 이미지에 node_modules 를 남기면
# 정적 파일만 서빙하는 컨테이너가 수백 MB 짜리 공격 표면을 함께 들고 다닌다.
FROM node:26-alpine AS build
WORKDIR /build

# 의존성 레이어를 먼저 굳힌다 — 소스만 바뀌면 npm ci 를 다시 돌리지 않는다.
COPY package.json package-lock.json ./
RUN npm ci

COPY tsconfig.json vite.config.ts index.html ./
COPY src ./src
RUN npm run build

FROM nginx:1.31-alpine
RUN apk add --no-cache curl tzdata \
 && cp /usr/share/zoneinfo/Asia/Seoul /etc/localtime \
 && echo "Asia/Seoul" > /etc/timezone
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /build/dist /usr/share/nginx/html
EXPOSE 8086
