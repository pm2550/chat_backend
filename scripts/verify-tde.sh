#!/usr/bin/env bash
set -euo pipefail

MYSQL_CONTAINER="${MYSQL_CONTAINER:-deploy-mysql-1}"
DB_NAME="${DB_NAME:-chatapp}"
EXPECTED_DATA_FILE="${EXPECTED_KEYRING_DATA_FILE:-/var/lib/mysql-keyring/component_keyring_file}"

mysql_root() {
  sudo docker exec "$MYSQL_CONTAINER" sh -c "MYSQL_PWD=\"\$MYSQL_ROOT_PASSWORD\" mysql -uroot -N -B -e \"$1\""
}

status="$(mysql_root "SELECT status_value FROM performance_schema.keyring_component_status WHERE status_key='Component_status';")"
if [[ "$status" != "Active" ]]; then
  echo "TDE_VERIFY_FAIL component_status=$status" >&2
  exit 1
fi

data_file="$(mysql_root "SELECT status_value FROM performance_schema.keyring_component_status WHERE status_key='Data_file';")"
if [[ "$data_file" != "$EXPECTED_DATA_FILE" ]]; then
  echo "TDE_VERIFY_FAIL data_file=$data_file expected=$EXPECTED_DATA_FILE" >&2
  exit 1
fi

missing="$(mysql_root "SELECT SUM(CASE WHEN LOCATE('ENCRYPTION', create_options) = 0 THEN 1 ELSE 0 END) FROM information_schema.tables WHERE table_schema='$DB_NAME' AND engine='InnoDB';")"
if [[ "$missing" != "0" ]]; then
  echo "TDE_VERIFY_FAIL missing_encryption=$missing" >&2
  mysql_root "SELECT table_name, create_options FROM information_schema.tables WHERE table_schema='$DB_NAME' AND engine='InnoDB' AND LOCATE('ENCRYPTION', create_options) = 0 ORDER BY table_name;" >&2
  exit 1
fi

if ! sudo docker exec "$MYSQL_CONTAINER" test -s "$EXPECTED_DATA_FILE"; then
  echo "TDE_VERIFY_FAIL keyring data file missing or empty: $EXPECTED_DATA_FILE" >&2
  exit 1
fi

if sudo docker exec "$MYSQL_CONTAINER" test -e /var/lib/mysql/component_keyring_file; then
  echo "TDE_VERIFY_FAIL keyring data file exists in datadir" >&2
  exit 1
fi

total="$(mysql_root "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='$DB_NAME' AND engine='InnoDB';")"
echo "TDE_VERIFY_OK tables=$total data_file=$data_file"
