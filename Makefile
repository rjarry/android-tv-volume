# SPDX-License-Identifier: MIT
# Copyright (c) 2026 Robin Jarry

ANDROID_HOME ?= $(HOME)/.local/share/android/sdk
JAVA_HOME ?= /usr/lib/jvm/temurin-21-jdk
ADB_SERIAL ?=

export ANDROID_HOME
export ANDROID_SDK_ROOT := $(ANDROID_HOME)
export JAVA_HOME
export PATH := $(JAVA_HOME)/bin:$(PATH)

ADB = adb $(if $(ADB_SERIAL),-s $(ADB_SERIAL))
APK = app/build/outputs/apk/debug/app-debug.apk
DAEMON = daemon/finevold
DAEMON_LIB = app/src/main/jniLibs/armeabi-v7a/libfinevold.so
DAEMON_PATH = /data/local/tmp/finevold
DAEMON_LOG = /data/local/tmp/finevold.log

.PHONY: all build install run clean daemon daemon-install daemon-start daemon-stop daemon-log

all: build

build: $(DAEMON_LIB)
	./gradlew --no-daemon assembleDebug

$(DAEMON_LIB): $(DAEMON)
	mkdir -p $(dir $@)
	cp $< $@

install: build
	$(ADB) install -r $(APK)

run: install
	$(ADB) shell am start -n cc.jarry.finevolume/.MainActivity

$(DAEMON): $(wildcard daemon/*.go) daemon/go.mod
	cd daemon && CGO_ENABLED=0 GOOS=linux GOARCH=arm GOARM=7 \
		go build -ldflags=-s -o finevold .

daemon: $(DAEMON)

daemon-install: daemon
	$(ADB) push $(DAEMON) $(DAEMON_PATH)
	$(ADB) shell chmod 755 $(DAEMON_PATH)

# The daemon must be restarted after every reboot. It is detached from the adb
# session so that it keeps running once this command returns.
daemon-start: daemon-install daemon-stop
	$(ADB) shell "setsid nohup $(DAEMON_PATH) >$(DAEMON_LOG) 2>&1 &"
	sleep 1
	$(ADB) shell "pgrep -l finevold"

daemon-stop:
	-$(ADB) shell "pkill -TERM finevold"

daemon-log:
	$(ADB) shell "cat $(DAEMON_LOG)"

clean:
	./gradlew --no-daemon clean
	rm -f $(DAEMON) $(DAEMON_LIB)
