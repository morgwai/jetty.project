# DO NOT EDIT THIS FILE - See: https://eclipse.dev/jetty/documentation/

[description]
Creates and updates pid file used by jetty.sh

[tags]
start

[before]
server
threadpool
jvm

[xml]
etc/jetty-pid.xml

[ini-template]
## PID Config
jetty.pid=${jetty.base}/jetty.pid


