# DO NOT EDIT THIS FILE - See: https://eclipse.dev/jetty/documentation/

[description]
Configures Jetty to use the "CdiDecoratingListener" as the default CDI mode.
This mode that allows a webapp to register it's own CDI decorator.

[tag]
cdi

[provides]
cdi-mode

[depend]
cdi

[ini]
jetty.cdi.mode=CdiDecoratingListener
