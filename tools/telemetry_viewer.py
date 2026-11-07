#!/usr/bin/env python3
"""
BitChord Real-Time Telemetry & Main Thread Inspector CLI.
Connects to BitChord's localhost:7456 telemetry endpoint or reads from disk.
"""

import sys
import os
import time
import json
import argparse
import urllib.request
import urllib.error

DEFAULT_URL = "http://127.0.0.1:7456"
FALLBACK_FILE = "/storage/emulated/0/Documents/BitChord/telemetry/app_resource_summary.json"

def fetch_json(endpoint, base_url=DEFAULT_URL):
    url = f"{base_url.rstrip('/')}{endpoint}"
    req = urllib.request.Request(url, headers={"User-Agent": "BitChord-Perf-CLI"})
    try:
        with urllib.request.urlopen(req, timeout=2) as resp:
            return json.loads(resp.read().decode("utf-8"))
    except Exception as e:
        return None

def read_fallback():
    if os.path.exists(FALLBACK_FILE):
        try:
            with open(FALLBACK_FILE, "r") as f:
                return json.load(f)
        except Exception:
            pass
    return None

def clear_screen():
    print("\033[H\033[J", end="")

def format_summary(data):
    lines = []
    lines.append("=" * 70)
    lines.append("  BITCHORD REAL-TIME TELEMETRY & MAIN THREAD INSPECTOR")
    lines.append("=" * 70)
    
    timestamp = data.get("timestamp", "N/A")
    lines.append(f" Snapshot Time: {timestamp}")
    
    system = data.get("system", {})
    if system:
        uptime = system.get("uptimeSeconds", 0)
        heap = system.get("heapAllocatedMb", 0)
        heap_max = system.get("heapMaxMb", 0)
        native = system.get("nativeHeapAllocatedMb", 0)
        threads = system.get("activeThreads", 0)
        lines.append(f" Uptime: {uptime}s | Heap: {heap}MB / {heap_max}MB | Native Heap: {native}MB | Threads: {threads}")
    
    lines.append("-" * 70)
    
    # Frame & Jank Stats
    frames = data.get("frameStats", {})
    if frames:
        total = frames.get("totalFrames", 0)
        jank = frames.get("jankFrames", 0)
        pct = frames.get("jankPercentage", "0.0%")
        avg = frames.get("avgFrameMs", 0)
        p90 = frames.get("p90FrameMs", 0)
        max_f = frames.get("maxFrameMs", 0)
        
        status = "🟢 SMOOTH"
        try:
            val = float(pct.replace("%", ""))
            if val > 15:
                status = "🔴 HIGH JANK"
            elif val > 5:
                status = "🟡 MODERATE JANK"
        except Exception:
            pass

        lines.append(f" UI RENDERING METRICS: {status}")
        lines.append(f"   Frames Rendered: {total} | Jank Frames: {jank} ({pct})")
        lines.append(f"   Frame Duration: Avg {avg}ms | P90 {p90}ms | Max {max_f}ms")
    
    lines.append("-" * 70)
    
    # Main Thread Stalls
    stalls = data.get("topMainThreadStalls", [])
    lines.append(f" MAIN THREAD STALL CLUSTERS (Frozen > 16ms): {len(stalls)} hotspots")
    if not stalls:
        lines.append("   (No main thread hangs detected yet)")
    else:
        for idx, s in enumerate(stalls[:5], 1):
            cause = s.get("rootCause", "unknown")
            count = s.get("count", 0)
            total_ms = s.get("totalDurationMs", 0)
            max_ms = s.get("maxDurationMs", 0)
            lines.append(f"   [{idx}] {cause}")
            lines.append(f"       Count: {count}x | Total: {total_ms}ms | Max: {max_ms}ms")
    
    lines.append("-" * 70)

    # Power & Battery
    power = data.get("power", {})
    if power:
        p_mw = power.get("powerMw", 0)
        c_ma = power.get("currentNowMa", 0)
        v_mv = power.get("voltageMv", 0)
        pct = power.get("batteryPct", 0)
        temp = power.get("temperatureC", 0)
        thermal = power.get("thermalStatus", "N/A")
        headroom = power.get("thermalHeadroom", 1.0)
        lines.append(" POWER & BATTERY CONSUMPTION:")
        lines.append(f"   Instantaneous Draw: {p_mw:,.1f} mW ({c_ma:,.1f} mA @ {v_mv} mV)")
        lines.append(f"   Battery: {pct}% | Temp: {temp}°C | Thermal Status: {thermal} (Headroom: {headroom})")
        lines.append("-" * 70)

    # CPU & Thread Execution
    cpu = data.get("cpu", {})
    if cpu:
        total_cpu = cpu.get("totalProcessCpuMs", 0)
        usage = cpu.get("processCpuUsagePct", "0.0%")
        threads = cpu.get("topThreads", [])
        lines.append(f" CPU USAGE & THREAD EXECUTION: {usage} (Total CPU Time: {total_cpu:,}ms)")
        if threads:
            lines.append("   Top Active Threads:")
            for t in threads[:5]:
                t_name = t.get("name", "thread")[:20]
                t_tid = t.get("tid", 0)
                t_user = t.get("userTimeMs", 0)
                t_sys = t.get("systemTimeMs", 0)
                t_tot = t.get("totalCpuMs", 0)
                lines.append(f"     TID {t_tid:<6} {t_name:<20} User: {t_user:>6}ms | Sys: {t_sys:>5}ms | Total: {t_tot:>6}ms")
        lines.append("-" * 70)

    # Top Activities
    activities = data.get("topActivitiesByTotalTime", [])
    if activities:
        lines.append(" TOP ACTIVITIES BY TOTAL EXECUTION TIME:")
        lines.append(f"   {'Name':<35} {'Cat':<12} {'Calls':<6} {'Total(ms)':<10} {'Avg(ms)':<8}")
        for act in activities[:8]:
            name = act.get("name", "")[:34]
            cat = act.get("category", "")[:11]
            cnt = act.get("count", 0)
            tot = act.get("totalMs", 0)
            avg = act.get("avgMs", 0)
            lines.append(f"   {name:<35} {cat:<12} {cnt:<6} {tot:<10.1f} {avg:<8.1f}")
    
    lines.append("=" * 70)
    return "\n".join(lines)

def main():
    parser = argparse.ArgumentParser(description="BitChord Performance Inspector")
    parser.add_argument("--url", default=DEFAULT_URL, help="Base HTTP endpoint")
    parser.add_argument("-l", "--live", action="store_true", help="Live continuous dashboard mode")
    parser.add_argument("--stalls", action="store_true", help="Dump detailed main thread stalls & stack traces")
    parser.add_argument("--clear", action="store_true", help="Clear metrics on device")
    parser.add_argument("--export", action="store_true", help="Export full JSON report")
    args = parser.parse_args()

    if args.clear:
        res = fetch_json("/clear", args.url)
        print("Metrics cleared:", res or "Failed to connect")
        return

    if args.stalls:
        stalls = fetch_json("/stalls", args.url)
        if not stalls:
            # Fallback to local disk file
            stalls_file = "/storage/emulated/0/Documents/BitChord/telemetry/telemetry_stalls.jsonl"
            if os.path.exists(stalls_file):
                stalls = []
                with open(stalls_file, "r") as f:
                    for line in f:
                        line = line.strip()
                        if line:
                            try:
                                stalls.append(json.loads(line))
                            except Exception:
                                pass
        if not stalls:
            print("Could not fetch stalls from endpoint or disk file.")
            return
        print(f"Recorded {len(stalls)} Main Thread Stalls (showing slowest 10):")
        sorted_stalls = sorted(stalls, key=lambda x: x.get("durationMs", 0), reverse=True)[:10]
        for idx, s in enumerate(sorted_stalls, 1):
            dur = s.get("durationMs", 0)
            cause = s.get("rootCause", "unknown")
            print(f"\n--- [Stall #{idx}] {dur:,.1f}ms ---")
            print("Root cause:", cause)
            print("Message:", s.get("message"))
            print("Sample Call Stack:")
            for frame in s.get("stackTrace", [])[:8]:
                print("   ", frame)
        return

    if args.export:
        data = fetch_json("/export", args.url) or read_fallback()
        if data:
            out_file = "bitchord_perf_export.json"
            with open(out_file, "w") as f:
                json.dump(data, f, indent=2)
            print(f"Exported telemetry to {out_file}")
        else:
            print("Failed to fetch export data.")
        return

    if args.live:
        print("Starting Live Telemetry Dashboard (Press Ctrl+C to stop)...")
        try:
            while True:
                data = fetch_json("/summary", args.url) or read_fallback()
                clear_screen()
                if data:
                    print(format_summary(data))
                else:
                    print(f"Waiting for BitChord Telemetry at {args.url} or {FALLBACK_FILE}...")
                time.sleep(1)
        except KeyboardInterrupt:
            print("\nLive dashboard stopped.")
            return

    # Single snapshot summary
    data = fetch_json("/summary", args.url) or read_fallback()
    if data:
        print(format_summary(data))
    else:
        print(f"Error: Could not connect to BitChord telemetry at {args.url}")
        print(f"And no fallback file found at {FALLBACK_FILE}")
        print("Ensure BitChord is running on the device with AppPerformanceBank installed.")

if __name__ == "__main__":
    main()
