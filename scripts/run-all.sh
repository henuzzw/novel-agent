#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

if [[ ! -f "$repo_root/.env.local" ]]; then
    echo "Missing .env.local. Copy .env.example and fill in local credentials." >&2
    exit 1
fi

if [[ ! -d "$repo_root/apps/web/node_modules" ]]; then
    echo "Missing frontend dependencies. Run npm install in apps/web first." >&2
    exit 1
fi

cd "$repo_root/apps/web"
exec npm run dev:all
