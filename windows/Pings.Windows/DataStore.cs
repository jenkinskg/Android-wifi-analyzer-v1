using System.Text.Json;

namespace Pings.Windows;

public sealed class AppData
{
    public List<SubnetProfile> Profiles { get; set; } = new();
    public List<ScanRecord> Scans { get; set; } = new();
}

public sealed class DataStore
{
    private readonly string _dir = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Pings");
    private string FilePath => Path.Combine(_dir, "store.json");
    public AppData Data { get; private set; } = new();

    public DataStore()
    {
        Directory.CreateDirectory(_dir);
        Load();
    }

    public void Load()
    {
        try
        {
            if (File.Exists(FilePath))
                Data = JsonSerializer.Deserialize<AppData>(File.ReadAllText(FilePath)) ?? new();
        }
        catch { Data = new(); }
    }

    public void Save()
    {
        Directory.CreateDirectory(_dir);
        File.WriteAllText(FilePath, JsonSerializer.Serialize(Data, new JsonSerializerOptions { WriteIndented = true }));
    }

    public void AddProfile(string name, string cidr)
    {
        var norm = NetworkUtil.Normalize(cidr);
        if (!Data.Profiles.Any(p => p.Cidr.Equals(norm, StringComparison.OrdinalIgnoreCase)))
            Data.Profiles.Add(new SubnetProfile { Name = string.IsNullOrWhiteSpace(name) ? "Subnet" : name.Trim(), Cidr = norm });
        Save();
    }

    public void AddScan(ScanRecord scan)
    {
        Data.Scans.Insert(0, scan);
        if (Data.Scans.Count > 60) Data.Scans.RemoveRange(60, Data.Scans.Count - 60);
        Save();
    }
}
