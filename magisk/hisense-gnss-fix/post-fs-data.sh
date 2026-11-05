#!/system/bin/sh
# The GNSS HAL keeps a runtime copy of supl.xml; drop it so it is regenerated from the patched file.
rm -f /data/vendor/gnss/supl/supl.xml
