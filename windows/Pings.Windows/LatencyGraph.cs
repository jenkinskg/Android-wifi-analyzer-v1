namespace Pings.Windows;

public sealed class LatencyGraph : Control
{
    private readonly Dictionary<int, List<long?>> _samples = new();
    private const int MaxSamples = 60;

    public LatencyGraph()
    {
        DoubleBuffered = true;
        BackColor = Color.FromArgb(20, 26, 33);
        ForeColor = Color.Gainsboro;
        Dock = DockStyle.Fill;
        MinimumSize = new Size(300, 180);
    }

    public void AddSample(IEnumerable<TraceHop> hops)
    {
        var present = hops.ToDictionary(h => h.Hop, h => h.RttMs);
        var keys = _samples.Keys.Union(present.Keys).Distinct().ToList();

        foreach (var hop in keys)
        {
            if (!_samples.TryGetValue(hop, out var list))
            {
                list = new List<long?>();
                _samples[hop] = list;
            }
            list.Add(present.TryGetValue(hop, out var v) ? v : null);
            if (list.Count > MaxSamples) list.RemoveAt(0);
        }
        Invalidate();
    }

    public void ClearGraph()
    {
        _samples.Clear();
        Invalidate();
    }

    protected override void OnPaint(PaintEventArgs e)
    {
        base.OnPaint(e);
        var g = e.Graphics;
        g.Clear(BackColor);

        var rect = new Rectangle(48, 12, Math.Max(20, Width - 62), Math.Max(20, Height - 42));
        using var gridPen = new Pen(Color.FromArgb(55, 65, 75), 1);
        using var axisPen = new Pen(Color.FromArgb(110, 125, 140), 1);

        for (int i = 0; i <= 4; i++)
        {
            int y = rect.Top + i * rect.Height / 4;
            g.DrawLine(gridPen, rect.Left, y, rect.Right, y);
        }
        g.DrawRectangle(axisPen, rect);

        var values = _samples.Values.SelectMany(x => x).Where(x => x.HasValue).Select(x => x!.Value).ToList();
        long max = values.Count == 0 ? 100 : Math.Max(20, values.Max());
        max = ((max + 19) / 20) * 20;

        using var font = new Font("Segoe UI", 8);
        using var brush = new SolidBrush(ForeColor);
        g.DrawString($"{max} ms", font, brush, 2, rect.Top - 2);
        g.DrawString("0", font, brush, 20, rect.Bottom - 8);

        Color[] palette = { Color.LimeGreen, Color.DeepSkyBlue, Color.Gold, Color.OrangeRed, Color.MediumOrchid, Color.Cyan, Color.LightPink, Color.White };
        int legendY = rect.Top + 2;
        int seriesIndex = 0;

        foreach (var pair in _samples.OrderBy(x => x.Key))
        {
            var list = pair.Value;
            if (list.Count < 2) continue;
            var color = palette[seriesIndex++ % palette.Length];
            using var pen = new Pen(color, 1.6f);

            PointF? prev = null;
            for (int i = 0; i < list.Count; i++)
            {
                if (!list[i].HasValue) { prev = null; continue; }
                float x = rect.Left + (list.Count <= 1 ? 0 : i * rect.Width / (float)(MaxSamples - 1));
                float y = rect.Bottom - Math.Min(max, list[i]!.Value) * rect.Height / (float)max;
                var p = new PointF(x, y);
                if (prev.HasValue) g.DrawLine(pen, prev.Value, p);
                prev = p;
            }

            if (legendY < rect.Bottom - 12)
            {
                using var lb = new SolidBrush(color);
                g.FillRectangle(lb, rect.Right - 100, legendY + 3, 10, 6);
                g.DrawString($"Hop {pair.Key}", font, brush, rect.Right - 84, legendY - 1);
                legendY += 15;
            }
        }
    }
}
