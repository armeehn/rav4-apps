#!/usr/bin/env bash
# Untouchable-app denylist — the OEM packages we must NEVER replace or overlay.
#
# Replacing any of these can brick the unit or kill a safety function:
#   - eventcenter is the car gateway: it owns the reverse camera (BackcarEvent),
#     the steering-wheel keys (SWC), the MCU serial link (ttyHS1) and the
#     SysVarProvider (455 live car settings). Safety-critical.
#   - customerui is the stock launcher; the gateway inflates its views BY NAME
#     (reflected into from another process), so a swapped APK breaks that binding.
#   - canbus/canbus2 is the CANBOX link (vehicle bus). auxcamera drives the
#     camera decoder. radio/dsp own the tuner + amp/EQ path over HiWorld serial.
# Ground truth: device-reveng CAR_API.md, CUSTOMERUI_NOTES.md, FINDINGS.md.
#
# Source this file, then call:  is_protected <package>   (exit 0 = protected)

# Exact package names and glob patterns (bash `case`) that are off-limits.
PROTECTED_APPS=(
  'com.szchoiceway.eventcenter'   # car gateway: reverse cam, SWC, SysVarProvider, ttyHS1
  'com.szchoiceway.customerui'    # stock launcher, reflected-into by the gateway
  'com.szchoiceway.canbus2'       # CANBOX <-> MCU link
  'com.szchoiceway.canbus'        # CAN bus (family)
  'com.szchoiceway.auxcamera'     # camera decoder / signal detection
  'com.szchoiceway.radio'         # tuner
  'com.choiceway.dsp'             # audio DSP -> amp over HiWorld serial
)

# is_protected <pkg> — 0 if the package is on the denylist, 1 otherwise.
is_protected() {
  local pkg="$1" entry
  for entry in "${PROTECTED_APPS[@]}"; do
    case "$pkg" in
      $entry) return 0 ;;
    esac
  done
  return 1
}
