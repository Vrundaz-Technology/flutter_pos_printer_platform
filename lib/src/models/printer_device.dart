import 'dart:io';

class PrinterDevice {
  String name;
  String operatingSystem = Platform.operatingSystem;
  String? vendorId;
  String? productId;
  String? address;

  /// Which Bluetooth transport this device was discovered over.
  ///
  /// `null` for non-Bluetooth devices and for platforms that do not report it.
  /// Callers merging a Classic + BLE sweep should prefer `isBle == false`:
  /// Classic streams a whole receipt over RFCOMM, while BLE has to chunk it
  /// into ATT-sized writes.
  final bool? isBle;

  PrinterDevice({
    required this.name,
    this.address,
    this.vendorId,
    this.productId,
    this.isBle,
  });
}
