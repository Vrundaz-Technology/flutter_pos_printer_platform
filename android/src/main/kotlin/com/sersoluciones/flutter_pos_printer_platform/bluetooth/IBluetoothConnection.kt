package com.sersoluciones.flutter_pos_printer_platform.bluetooth
import android.os.Handler
import io.flutter.plugin.common.MethodChannel.Result

interface IBluetoothConnection {
    fun connect(address: String, result: Result)
    fun stop()

    /// Write [out] to the printer, returning whether ALL of it was accepted.
    ///
    /// The return value used to be nothing at all, and callers reported success
    /// purely because a socket was open. On BLE that silently truncated every
    /// receipt at one ATT MTU — the printer produced a header and stopped,
    /// while the app logged a successful print.
    ///
    /// MAY BLOCK: the BLE implementation waits for each chunk to be
    /// acknowledged. Call it off the main thread.
    fun write(out: ByteArray?): Boolean
    var state: Int

    /// Redirect this connection's events to [handler].
    ///
    /// The connection object outlives the engine that created it — it is held
    /// in a companion-object field and only replaced on an explicit
    /// disconnect. Capturing the handler once at construction meant whichever
    /// engine happened to connect first owned every later state change, read
    /// and write failure for the life of the link, even after that engine had
    /// detached. A background print isolate could therefore take ownership of
    /// the events the live UI needs.
    fun setHandler(handler: Handler)
}