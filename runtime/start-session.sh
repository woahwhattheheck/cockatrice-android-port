#!/bin/sh
# Runs inside the bundled Linux filesystem, not in Termux.
set -eu
export PATH=/usr/bin:/bin
export HOME=/root
export LANG=C.UTF-8
export DISPLAY=:83
export XAUTHORITY=/run/xauth
export XDG_RUNTIME_DIR=/run/user
export QT_QPA_PLATFORM=xcb
export QT_X11_NO_MITSHM=1
export QT_XCB_GL_INTEGRATION=none
export QT_AUTO_SCREEN_SCALE_FACTOR=0
export QT_SCALE_FACTOR=1
export QT_FONT_DPI=96
export FONTCONFIG_PATH=/etc/fonts

mkdir -p /tmp/.X11-unix /run/user /root/.cache /root/.fluxbox
chmod 700 /run/user
rm -f /tmp/.X83-lock /tmp/.X11-unix/X83 /run/session.ready
children=""
cleanup() {
    trap - EXIT INT TERM
    if [ -n "$children" ]; then
        kill $children 2>/dev/null || true
        wait 2>/dev/null || true
    fi
}
trap cleanup EXIT
trap 'exit 0' INT TERM

Xvfb "$DISPLAY" -screen 0 "${COCKATRICE_GEOMETRY:-1280x720}x24" \
    -dpi 96 -nolisten tcp -noreset -auth "$XAUTHORITY" -extension MIT-SHM -extension GLX &
xpid=$!
children="$xpid"
attempt=0
until xdpyinfo -display "$DISPLAY" >/dev/null 2>&1; do
    kill -0 "$xpid" 2>/dev/null || { echo 'Xvfb exited before becoming ready'; exit 1; }
    attempt=$((attempt + 1))
    [ "$attempt" -lt 150 ] || { echo 'Xvfb readiness timed out'; exit 1; }
    sleep 0.2
done

fluxbox -display "$DISPLAY" -rc /opt/cockatrice/fluxbox/init &
children="$children $!"
x11vnc -display "$DISPLAY" -localhost -listen 127.0.0.1 -noipv6 \
    -rfbport "$COCKATRICE_VNC_PORT" -rfbportv6 -1 \
    -rfbauth /run/vnc.pass -forever -shared -repeat -noshm -noxdamage \
    -wait 15 -defer 10 &
children="$children $!"

cockatrice &
game=$!
children="$children $game"
supervisor=$$
(
    while [ ! -f /run/stop ]; do sleep 0.5; done
    kill -TERM "$supervisor"
) &
children="$children $!"
attempt=0
until xwininfo -root -tree 2>/dev/null | grep -q '"Cockatrice -'; do
    kill -0 "$game" 2>/dev/null || { wait "$game"; exit 1; }
    attempt=$((attempt + 1))
    [ "$attempt" -lt 300 ] || { echo 'Cockatrice window readiness timed out'; exit 1; }
    sleep 0.2
done
echo 'COCKATRICE_READY'
: > /run/session.ready
wait "$game"
