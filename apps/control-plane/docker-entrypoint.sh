#!/bin/sh
set -eu

load_secret() {
  variable_name="$1"
  file_variable_name="${variable_name}_FILE"
  eval "file_path=\${$file_variable_name:-}"
  if [ -z "$file_path" ]; then
    return
  fi
  if [ ! -f "$file_path" ] || [ -L "$file_path" ]; then
    echo "$file_variable_name must reference a regular non-symlink file" >&2
    exit 1
  fi
  value=$(tr -d '\r\n' < "$file_path")
  if [ -z "$value" ]; then
    echo "$file_variable_name cannot be empty" >&2
    exit 1
  fi
  export "$variable_name=$value"
  unset "$file_variable_name"
}

for variable_name in \
  DATABASE_PASSWORD \
  REDIS_PASSWORD \
  REMOTE_DESKTOP_TICKET_SECRET \
  AGENT_CAPABILITY_TOKEN_SECRET \
  AGENT_ACTION_PAYLOAD_SECRET \
  AUDIT_EXPORT_SIGNING_KEY
do
  load_secret "$variable_name"
done

exec java -XX:MaxRAMPercentage=75 -jar /app/app.jar
