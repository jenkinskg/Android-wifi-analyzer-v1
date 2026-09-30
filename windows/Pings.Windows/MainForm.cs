using System.Diagnostics;
using System.Text;

namespace Pings.Windows;

public sealed class MainForm : Form
{
    private readonly DataStore _store = new();
    private readonly TabControl _tabs = new() { Dock = DockStyle.Fill };

    private readonly ComboBox _sweepProfile = new() { DropDownStyle = ComboBoxStyle.DropDownList };
    private readonly TextBox _sweepCidr = new();
    private readonly ComboBox _sweepFilter = new() { DropDownStyle = ComboBoxStyle.DropDownList };
    private readonly DataGridView _sweepGrid = Grid();
    private readonly Label _sweepStatus = new() { AutoSize = true, Text = "Ready" };
    private CancellationTokenSource? _scanCts;
    private List<HostResult> _lastSweep = new();

    private readonly ComboBox _monitorProfile = new() { DropDownStyle = ComboBoxStyle.DropDownList };
    private readonly ComboBox _monitorInterval = new() { DropDownStyle = ComboBoxStyle.DropDownList };
    private readonly DataGridView _monitorGrid = Grid();
    private readonly Label _monitorStatus = new() { AutoSize = true, Text = "Stopped" };
    private readonly System.Windows.Forms.Timer _monitorTimer = new();
    private bool _monitorBusy;

    private readonly ComboBox _compareA = new() { DropDownStyle = ComboBoxStyle.DropDownList };
    private readonly ComboBox _compareB = new() { DropDownStyle = ComboBoxStyle.DropDownList };
    private readonly DataGridView _compareGrid = Grid();
    private List<CompareRow> _lastCompare = new();

    private readonly TextBox _traceTarget = new() { Text = "8.8.8.8" };
    private readonly ComboBox _traceInterval = new() { DropDownStyle = ComboBoxStyle.DropDownList };
    private readonly Button _traceStart = new() { Text = "Start Trace" };
    private readonly DataGridView _traceGrid = Grid();
    private readonly LatencyGraph _traceGraph = new();
    private readonly Label _traceStatus = new() { AutoSize = true, Text = "Stopped" };
    private readonly System.Windows.Forms.Timer _traceTimer = new();
    private bool _traceBusy;
    private List<TraceHop> _lastTrace = new();

    private readonly DataGridView _subnetGrid = Grid();

    public MainForm()
    {
        Text = "Pings — Network Tools";
        Width = 1180;
        Height = 780;
        MinimumSize = new Size(900, 600);
        StartPosition = FormStartPosition.CenterScreen;
        Font = new Font("Segoe UI", 9F);
        BackColor = Color.FromArgb(24, 30, 38);
        ForeColor = Color.Gainsboro;

        Controls.Add(_tabs);
        _tabs.TabPages.Add(BuildSweepTab());
        _tabs.TabPages.Add(BuildMonitorTab());
        _tabs.TabPages.Add(BuildCompareTab());
        _tabs.TabPages.Add(BuildTraceTab());
        _tabs.TabPages.Add(BuildSubnetsTab());

        _sweepFilter.Items.AddRange(new object[] { "All", "Pingable", "No Ping" });
        _sweepFilter.SelectedIndex = 0;
        _monitorInterval.Items.AddRange(new object[] { "30 seconds", "5 minutes" });
        _monitorInterval.SelectedIndex = 0;
        _traceInterval.Items.AddRange(new object[] { "5 seconds", "30 seconds" });
        _traceInterval.SelectedIndex = 1;

        _monitorTimer.Tick += async (_, _) => await MonitorOnceAsync();
        _traceTimer.Tick += async (_, _) => await TraceOnceAsync();

        RefreshProfiles();
        RefreshHistory();
        RefreshSubnets();

        FormClosing += (_, _) =>
        {
            _scanCts?.Cancel();
            _monitorTimer.Stop();
            _traceTimer.Stop();
        };
    }

    private TabPage BuildSweepTab()
    {
        var tab = NewTab("Sweep");
        var top = Flow();
        top.Controls.Add(new Label { Text = "Saved subnet:", AutoSize = true, Padding = new Padding(0, 8, 0, 0) });
        _sweepProfile.Width = 220;
        _sweepProfile.SelectedIndexChanged += (_, _) =>
        {
            if (_sweepProfile.SelectedItem is SubnetProfile p) _sweepCidr.Text = p.Cidr;
        };
        top.Controls.Add(_sweepProfile);

        _sweepCidr.Width = 180;
        _sweepCidr.PlaceholderText = "192.168.1.0/24";
        top.Controls.Add(_sweepCidr);

        var current = Btn("Current Subnet");
        current.Click += (_, _) =>
        {
            var c = NetworkUtil.DetectCurrentSubnet();
            if (c == null) MessageBox.Show("Could not detect an active private IPv4 subnet.", "Pings");
            else _sweepCidr.Text = c;
        };
        top.Controls.Add(current);

        var scan = Btn("Scan Now");
        scan.Click += async (_, _) => await RunSweepAsync();
        top.Controls.Add(scan);

        var stop = Btn("Stop");
        stop.Click += (_, _) => _scanCts?.Cancel();
        top.Controls.Add(stop);

        top.Controls.Add(new Label { Text = "Show:", AutoSize = true, Padding = new Padding(8, 8, 0, 0) });
        _sweepFilter.Width = 130;
        _sweepFilter.SelectedIndexChanged += (_, _) => ApplySweepFilter();
        top.Controls.Add(_sweepFilter);

        var export = Btn("Export CSV");
        export.Click += (_, _) => ExportSweepCsv();
        top.Controls.Add(export);

        var email = Btn("Email Report");
        email.Click += (_, _) => EmailSweep();
        top.Controls.Add(email);

        var panel = new Panel { Dock = DockStyle.Top, Height = 76 };
        panel.Controls.Add(top);
        _sweepStatus.Location = new Point(8, 48);
        panel.Controls.Add(_sweepStatus);

        tab.Controls.Add(_sweepGrid);
        tab.Controls.Add(panel);
        return tab;
    }

    private TabPage BuildMonitorTab()
    {
        var tab = NewTab("Monitor");
        var top = Flow();

        top.Controls.Add(new Label { Text = "Subnet:", AutoSize = true, Padding = new Padding(0, 8, 0, 0) });
        _monitorProfile.Width = 260;
        top.Controls.Add(_monitorProfile);

        top.Controls.Add(new Label { Text = "Interval:", AutoSize = true, Padding = new Padding(8, 8, 0, 0) });
        _monitorInterval.Width = 130;
        top.Controls.Add(_monitorInterval);

        var start = Btn("Start Monitor");
        start.Click += async (_, _) =>
        {
            if (_monitorProfile.SelectedItem is not SubnetProfile p)
            {
                MessageBox.Show("Add or select a subnet first.", "Pings");
                return;
            }
            _monitorTimer.Interval = _monitorInterval.SelectedIndex == 0 ? 30_000 : 300_000;
            _monitorTimer.Start();
            _monitorStatus.Text = $"Monitoring {p.Cidr}";
            await MonitorOnceAsync();
        };
        top.Controls.Add(start);

        var stop = Btn("Stop");
        stop.Click += (_, _) =>
        {
            _monitorTimer.Stop();
            _monitorStatus.Text = "Stopped";
        };
        top.Controls.Add(stop);

        var export = Btn("Export History CSV");
        export.Click += (_, _) => ExportMonitorHistory();
        top.Controls.Add(export);

        var panel = new Panel { Dock = DockStyle.Top, Height = 76 };
        panel.Controls.Add(top);
        _monitorStatus.Location = new Point(8, 48);
        panel.Controls.Add(_monitorStatus);

        tab.Controls.Add(_monitorGrid);
        tab.Controls.Add(panel);
        return tab;
    }

    private TabPage BuildCompareTab()
    {
        var tab = NewTab("Compare");
        var top = Flow();
        top.Controls.Add(new Label { Text = "Before:", AutoSize = true, Padding = new Padding(0, 8, 0, 0) });
        _compareA.Width = 290; top.Controls.Add(_compareA);
        top.Controls.Add(new Label { Text = "After:", AutoSize = true, Padding = new Padding(8, 8, 0, 0) });
        _compareB.Width = 290; top.Controls.Add(_compareB);

        var compare = Btn("Compare");
        compare.Click += (_, _) => RunCompare();
        top.Controls.Add(compare);

        var export = Btn("Export CSV");
        export.Click += (_, _) => ExportCompare();
        top.Controls.Add(export);

        var email = Btn("Email Report");
        email.Click += (_, _) => EmailCompare();
        top.Controls.Add(email);

        var panel = new Panel { Dock = DockStyle.Top, Height = 52 };
        panel.Controls.Add(top);
        tab.Controls.Add(_compareGrid);
        tab.Controls.Add(panel);
        return tab;
    }

    private TabPage BuildTraceTab()
    {
        var tab = NewTab("Trace");
        var top = Flow();
        top.Controls.Add(new Label { Text = "Target:", AutoSize = true, Padding = new Padding(0, 8, 0, 0) });
        _traceTarget.Width = 230; top.Controls.Add(_traceTarget);
        top.Controls.Add(new Label { Text = "Interval:", AutoSize = true, Padding = new Padding(8, 8, 0, 0) });
        _traceInterval.Width = 120; top.Controls.Add(_traceInterval);
        _traceStart.Click += async (_, _) =>
        {
            if (_traceTimer.Enabled)
            {
                _traceTimer.Stop();
                _traceStart.Text = "Start Trace";
                _traceStatus.Text = "Stopped";
                return;
            }
            _traceGraph.ClearGraph();
            _traceTimer.Interval = _traceInterval.SelectedIndex == 0 ? 5_000 : 30_000;
            _traceTimer.Start();
            _traceStart.Text = "Stop Trace";
            await TraceOnceAsync();
        };
        top.Controls.Add(_traceStart);

        var email = Btn("Email Trace");
        email.Click += (_, _) => EmailTrace();
        top.Controls.Add(email);

        var topPanel = new Panel { Dock = DockStyle.Top, Height = 76 };
        topPanel.Controls.Add(top);
        _traceStatus.Location = new Point(8, 48);
        topPanel.Controls.Add(_traceStatus);

        var split = new SplitContainer { Dock = DockStyle.Fill, Orientation = Orientation.Horizontal, SplitterDistance = 300 };
        split.Panel1.Controls.Add(_traceGrid);
        split.Panel2.Controls.Add(_traceGraph);

        tab.Controls.Add(split);
        tab.Controls.Add(topPanel);
        return tab;
    }

    private TabPage BuildSubnetsTab()
    {
        var tab = NewTab("Subnets");
        var top = Flow();

        var add = Btn("Add Subnet");
        add.Click += (_, _) => AddSubnetDialog();
        top.Controls.Add(add);

        var addCurrent = Btn("Add Current Subnet");
        addCurrent.Click += (_, _) =>
        {
            var cidr = NetworkUtil.DetectCurrentSubnet();
            if (cidr == null)
            {
                MessageBox.Show("Could not detect an active private IPv4 subnet.", "Pings");
                return;
            }
            _store.AddProfile("Current LAN", cidr);
            RefreshProfiles(); RefreshSubnets();
        };
        top.Controls.Add(addCurrent);

        var del = Btn("Delete Selected");
        del.Click += (_, _) =>
        {
            if (_subnetGrid.CurrentRow?.DataBoundItem is SubnetProfile p)
            {
                _store.Data.Profiles.Remove(p);
                _store.Save();
                RefreshProfiles(); RefreshSubnets();
            }
        };
        top.Controls.Add(del);

        var panel = new Panel { Dock = DockStyle.Top, Height = 52 };
        panel.Controls.Add(top);

        tab.Controls.Add(_subnetGrid);
        tab.Controls.Add(panel);
        return tab;
    }

    private async Task RunSweepAsync()
    {
        var raw = _sweepCidr.Text.Trim();
        try { raw = NetworkUtil.Normalize(raw); }
        catch (Exception ex) { MessageBox.Show(ex.Message, "Pings"); return; }

        _scanCts?.Cancel();
        _scanCts = new CancellationTokenSource();
        var progress = new Progress<int>(p => _sweepStatus.Text = $"Scanning {raw} — {p}%");
        try
        {
            _lastSweep = await PingEngine.SweepAsync(raw, progress: progress, ct: _scanCts.Token);
            var scan = new ScanRecord { Timestamp = DateTime.Now, Cidr = raw, Results = _lastSweep };
            _store.AddScan(scan);
            ApplySweepFilter();
            RefreshHistory();
            _sweepStatus.Text = $"Done — {_lastSweep.Count(x => x.Reachable)} pingable / {_lastSweep.Count} addresses";
        }
        catch (OperationCanceledException) { _sweepStatus.Text = "Scan stopped"; }
        catch (Exception ex) { MessageBox.Show(ex.Message, "Pings"); _sweepStatus.Text = "Error"; }
    }

    private void ApplySweepFilter()
    {
        IEnumerable<HostResult> data = _lastSweep;
        if (_sweepFilter.SelectedIndex == 1) data = data.Where(x => x.Reachable);
        if (_sweepFilter.SelectedIndex == 2) data = data.Where(x => !x.Reachable);
        _sweepGrid.DataSource = data.Select(x => new
        {
            IP = x.Ip,
            Status = x.Status,
            Ping_ms = x.RttMs,
            Hostname = x.Hostname
        }).ToList();
    }

    private async Task MonitorOnceAsync()
    {
        if (_monitorBusy || _monitorProfile.SelectedItem is not SubnetProfile p) return;
        _monitorBusy = true;
        try
        {
            _monitorStatus.Text = $"Scanning {p.Cidr}...";
            var results = await PingEngine.SweepAsync(p.Cidr, timeoutMs: 800, concurrency: 48);
            _store.AddScan(new ScanRecord { Timestamp = DateTime.Now, Cidr = p.Cidr, Results = results });
            _monitorStatus.Text = $"{DateTime.Now:T}: {results.Count(x => x.Reachable)} pingable / {results.Count}";
            RefreshHistory();
            RefreshMonitorGrid(p.Cidr);
        }
        catch (Exception ex) { _monitorStatus.Text = "Error: " + ex.Message; }
        finally { _monitorBusy = false; }
    }

    private void RefreshMonitorGrid(string? cidr = null)
    {
        var scans = _store.Data.Scans.AsEnumerable();
        if (!string.IsNullOrWhiteSpace(cidr)) scans = scans.Where(s => s.Cidr.Equals(cidr, StringComparison.OrdinalIgnoreCase));
        _monitorGrid.DataSource = scans.Take(30).Select(s => new
        {
            Time = s.Timestamp,
            Subnet = s.Cidr,
            Pingable = s.Results.Count(x => x.Reachable),
            No_Ping = s.Results.Count(x => !x.Reachable),
            Total = s.Results.Count
        }).ToList();
    }

    private void RunCompare()
    {
        if (_compareA.SelectedItem is not ScanRecord a || _compareB.SelectedItem is not ScanRecord b)
        {
            MessageBox.Show("Choose two scans.", "Pings"); return;
        }
        var all = a.Results.Select(x => x.Ip).Union(b.Results.Select(x => x.Ip))
            .Distinct().OrderBy(x => x, StringComparer.Ordinal).ToList();
        var ad = a.Results.ToDictionary(x => x.Ip);
        var bd = b.Results.ToDictionary(x => x.Ip);

        _lastCompare = all.Select(ip =>
        {
            bool ar = ad.TryGetValue(ip, out var av) && av.Reachable;
            bool br = bd.TryGetValue(ip, out var bv) && bv.Reachable;
            string state = !ar && br ? "NEW / NOW PINGABLE"
                         : ar && !br ? "MISSING / NO PING"
                         : ar && br ? "STILL PINGABLE"
                         : "STILL NO PING";
            return new CompareRow
            {
                Ip = ip,
                State = state,
                BeforeMs = av?.RttMs,
                AfterMs = bv?.RttMs,
                Hostname = !string.IsNullOrWhiteSpace(bv?.Hostname) ? bv!.Hostname : av?.Hostname ?? ""
            };
        }).ToList();
        _compareGrid.DataSource = _lastCompare;
    }

    private async Task TraceOnceAsync()
    {
        if (_traceBusy) return;
        var target = _traceTarget.Text.Trim();
        if (target.Length == 0) return;
        _traceBusy = true;
        try
        {
            _traceStatus.Text = $"Tracing {target}...";
            _lastTrace = await TraceEngine.TraceAsync(target);
            _traceGrid.DataSource = _lastTrace.Select(h => new { h.Hop, h.Address, Latency_ms = h.RttMs, h.Status }).ToList();
            _traceGraph.AddSample(_lastTrace);
            _traceStatus.Text = $"{DateTime.Now:T}: {_lastTrace.Count} hops";
        }
        catch (Exception ex)
        {
            _traceStatus.Text = "Trace error";
            MessageBox.Show(ex.Message, "Pings");
        }
        finally { _traceBusy = false; }
    }

    private void AddSubnetDialog()
    {
        using var dialog = new Form
        {
            Text = "Add Subnet",
            Width = 470,
            Height = 245,
            StartPosition = FormStartPosition.CenterParent,
            FormBorderStyle = FormBorderStyle.FixedDialog,
            MaximizeBox = false,
            MinimizeBox = false,
            BackColor = BackColor,
            ForeColor = ForeColor
        };

        var name = new TextBox { PlaceholderText = "Name, e.g. Office LAN", Width = 400 };
        var cidr = new TextBox { PlaceholderText = "CIDR, e.g. 192.168.1.0/24", Width = 400 };
        var useCurrent = Btn("Use Current Subnet");
        useCurrent.Width = 170;
        useCurrent.Click += (_, _) =>
        {
            var current = NetworkUtil.DetectCurrentSubnet();
            if (current == null) MessageBox.Show(dialog, "Could not detect an active private IPv4 subnet.", "Pings");
            else
            {
                cidr.Text = current;
                if (string.IsNullOrWhiteSpace(name.Text)) name.Text = "Current LAN";
            }
        };
        var save = Btn("Save");
        save.Width = 100;
        save.Click += (_, _) =>
        {
            try
            {
                _store.AddProfile(name.Text, cidr.Text);
                dialog.DialogResult = DialogResult.OK;
                dialog.Close();
            }
            catch (Exception ex) { MessageBox.Show(dialog, ex.Message, "Pings"); }
        };
        var cancel = Btn("Cancel");
        cancel.Width = 100;
        cancel.Click += (_, _) => dialog.Close();

        var stack = new FlowLayoutPanel
        {
            Dock = DockStyle.Fill,
            FlowDirection = FlowDirection.TopDown,
            WrapContents = false,
            Padding = new Padding(20)
        };
        stack.Controls.Add(new Label { Text = "Enter a subnet or detect the current Windows LAN subnet.", AutoSize = true });
        stack.Controls.Add(useCurrent);
        stack.Controls.Add(name);
        stack.Controls.Add(cidr);
        var buttons = new FlowLayoutPanel { AutoSize = true };
        buttons.Controls.Add(save); buttons.Controls.Add(cancel);
        stack.Controls.Add(buttons);
        dialog.Controls.Add(stack);
        dialog.ShowDialog(this);
        RefreshProfiles();
        RefreshSubnets();
    }

    private void ExportSweepCsv()
    {
        var data = _sweepGrid.DataSource;
        if (data == null) return;
        using var sfd = new SaveFileDialog { Filter = "CSV files|*.csv", FileName = $"Pings-Sweep-{DateTime.Now:yyyyMMdd-HHmmss}.csv" };
        if (sfd.ShowDialog(this) != DialogResult.OK) return;
        var sb = new StringBuilder("IP,Status,Ping_ms,Hostname\r\n");
        IEnumerable<HostResult> rows = _lastSweep;
        if (_sweepFilter.SelectedIndex == 1) rows = rows.Where(x => x.Reachable);
        if (_sweepFilter.SelectedIndex == 2) rows = rows.Where(x => !x.Reachable);
        foreach (var r in rows) sb.AppendLine($"{Csv(r.Ip)},{Csv(r.Status)},{r.RttMs},{Csv(r.Hostname)}");
        File.WriteAllText(sfd.FileName, sb.ToString());
    }

    private void ExportMonitorHistory()
    {
        using var sfd = new SaveFileDialog { Filter = "CSV files|*.csv", FileName = $"Pings-Monitor-{DateTime.Now:yyyyMMdd-HHmmss}.csv" };
        if (sfd.ShowDialog(this) != DialogResult.OK) return;
        var sb = new StringBuilder("Time,Subnet,Pingable,No_Ping,Total\r\n");
        foreach (var s in _store.Data.Scans.Take(60))
            sb.AppendLine($"{s.Timestamp:O},{s.Cidr},{s.Results.Count(x => x.Reachable)},{s.Results.Count(x => !x.Reachable)},{s.Results.Count}");
        File.WriteAllText(sfd.FileName, sb.ToString());
    }

    private void ExportCompare()
    {
        if (_lastCompare.Count == 0) return;
        using var sfd = new SaveFileDialog { Filter = "CSV files|*.csv", FileName = $"Pings-Compare-{DateTime.Now:yyyyMMdd-HHmmss}.csv" };
        if (sfd.ShowDialog(this) != DialogResult.OK) return;
        var sb = new StringBuilder("IP,State,Before_ms,After_ms,Hostname\r\n");
        foreach (var r in _lastCompare)
            sb.AppendLine($"{Csv(r.Ip)},{Csv(r.State)},{r.BeforeMs},{r.AfterMs},{Csv(r.Hostname)}");
        File.WriteAllText(sfd.FileName, sb.ToString());
    }

    private void EmailSweep()
    {
        var lines = _lastSweep.Where(x => x.Reachable).Take(80)
            .Select(x => $"{x.Ip,-16} {x.RttMs,5} ms  {x.Hostname}");
        OpenMail("Pings sweep report", $"Subnet: {_sweepCidr.Text}\r\nPingable: {_lastSweep.Count(x => x.Reachable)} / {_lastSweep.Count}\r\n\r\n" + string.Join("\r\n", lines));
    }

    private void EmailCompare()
    {
        var lines = _lastCompare.Take(100).Select(x => $"{x.State,-22} {x.Ip} {x.Hostname}");
        OpenMail("Pings subnet comparison", string.Join("\r\n", lines));
    }

    private void EmailTrace()
    {
        var lines = _lastTrace.Select(x => $"Hop {x.Hop,2}: {x.Address,-16} {x.Status}");
        OpenMail($"Pings trace — {_traceTarget.Text}", string.Join("\r\n", lines));
    }

    private static void OpenMail(string subject, string body)
    {
        try
        {
            var url = "mailto:?subject=" + Uri.EscapeDataString(subject) + "&body=" + Uri.EscapeDataString(body);
            Process.Start(new ProcessStartInfo(url) { UseShellExecute = true });
        }
        catch (Exception ex) { MessageBox.Show(ex.Message, "Pings"); }
    }

    private void RefreshProfiles()
    {
        var selectedSweep = _sweepCidr.Text;
        _sweepProfile.DataSource = null;
        _sweepProfile.DataSource = _store.Data.Profiles.ToList();
        _monitorProfile.DataSource = null;
        _monitorProfile.DataSource = _store.Data.Profiles.ToList();
        if (_sweepProfile.Items.Count > 0 && string.IsNullOrWhiteSpace(selectedSweep)) _sweepProfile.SelectedIndex = 0;
        if (_monitorProfile.Items.Count > 0) _monitorProfile.SelectedIndex = 0;
    }

    private void RefreshHistory()
    {
        var scans = _store.Data.Scans.ToList();
        _compareA.DataSource = null; _compareA.DataSource = scans.ToList();
        _compareB.DataSource = null; _compareB.DataSource = scans.ToList();
        if (_compareA.Items.Count > 1) _compareA.SelectedIndex = 1;
        if (_compareB.Items.Count > 0) _compareB.SelectedIndex = 0;
        RefreshMonitorGrid();
    }

    private void RefreshSubnets()
    {
        _subnetGrid.DataSource = null;
        _subnetGrid.DataSource = _store.Data.Profiles.ToList();
    }

    private static string Csv(string? s)
    {
        s ??= "";
        return "\"" + s.Replace("\"", "\"\"") + "\"";
    }

    private static TabPage NewTab(string name) => new(name) { BackColor = Color.FromArgb(24, 30, 38), ForeColor = Color.Gainsboro };

    private static FlowLayoutPanel Flow() => new()
    {
        Dock = DockStyle.Top,
        Height = 42,
        Padding = new Padding(6),
        WrapContents = false,
        AutoScroll = true
    };

    private static Button Btn(string text) => new()
    {
        Text = text,
        AutoSize = true,
        Height = 30,
        FlatStyle = FlatStyle.System,
        Margin = new Padding(4, 2, 4, 2)
    };

    private static DataGridView Grid()
    {
        var g = new DataGridView
        {
            Dock = DockStyle.Fill,
            ReadOnly = true,
            AllowUserToAddRows = false,
            AllowUserToDeleteRows = false,
            AutoSizeColumnsMode = DataGridViewAutoSizeColumnsMode.Fill,
            SelectionMode = DataGridViewSelectionMode.FullRowSelect,
            MultiSelect = false,
            BackgroundColor = Color.FromArgb(18, 23, 29),
            BorderStyle = BorderStyle.None,
            RowHeadersVisible = false
        };
        return g;
    }

    private sealed class CompareRow
    {
        public string Ip { get; set; } = "";
        public string State { get; set; } = "";
        public long? BeforeMs { get; set; }
        public long? AfterMs { get; set; }
        public string Hostname { get; set; } = "";
    }
}
