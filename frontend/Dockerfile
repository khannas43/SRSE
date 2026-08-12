# ---- Build stage ----
FROM node:20-alpine AS build
WORKDIR /app
# Next.js inlines NEXT_PUBLIC_* values into the client bundle at build time —
# a runtime `environment:` var in docker-compose.yml has NO effect on them.
# Must be passed as a build ARG (docker-compose.yml's build.args) to actually
# change which backend URL the deployed app talks to.
ARG NEXT_PUBLIC_API_BASE=http://localhost:8080
ENV NEXT_PUBLIC_API_BASE=$NEXT_PUBLIC_API_BASE
COPY package.json package-lock.json* ./
RUN npm install
COPY . .
RUN npm run build

# ---- Runtime stage ----
FROM node:20-alpine
WORKDIR /app
ENV NODE_ENV=production
COPY --from=build /app/.next ./.next
COPY --from=build /app/node_modules ./node_modules
COPY --from=build /app/package.json ./package.json
COPY --from=build /app/next.config.ts ./next.config.ts
COPY --from=build /app/public ./public
EXPOSE 3000
CMD ["npm", "start"]
