FROM node:24.11.0-alpine
WORKDIR /app
COPY package.json ./
COPY src ./src
RUN addgroup -S ontoflow && adduser -S ontoflow -G ontoflow && chown -R ontoflow:ontoflow /app
USER ontoflow
EXPOSE 8090
CMD ["node", "src/server.js"]

