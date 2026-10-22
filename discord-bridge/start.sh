#!/bin/sh
cd "$(dirname "$0")"
exec java -jar spawner-search-discord.jar "$@"
