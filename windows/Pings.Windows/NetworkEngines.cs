using System.Diagnostics;
using System.Net;
using System.Net.NetworkInformation;
using System.Text.RegularExpressions;

namespace Pings.Windows;

public static class PingEngine
{
    public static async Task<List<HostResult>> SweepAsync(string cidr, int timeoutMs = 900, int concurrency = 48, IProgress<int>? progress = null, CancellationToken ct = default)
    {
        var hosts = NetworkUtil.Hosts(cidr).ToList();
        var results = new HostResult[hosts.Count];
        using var gate = new SemaphoreSlim(concurrency);

        var tasks = hosts.Select(async (ip, index) =>
        {
            await gate.WaitAsync(ct);
            try
            {
                ct.ThrowIfCancellationRequested();
                var result = new HostResult { Ip = ip };
                try
                {
                    using var ping = new Ping();
                    var reply = await ping.SendPingAsync(ip, TimeSpan.FromMilliseconds(timeoutMs));
                    result.Reachable = reply.Status == IPStatus.Success;
                    result.RttMs = result.Reachable ? reply.RoundtripTime : null;
                }
                catch { result.Reachable = false; }

                if (result.Reachable)
                {
                    try
                    {
                        var entry = await Dns.GetHostEntryAsync(ip, ct);
                        result.Hostname = entry.HostName ?? "";
                    }
                    catch { }
                }
                results[index] = result;
                progress?.Report((int)Math.Round((index + 1) * 100.0 / hosts.Count));
            }
            finally { gate.Release(); }
        });

        await Task.WhenAll(tasks);
        return results.Where(x => x != null).OrderBy(x => IPAddress.Parse(x.Ip).GetAddressBytes(), ByteArrayComparer.Instance).ToList()!;
    }

    private sealed class ByteArrayComparer : IComparer<byte[]>
    {
        public static readonly ByteArrayComparer Instance = new();
        public int Compare(byte[]? x, byte[]? y)
        {
            if (ReferenceEquals(x, y)) return 0;
            if (x is null) return -1; if (y is null) return 1;
            for (int i = 0; i < Math.Min(x.Length, y.Length); i++)
            {
                int c = x[i].CompareTo(y[i]); if (c != 0) return c;
            }
            return x.Length.CompareTo(y.Length);
        }
    }
}

public static class TraceEngine
{
    private static readonly Regex HopLine = new(@"^s*(d+)s+(.*)$", RegexOptions.Compiled);
    private static readonly Regex Ms = new(@"<?(d+)s*ms", RegexOptions.Compiled | RegexOptions.IgnoreCase);
    private static readonly Regex Ip = new(@"(d{1,3}(?:.d{1,3}){3})", RegexOptions.Compiled);

    public static async Task<List<TraceHop>> TraceAsync(string target, CancellationToken ct = default)
    {
        var psi = new ProcessStartInfo
        {
            FileName = "tracert.exe",
            Arguments = $"-d -h 30 -w 900 \"{target}\"",
            RedirectStandardOutput = true,
            RedirectStandardError = true,
            UseShellExecute = false,
            CreateNoWindow = true
        };
        using var p = Process.Start(psi) ?? throw new InvalidOperationException("Could not start tracert.exe.");
        var outputTask = p.StandardOutput.ReadToEndAsync(ct);
        await p.WaitForExitAsync(ct);
        var text = await outputTask;

        var list = new List<TraceHop>();
        foreach (var line in text.Split('\n'))
        {
            var hm = HopLine.Match(line);
            if (!hm.Success) continue;
            if (!int.TryParse(hm.Groups[1].Value, out int hop)) continue;
            var rest = hm.Groups[2].Value;
            var ipm = Ip.Match(rest);
            var times = Ms.Matches(rest).Select(m => long.TryParse(m.Groups[1].Value, out var v) ? (long?)v : null).Where(v => v.HasValue).Select(v => v!.Value).ToList();
            list.Add(new TraceHop
            {
                Hop = hop,
                Address = ipm.Success ? ipm.Value : "*",
                RttMs = times.Count > 0 ? (long?)Math.Round(times.Average()) : null
            });
        }
        return list;
    }
}
