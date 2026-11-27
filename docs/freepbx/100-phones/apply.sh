#!/bin/bash
#
# Применение конфигурации GSM-шлюзов на сервере FreePBX.
#
#   ./apply.sh apply [--with-pjsip] [--drop-legacy] [--dry-run]
#   ./apply.sh verify
#   ./apply.sh rollback <каталог-бэкапа>
#
# apply   — ставит dialplan, создаёт транки и маршруты, перезагружает конфиг.
#          Сначала делает бэкап и печатает его путь.
# verify  — проверяет, что на сервере есть то, что должно быть.
# rollback— возвращает конфиги и строки базы из бэкапа.
#
# Флаги:
#   --with-pjsip  — вместе с dialplan поставить файлы эндпоинтов
#                   (pjsip.endpoint_custom.conf, pjsip.aor_custom.conf,
#                   pjsip.auth_custom.conf). Нужно, только если SIP-аккаунты
#                   телефонов не созданы в GUI. Если они уже есть — не ставьте.
#   --drop-legacy — удалить одиночную рабочую схему (транк 248 и маршрут 351).
#                   Она описывает тот же телефон 1001, что и сгенерированный
#                   набор, поэтому два маршрута с одинаковым match_cid будут
#                   конкурировать за исходящие агента. После удаления маршрут
#                   телефона 1001 берётся из сгенерированного набора (9001).
#   --dry-run     — показать, что будет сделано, и ничего не менять.
#
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
GEN="$HERE/generated"
ASTERISK_DIR=/etc/asterisk
BACKUP_ROOT=/root/gsm-gateway-backup
ID_BASE=9000

DIALPLAN="$ASTERISK_DIR/extensions_custom.conf"
PJSIP_FILES=(
  "$ASTERISK_DIR/pjsip.endpoint_custom.conf"
  "$ASTERISK_DIR/pjsip.aor_custom.conf"
  "$ASTERISK_DIR/pjsip.auth_custom.conf"
)

say() { printf '%s\n' "$*"; }
die() { printf 'ОШИБКА: %s\n' "$*" >&2; exit 1; }

preflight() {
  [ "$(id -u)" = "0" ] || die "нужен root"
  [ -d "$ASTERISK_DIR" ] || die "это не сервер FreePBX: нет $ASTERISK_DIR"
  command -v mysql >/dev/null || die "нет mysql"
  command -v fwconsole >/dev/null || die "нет fwconsole"
  [ -f "$GEN/extensions_custom.conf" ] || die "нет $GEN/extensions_custom.conf — сначала python3 gen.py"
  [ -f "$GEN/trunks-routes.sql" ] || die "нет $GEN/trunks-routes.sql — сначала python3 gen.py"
}

db() { mysql -B asterisk "$@"; }

backup() {
  local dir="$BACKUP_ROOT/$(date +%Y%m%d-%H%M%S)"
  mkdir -p "$dir"
  say "Бэкап: $dir"
  for f in "$DIALPLAN" "${PJSIP_FILES[@]}"; do
    [ -f "$f" ] && cp -a "$f" "$dir/$(basename "$f")" || true
  done
  mysqldump asterisk trunks outbound_routes outbound_route_patterns \
    outbound_route_trunks trunk_dialpatterns > "$dir/db-tables.sql"
  echo "$dir" > "$dir/PATH"
  say "  конфиги и таблицы базы скопированы"
}

restore_db() {
  local dir="$1"
  [ -f "$dir/db-tables.sql" ] || die "в бэкапе нет db-tables.sql"
  say "Восстанавливаю таблицы базы из $dir/db-tables.sql"
  mysql asterisk < "$dir/db-tables.sql"
}

do_apply() {
  local with_pjsip=0 drop_legacy=0 dry=0
  while [ $# -gt 0 ]; do
    case "$1" in
      --with-pjsip) with_pjsip=1 ;;
      --drop-legacy) drop_legacy=1 ;;
      --dry-run) dry=1 ;;
      *) die "неизвестный флаг: $1" ;;
    esac
    shift
  done

  if [ "$dry" = "1" ]; then
    say "--- ПРОБА: изменения не вносятся ---"
    say "dialplan: $GEN/extensions_custom.conf -> $DIALPLAN"
    if [ "$with_pjsip" = "1" ]; then
      say "pjsip:    3 файла -> $ASTERISK_DIR"
    else
      say "pjsip:    пропущен (--with-pjsip не указан)"
    fi
    if [ "$drop_legacy" = "1" ]; then
      say "база:     удалить легаси-транк 248 и легаси-маршрут 351"
    else
      say "база:     удаление легаси-объектов пропущено (--drop-legacy не указан)"
    fi
    say "база:     $(grep -c '^INSERT' "$GEN/trunks-routes.sql") вставок, id $((ID_BASE + 1)).."
    return 0
  fi

  backup

  say "Ставлю dialplan"
  install -m 0644 "$GEN/extensions_custom.conf" "$DIALPLAN"

  if [ "$with_pjsip" = "1" ]; then
    say "Ставлю файлы эндпоинтов"
    install -m 0644 "$GEN/pjsip.endpoint_custom.conf" "${PJSIP_FILES[0]}"
    install -m 0644 "$GEN/pjsip.aor_custom.conf" "${PJSIP_FILES[1]}"
    install -m 0644 "$GEN/pjsip.auth_custom.conf" "${PJSIP_FILES[2]}"
  else
    say "Файлы эндпоинтов пропущены (--with-pjsip не указан)"
  fi

  say "Создаю транки и маршруты"
  mysql asterisk < "$GEN/trunks-routes.sql"

  if [ "$drop_legacy" = "1" ]; then
    say "Удаляю одиночную рабочую схему (транк 248, маршрут 351)"
    mysql asterisk <<'SQL'
DELETE FROM outbound_route_trunks WHERE route_id = 351;
DELETE FROM outbound_route_patterns WHERE route_id = 351;
DELETE FROM outbound_routes WHERE route_id = 351;
DELETE FROM trunk_dialpatterns WHERE trunkid = 248;
DELETE FROM trunks WHERE trunkid = 248;
SQL
  else
    say "Легаси-объекты 248/351 оставлены (--drop-legacy не указан)."
    say "  Внимание: маршрут 351 и сгенерированный маршрут телефона 1001"
    say "  имеют одинаковый match_cid и будут конкурировать за исходящие."
  fi

  say "Перезагружаю конфигурацию FreePBX (звонки на секунду прервутся)"
  fwconsole reload >/dev/null

  say "Готово. Проверка:"
  do_verify
}

do_verify() {
  local ok=1
  say "--- проверка ---"

  local did_count
  did_count=$(grep -c '^exten => [0-9]' "$DIALPLAN" 2>/dev/null || echo 0)
  say "DID в dialplan: $did_count"
  [ "$did_count" -gt 0 ] || ok=0

  local trunks routes
  trunks=$(db -N -e "SELECT COUNT(*) FROM trunks WHERE trunkid BETWEEN $((ID_BASE + 1)) AND $((ID_BASE + 1000));")
  routes=$(db -N -e "SELECT COUNT(*) FROM outbound_routes WHERE route_id BETWEEN $((ID_BASE + 1)) AND $((ID_BASE + 1000));")
  say "транков в базе: $trunks, маршрутов: $routes"
  [ "$trunks" -gt 0 ] && [ "$routes" -gt 0 ] || ok=0

  local badcid
  badcid=$(db -N -e "SELECT COUNT(*) FROM outbound_routes r JOIN outbound_route_patterns p ON p.route_id = r.route_id WHERE r.route_id BETWEEN $((ID_BASE + 1)) AND $((ID_BASE + 1000)) AND (r.outcid = '' OR p.match_cid = '');")
  say "маршрутов с пустым Route CID или match_cid: ${badcid:-0}"
  [ "${badcid:-0}" -eq 0 ] || ok=0

  local legacy
  legacy=$(db -N -e "SELECT COUNT(*) FROM outbound_routes WHERE route_id = 351;")
  if [ "${legacy:-0}" -gt 0 ]; then
    say "ВНИМАНИЕ: легаси-маршрут 351 на месте. Он описывает тот же телефон 1001"
    say "  и имеет тот же match_cid, что сгенерированный маршрут 9001: исходящие"
    say "  агента будут выбираться между ними. Убрать: ./apply.sh apply --drop-legacy"
  fi

  local ctx
  ctx=$(asterisk -rx "dialplan show contexts" 2>/dev/null | grep -c 'outrt-' || true)
  say "контекстов маршрутов в работающем dialplan: $ctx"
  [ "${ctx:-0}" -gt 0 ] || ok=0

  local ep
  ep=$(asterisk -rx "pjsip show endpoints" 2>/dev/null | grep -cE '^  Endpoint: +1[0-9]{3}' || true)
  say "эндпоинтов телефонов зарегистрировано: $ep"
  say "(0 — телефоны ещё не зарегистрировались; это нормально до их включения)"

  if [ "$ok" = "1" ]; then
    say "Проверка пройдена."
  else
    say "Проверка НЕ пройдена — смотрите предыдущие строки."
  fi
}

do_rollback() {
  local dir="${1:-}"
  [ -n "$dir" ] || die "укажите каталог бэкапа: ./apply.sh rollback $BACKUP_ROOT/<дата>"
  [ -d "$dir" ] || die "нет каталога $dir"
  say "Откат из $dir"
  for f in extensions_custom.conf pjsip.endpoint_custom.conf pjsip.aor_custom.conf pjsip.auth_custom.conf; do
    if [ -f "$dir/$f" ]; then
      say "  возвращаю $f"
      cp -a "$dir/$f" "$ASTERISK_DIR/$f"
    fi
  done
  restore_db "$dir"
  say "Перезагружаю конфигурацию FreePBX"
  fwconsole reload >/dev/null
  say "Откат выполнен."
}

case "${1:-apply}" in
  apply) shift || true; preflight; do_apply "$@" ;;
  verify) preflight; do_verify ;;
  rollback) shift; preflight; do_rollback "$@" ;;
  *) die "команда: apply | verify | rollback" ;;
esac
