"""Native Windows WLAN API access, scoped to the selected wireless adapter.

Uses wlanapi.dll rather than netsh parsing so multiple adapters can be selected
independently.  All WLAN buffers are freed and handles are closed on exit.
Requires Windows 10/11 and WLAN AutoConfig; no administrator rights required
on ordinary systems (Wi-Fi/location policy may restrict access).
"""
from __future__ import annotations

import ctypes as C
import sys
import uuid
from dataclasses import dataclass


class GUID(C.Structure):
    _fields_ = [("Data1", C.c_uint32), ("Data2", C.c_uint16),
                ("Data3", C.c_uint16), ("Data4", C.c_ubyte * 8)]


class DOT11_SSID(C.Structure):
    _fields_ = [("length", C.c_uint32), ("data", C.c_ubyte * 32)]


class WLAN_INTERFACE_INFO(C.Structure):
    _fields_ = [("guid", GUID), ("description", C.c_wchar * 256),
                ("state", C.c_uint32)]


class WLAN_RATE_SET(C.Structure):
    _fields_ = [("length", C.c_uint32), ("rates", C.c_uint16 * 126)]


class WLAN_BSS_ENTRY(C.Structure):
    _fields_ = [
        ("ssid", DOT11_SSID),
        ("phy_id", C.c_uint32),
        ("bssid", C.c_ubyte * 6),
        ("bss_type", C.c_uint32),
        ("phy_type", C.c_uint32),
        ("rssi", C.c_int32),
        ("quality", C.c_uint32),
        ("in_domain", C.c_uint8),
        ("beacon", C.c_uint16),
        ("timestamp", C.c_uint64),
        ("host_timestamp", C.c_uint64),
        ("capabilities", C.c_uint16),
        ("center_frequency_khz", C.c_uint32),
        ("rates", WLAN_RATE_SET),
        ("ie_offset", C.c_uint32),
        ("ie_size", C.c_uint32),
    ]


class WLAN_ASSOCIATION_ATTRIBUTES(C.Structure):
    _fields_ = [
        ("ssid", DOT11_SSID),
        ("bss_type", C.c_uint32),
        ("bssid", C.c_ubyte * 6),
        ("phy_type", C.c_uint32),
        ("phy_index", C.c_uint32),
        ("quality", C.c_uint32),
        ("rx_rate", C.c_uint32),
        ("tx_rate", C.c_uint32),
    ]


class WLAN_SECURITY_ATTRIBUTES(C.Structure):
    _fields_ = [("security_enabled", C.c_int32), ("onex_enabled", C.c_int32),
                ("auth", C.c_uint32), ("cipher", C.c_uint32)]


class WLAN_CONNECTION_ATTRIBUTES(C.Structure):
    _fields_ = [
        ("state", C.c_uint32), ("mode", C.c_uint32),
        ("profile", C.c_wchar * 256),
        ("association", WLAN_ASSOCIATION_ATTRIBUTES),
        ("security", WLAN_SECURITY_ATTRIBUTES),
    ]


def guid_bytes(g: GUID) -> bytes:
    return C.string_at(C.byref(g), 16)


def format_mac(raw) -> str:
    return ":".join(f"{int(v):02X}" for v in raw)


def decode_ssid(s: DOT11_SSID) -> str:
    n = max(0, min(32, int(s.length)))
    return bytes(s.data[:n]).decode("utf-8", errors="replace")


def channel_for_mhz(mhz: int) -> int:
    if mhz == 2484:
        return 14
    if 2412 <= mhz <= 2472:
        return (mhz - 2407) // 5
    if mhz == 5935:
        return 2
    if 5955 <= mhz <= 7115:
        return (mhz - 5950) // 5
    if 5000 <= mhz < 5925:
        return (mhz - 5000) // 5
    return 0


@dataclass(frozen=True)
class Adapter:
    guid_hex: str
    description: str
    state: int

    @property
    def display(self) -> str:
        state = "Connected" if self.state == 1 else "Disconnected" if self.state == 4 else "State " + str(self.state)
        return f"{self.description}  [{state}]  ({self.guid_hex[:8]})"


@dataclass(frozen=True)
class BSS:
    ssid: str
    bssid: str
    rssi: int
    frequency: int
    channel: int
    quality: int
    host_timestamp: int = 0


class WLANError(RuntimeError):
    pass


class NativeWiFi:
    """One wlanapi.dll session, safe to instantiate in a worker thread."""
    def __init__(self) -> None:
        if sys.platform != "win32":
            raise WLANError("Native Wi-Fi survey requires Windows 10 or Windows 11.")
        self.lib = C.WinDLL("wlanapi.dll")
        ptr = C.c_void_p
        self.lib.WlanOpenHandle.argtypes = [C.c_uint32, ptr, C.POINTER(C.c_uint32), C.POINTER(ptr)]
        self.lib.WlanOpenHandle.restype = C.c_uint32
        self.lib.WlanCloseHandle.argtypes = [ptr, ptr]
        self.lib.WlanCloseHandle.restype = C.c_uint32
        self.lib.WlanEnumInterfaces.argtypes = [ptr, ptr, C.POINTER(ptr)]
        self.lib.WlanEnumInterfaces.restype = C.c_uint32
        self.lib.WlanFreeMemory.argtypes = [ptr]
        self.lib.WlanFreeMemory.restype = None
        self.lib.WlanScan.argtypes = [ptr, C.POINTER(GUID), ptr, ptr, ptr]
        self.lib.WlanScan.restype = C.c_uint32
        self.lib.WlanGetNetworkBssList.argtypes = [ptr, C.POINTER(GUID), ptr, C.c_uint32,
                                                   C.c_int32, ptr, C.POINTER(ptr)]
        self.lib.WlanGetNetworkBssList.restype = C.c_uint32
        self.lib.WlanQueryInterface.argtypes = [ptr, C.POINTER(GUID), C.c_uint32, ptr,
                                                C.POINTER(C.c_uint32), C.POINTER(ptr), ptr]
        self.lib.WlanQueryInterface.restype = C.c_uint32
        self.handle = ptr()
        negotiated = C.c_uint32()
        self._check(self.lib.WlanOpenHandle(2, None, C.byref(negotiated), C.byref(self.handle)), "WlanOpenHandle")

    def __enter__(self) -> "NativeWiFi":
        return self

    def __exit__(self, *_):
        if self.handle:
            self.lib.WlanCloseHandle(self.handle, None)
            self.handle = C.c_void_p()

    @staticmethod
    def _check(code: int, action: str):
        if code:
            hint = (" Check Windows Settings > Privacy & security > Location, "
                    "desktop app location permissions, Wi-Fi ON and WLAN AutoConfig.")
            raise WLANError(f"{action}: Windows error {code}." + hint)

    @staticmethod
    def _guid(key: str) -> GUID:
        return GUID.from_buffer_copy(bytes.fromhex(key))

    def adapters(self) -> list[Adapter]:
        out = C.c_void_p()
        self._check(self.lib.WlanEnumInterfaces(self.handle, None, C.byref(out)), "WlanEnumInterfaces")
        try:
            if not out.value:
                return []
            count = C.c_uint32.from_address(out.value).value
            if count > 256:
                raise WLANError("Unexpected Wi-Fi adapter count from Windows")
            base = out.value + 8
            size = C.sizeof(WLAN_INTERFACE_INFO)
            found = []
            for i in range(count):
                item = WLAN_INTERFACE_INFO.from_address(base + i * size)
                found.append(Adapter(guid_bytes(item.guid).hex(), item.description, item.state))
            return found
        finally:
            if out.value:
                self.lib.WlanFreeMemory(out)

    def request_scan(self, guid_hex: str) -> None:
        guid = self._guid(guid_hex)
        self._check(self.lib.WlanScan(self.handle, C.byref(guid), None, None, None), "WlanScan")

    def bss(self, guid_hex: str) -> list[BSS]:
        guid = self._guid(guid_hex)
        result = C.c_void_p()
        self._check(self.lib.WlanGetNetworkBssList(
            self.handle, C.byref(guid), None, 3, 0, None, C.byref(result)), "WlanGetNetworkBssList")
        try:
            if not result.value:
                return []
            total = C.c_uint32.from_address(result.value).value
            count = C.c_uint32.from_address(result.value + 4).value
            entry_size = C.sizeof(WLAN_BSS_ENTRY)
            if count > 8192 or total < 8 or count * entry_size + 8 > total:
                raise WLANError("Invalid BSS buffer returned by Wi-Fi driver")
            rows: dict[str, BSS] = {}
            for i in range(count):
                b = WLAN_BSS_ENTRY.from_address(result.value + 8 + i * entry_size)
                mac = format_mac(b.bssid)
                freq = int(b.center_frequency_khz // 1000)
                row = BSS(decode_ssid(b.ssid), mac, int(b.rssi), freq,
                          channel_for_mhz(freq), int(b.quality), int(b.host_timestamp))
                if mac not in rows or row.rssi > rows[mac].rssi:
                    rows[mac] = row
            return sorted(rows.values(), key=lambda a: a.rssi, reverse=True)
        finally:
            if result.value:
                self.lib.WlanFreeMemory(result)

    def _query(self, guid_hex: str, code: int) -> tuple[int, bytes]:
        guid = self._guid(guid_hex)
        size = C.c_uint32()
        result = C.c_void_p()
        error = self.lib.WlanQueryInterface(self.handle, C.byref(guid), code,
                                             None, C.byref(size), C.byref(result), None)
        if error:
            return int(error), b""
        try:
            return 0, C.string_at(result.value, size.value) if result.value else b""
        finally:
            if result.value:
                self.lib.WlanFreeMemory(result)

    def connection(self, guid_hex: str) -> dict:
        err, raw = self._query(guid_hex, 7)  # wlan_intf_opcode_current_connection
        if err or len(raw) < C.sizeof(WLAN_CONNECTION_ATTRIBUTES):
            return {}
        conn = WLAN_CONNECTION_ATTRIBUTES.from_buffer_copy(raw)
        if conn.state != 1:
            return {}
        a = conn.association
        mac = format_mac(a.bssid)
        rss_err, rss_raw = self._query(guid_hex, 0x10000102)  # wlan_intf_opcode_rssi
        rss = int(C.c_int32.from_buffer_copy(rss_raw[:4]).value) if not rss_err and len(rss_raw) >= 4 else None
        return dict(ssid=decode_ssid(a.ssid), bssid=mac, quality=int(a.quality),
                    rssi=rss, tx_mbps=round(a.tx_rate / 1000, 1), rx_mbps=round(a.rx_rate / 1000, 1))


if __name__ == "__main__":
    # A Windows/driver smoke check for the workflow; no radio access without user action.
    print("WLAN BSS struct:", C.sizeof(WLAN_BSS_ENTRY))
    assert C.sizeof(WLAN_BSS_ENTRY) == 360
    assert WLAN_BSS_ENTRY.host_timestamp.offset == 80
