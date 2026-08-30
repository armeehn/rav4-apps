# Magisk module install customization (sourced by install_module).
# SKIPUNZIP=0 lets Magisk unpack the whole module tree (system/ + module.prop).
SKIPUNZIP=0

ui_print "- Installing RAV4 App Rewrites overlay"

# Give every overlaid APK the normal system-app perms (0644, root:root) so the
# package manager scans them like the OEM files they replace.
set_perm_recursive "$MODPATH/system" 0 0 0755 0644
