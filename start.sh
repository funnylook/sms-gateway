#!/bin/sh
# Start Flask backend on port 8988 (internal) in background
cd /app
python3 sms_server.py &

# Give Flask a moment to start
sleep 2

# Start nginx in foreground on port 8989
exec nginx -g "daemon off;"
