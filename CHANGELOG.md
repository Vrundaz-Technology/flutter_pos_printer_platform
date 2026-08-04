## 1.2.5

* Register the method and event channels on engine attach rather than activity
  attach, so the plugin works in a Flutter engine that has no Activity — a
  Firebase background message isolate, for instance. Previously such an isolate
  raised `MissingPluginException: No implementation found for method listen on
  channel com.sersoluciones.flutter_pos_printer_platform/bt_state`, and because
  the same method channel backs `onStartConnection` and `printBytes`, printing
  from the background was impossible.
* Do not dereference a null Activity when Bluetooth permissions are missing;
  report them as missing instead.
* Only latch the enable-Bluetooth prompt flag once the prompt is actually
  shown, so an engine with no Activity does not wedge every later connect.
* Make USB init idempotent — the service is a process-wide singleton and each
  attached engine called it, registering the broadcast receiver more than once.

## 1.2.4

* Relax rxdart version to allow library usage in FlutterFlow app builder

## 1.2.3

* Add namespace in build.gradle to be compatible with Gradle 8 (credit: https://github.com/tgarm)
* Fix some errors in Android library (credit: https://github.com/tgarm)
* Update rxdart minor version (credit: https://github.com/ivankasalo)
* Fix windows build (credit: https://github.com/sedess)

## 1.2.2

* Fix incorrect ActivityAware lifecycle hooks
* Fix issue in PendingIntent for Android 14+

## 1.2.1

* Attempt to fix initialized error

## 1.1.0

* Toast msgs english locale default

## 1.0.12

* Resolve minor bug [Android] 12

## 1.0.11

* Resolve minor bug dependecies

## 1.0.10

* Now android supports targetSdkVersion 31

## 1.0.9

* Resolve minor bug [Android] connection

## 1.0.8

* Resolve minor bug [Android] connection

## 1.0.6

* Resolve minor bug [Android] connection

## 1.0.6

* Resolve minor bug windows printer

## 1.0.5

* Get current status bt

## 1.0.4

* Solved Bug windows: USB

## 1.0.3

* Bug android notify events: Bluetooth 

## 1.0.2

* Bug android connection interface: USB 

## 1.0.1

* How to use it.

## 1.0.0

* Initial release.
