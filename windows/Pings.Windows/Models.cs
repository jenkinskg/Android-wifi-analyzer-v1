using System.Net;

namespace Pings.Windows;

public sealed class SubnetProfile
{
    public string Name { get; set; } = "Subnet";
    public string Cidr { get; set; } = "192.168.1.0/24";
    public override string ToString() => $"{Name} — {Cidr}";
}

public sealed class HostResult
{
    public string Ip { get; set; } = "";
    public bool Reachable { get; set; }
    public long? RttMs { get; set; }
    public string Hostname { get; set; } = "";
    public string Status => Reachable ? "Pingable" : "No ping response";
}

public sealed class ScanRecord
{
    public Guid Id { get; set; } = Guid.NewGuid();
    public DateTime Timestamp { get; set; } = DateTime.Now;
    public string Cidr { get; set; } = "";
    public List<HostResult> Results { get; set; } = new();
    public override string ToString() => $"{Timestamp:yyyy-MM-dd HH:mm:ss} — {Cidr}";
}

public sealed class TraceHop
{
    public int Hop { get; set; }
    public string Address { get; set; } = "*";
    public long? RttMs { get; set; }
    public string Status => RttMs.HasValue ? $"{RttMs} ms" : "Timeout";
}

public static class NetworkUtil
{
    public static string? DetectCurrentSubnet()
    {
        var candidates = System.Net.NetworkInformation.NetworkInterface.GetAllNetworkInterfaces()
            .Where(n => n.OperationalStatus == System.Net.NetworkInformation.OperationalStatus.Up)
            .Where(n => n.NetworkInterfaceType != System.Net.NetworkInformation.NetworkInterfaceType.Loopback)
            .Select(n => new
            {
                Nic = n,
                Props = n.GetIPProperties(),
                HasGateway = n.GetIPProperties().GatewayAddresses.Any(g => g.Address.AddressFamily == System.Net.Sockets.AddressFamily.InterNetwork && !g.Address.Equals(IPAddress.Any))
            })
            .OrderByDescending(x => x.HasGateway);

        foreach (var item in candidates)
        {
            foreach (var u in item.Props.UnicastAddresses)
            {
                if (u.Address.AddressFamily != System.Net.Sockets.AddressFamily.InterNetwork) continue;
                if (IPAddress.IsLoopback(u.Address)) continue;
                var bytes = u.Address.GetAddressBytes();
                if (!IsPrivate(bytes)) continue;

                int prefix = u.PrefixLength;
                uint ip = ToUInt32(bytes);
                uint mask = prefix == 0 ? 0u : uint.MaxValue << (32 - prefix);
                uint network = ip & mask;
                return $"{FromUInt32(network)}/{prefix}";
            }
        }
        return null;
    }

    public static (uint Network, int Prefix, uint First, uint Last) ParseCidr(string cidr)
    {
        var p = cidr.Trim().Split('/');
        if (p.Length != 2 || !IPAddress.TryParse(p[0], out var ip) || ip.AddressFamily != System.Net.Sockets.AddressFamily.InterNetwork)
            throw new ArgumentException("Enter a valid IPv4 CIDR, for example 192.168.1.0/24.");
        if (!int.TryParse(p[1], out int prefix) || prefix < 16 || prefix > 32)
            throw new ArgumentException("For safety, Pings supports IPv4 /16 through /32.");

        uint raw = ToUInt32(ip.GetAddressBytes());
        uint mask = prefix == 0 ? 0u : uint.MaxValue << (32 - prefix);
        uint network = raw & mask;
        uint broadcast = network | ~mask;
        uint first = prefix <= 30 ? network + 1 : network;
        uint last = prefix <= 30 ? broadcast - 1 : broadcast;
        return (network, prefix, first, last);
    }

    public static string Normalize(string cidr)
    {
        var x = ParseCidr(cidr);
        return $"{FromUInt32(x.Network)}/{x.Prefix}";
    }

    public static IEnumerable<string> Hosts(string cidr)
    {
        var x = ParseCidr(cidr);
        for (uint i = x.First; i <= x.Last; i++)
        {
            yield return FromUInt32(i);
            if (i == uint.MaxValue) break;
        }
    }

    private static bool IsPrivate(byte[] b) =>
        b[0] == 10 ||
        (b[0] == 172 && b[1] >= 16 && b[1] <= 31) ||
        (b[0] == 192 && b[1] == 168);

    private static uint ToUInt32(byte[] b) =>
        ((uint)b[0] << 24) | ((uint)b[1] << 16) | ((uint)b[2] << 8) | b[3];

    private static string FromUInt32(uint v) =>
        $"{(v >> 24) & 255}.{(v >> 16) & 255}.{(v >> 8) & 255}.{v & 255}";
}
