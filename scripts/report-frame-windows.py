#!/usr/bin/env python3
"""Report each window's FrameTimeline durations from saved Perfetto traces as CSV.

Usage: python3 scripts/report-frame-windows.py [--trace-processor PATH] TRACE...

Requires Perfetto trace_processor_shell with the `query TRACE SQL` command.
Reads the whole trace for --process (default: app.songyan.ime.benchmark).
Durations include rendering/presentation waits; they are not FrameTimingMetric
CPU durations. Each trace remains a separate sample. Incomplete frames are counted
but excluded from percentiles; boundary_frames counts completed intervals ending
at or beyond the trace boundary. Percentiles use linear interpolation between sorted values.
"""

import argparse
import csv
import io
import re
import subprocess
import sys
from collections import defaultdict
from pathlib import Path


def percentile(values, fraction):
    position = (len(values) - 1) * fraction
    lower = int(position)
    upper = min(lower + 1, len(values) - 1)
    return values[lower] + (values[upper] - values[lower]) * (position - lower)


def window_rows(trace, processor, process):
    process_literal = process.replace("'", "''")
    query = f"""
        SELECT COALESCE(f.layer_name, '[unnamed]') AS layer_name, f.dur,
               f.ts + f.dur >= (SELECT end_ts FROM trace_bounds) AS at_trace_end
        FROM actual_frame_timeline_slice f JOIN process p USING (upid)
        WHERE p.name = '{process_literal}'
    """
    result = subprocess.run(
        [processor, "query", str(trace), query],
        check=True, capture_output=True, text=True,
    )
    reader = csv.DictReader(io.StringIO(result.stdout))
    if reader.fieldnames != ["layer_name", "dur", "at_trace_end"]:
        raise ValueError(f"{trace}: unexpected trace_processor output")
    windows = defaultdict(list)
    for row in reader:
        # Surface IDs change between iterations; the window name is the comparison key.
        window = re.sub(r"#\d+$", "", row["layer_name"])
        windows[window].append((int(row["dur"]), int(row["at_trace_end"])))
    if not windows:
        raise ValueError(f"{trace}: no FrameTimeline data for {process}")
    for window, frames in sorted(windows.items()):
        durations = sorted(duration / 1e6 for duration, _ in frames if duration >= 0)
        summary = ("", "", "")
        if durations:
            summary = tuple(f"{value:.3f}" for value in (
                percentile(durations, 0.5), percentile(durations, 0.95), durations[-1],
            ))
        yield [
            str(trace), window, len(durations), len(frames) - len(durations),
            sum(boundary for duration, boundary in frames if duration >= 0),
            *summary,
        ]


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--trace-processor", default="trace_processor_shell", help="Perfetto executable path")
    parser.add_argument("--process", default="app.songyan.ime.benchmark", help="Exact captured process name")
    parser.add_argument("traces", nargs="+", type=Path)
    args = parser.parse_args()
    writer = csv.writer(sys.stdout)
    writer.writerow([
        "trace", "window", "complete_frames", "incomplete_frames", "boundary_frames",
        "timeline_p50_ms", "timeline_p95_ms", "timeline_max_ms",
    ])
    try:
        for trace in args.traces:
            writer.writerows(window_rows(trace, args.trace_processor, args.process))
    except subprocess.CalledProcessError as error:
        print(error.stderr.strip() or str(error), file=sys.stderr)
        return 1
    except (OSError, ValueError) as error:
        print(error, file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
