#!/system/bin/sh
# READ-ONLY diagnostic — run ON the head unit (adb shell) to determine whether any
# USB port can act as a peripheral/gadget (required for adb-over-USB from a laptop).
# Changes nothing.
echo "== USB controller / config =="
getprop sys.usb.controller; getprop sys.usb.state
getprop sys.usb.config; getprop persist.sys.usb.config
echo; echo "== UDC (device-mode controllers => gadget-capable) =="
ls /sys/class/udc 2>/dev/null || echo "(none — no device-mode controller exposed)"
for s in /sys/class/udc/*/state; do [ -e "$s" ] && echo "$s = $(cat "$s" 2>/dev/null)"; done
echo; echo "== configfs gadget =="
ls /config/usb_gadget 2>/dev/null && {
  echo "UDC bound: $(cat /config/usb_gadget/g1/UDC 2>/dev/null)"
  echo "functions available:"; ls /config/usb_gadget/g1/functions 2>/dev/null
}
echo; echo "== dual-role mode (host vs device vs otg) =="
for f in /sys/class/dual_role_usb/*/mode /sys/devices/platform/soc/*/mode /sys/kernel/debug/usb/*/mode; do
  [ -e "$f" ] && echo "$f = $(cat "$f" 2>/dev/null)"; done
echo; echo "== adb state =="
echo "adb_enabled: $(settings get global adb_enabled 2>/dev/null)"
echo "adbd svc: $(getprop init.svc.adbd)"
echo; echo "== read: UDC + an adb function => USB-gadget adb possible; host-locked/no UDC => use TCP =="
