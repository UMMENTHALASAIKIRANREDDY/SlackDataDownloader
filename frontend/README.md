# Slack DM/MPIM Export – Frontend

React (Vite) UI for the Slack DM/MPIM export. Sends a POST request to the Spring Boot backend and downloads the returned ZIP file.

## Setup

```bash
cd frontend
npm install
```

## Run

Start the backend on port 8080, then:

```bash
npm run dev
```

Open http://localhost:3000. The dev server proxies `/api` to `http://localhost:8080`.

## Build

```bash
npm run build
```

Output is in `dist/`. Serve it with any static host or point the Spring Boot app at it if you add static resource handling.
