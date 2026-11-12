using System.Globalization;
using Avalonia;
using Avalonia.Controls;
using Avalonia.Layout;
using Avalonia.Media;

namespace SdnCustom.Server.App.Controls;

public sealed class HistoryLineChart : Control
{
    private static readonly IBrush BackgroundBrush = new SolidColorBrush(Color.Parse("#FFFFFF"));
    private static readonly IBrush GridBrush = new SolidColorBrush(Color.Parse("#E7EDF5"));
    private static readonly IBrush AxisBrush = new SolidColorBrush(Color.Parse("#9AA8BA"));
    private static readonly IBrush TextBrush = new SolidColorBrush(Color.Parse("#61708A"));
    private static readonly IBrush TitleBrush = new SolidColorBrush(Color.Parse("#172033"));
    private static readonly IBrush LineBrush = new SolidColorBrush(Color.Parse("#0B5CAD"));
    private static readonly Pen GridPen = new(GridBrush, 1);
    private static readonly Pen AxisPen = new(AxisBrush, 1);
    private static readonly Pen LinePen = new(LineBrush, 2.2);

    private double[] _xs = [];
    private double[] _ys = [];
    private string _title = "历史数据";
    private string? _message = "查询后在此显示图表";

    public void SetSeries(string title, IReadOnlyList<double> xs, IReadOnlyList<double> ys)
    {
        if (xs.Count != ys.Count)
        {
            throw new ArgumentException("X/Y 数据长度必须一致。", nameof(xs));
        }

        _title = title;
        _xs = xs.ToArray();
        _ys = ys.ToArray();
        _message = null;
        InvalidateVisual();
    }

    public void ShowMessage(string title, string message)
    {
        _title = title;
        _xs = [];
        _ys = [];
        _message = message;
        InvalidateVisual();
    }

    public override void Render(DrawingContext context)
    {
        base.Render(context);
        var bounds = new Rect(Bounds.Size);
        context.FillRectangle(BackgroundBrush, bounds);
        DrawText(context, _title, new Point(16, 12), 14, TitleBrush, FontWeight.SemiBold);

        if (_message is not null)
        {
            DrawText(
                context,
                _message,
                new Point(bounds.Width / 2, bounds.Height / 2),
                13,
                TextBrush,
                FontWeight.Normal,
                centered: true);
            return;
        }

        if (_xs.Length == 0 || bounds.Width < 120 || bounds.Height < 100)
        {
            return;
        }

        var plot = new Rect(58, 48, Math.Max(1, bounds.Width - 78), Math.Max(1, bounds.Height - 88));
        var minX = _xs.Min();
        var maxX = _xs.Max();
        var minY = _ys.Min();
        var maxY = _ys.Max();
        if (Math.Abs(maxX - minX) < 1e-9)
        {
            maxX = minX + 1;
        }
        if (Math.Abs(maxY - minY) < 1e-9)
        {
            var padding = Math.Max(1, Math.Abs(minY) * 0.1);
            minY -= padding;
            maxY += padding;
        }

        for (var index = 0; index <= 4; index++)
        {
            var ratio = index / 4d;
            var y = plot.Bottom - plot.Height * ratio;
            context.DrawLine(index == 0 ? AxisPen : GridPen, new Point(plot.Left, y), new Point(plot.Right, y));
            DrawText(
                context,
                FormatValue(minY + (maxY - minY) * ratio),
                new Point(plot.Left - 8, y),
                11,
                TextBrush,
                FontWeight.Normal,
                horizontalAlignment: HorizontalAlignment.Right,
                verticalAlignment: VerticalAlignment.Center);
        }

        for (var index = 0; index <= 4; index++)
        {
            var ratio = index / 4d;
            var x = plot.Left + plot.Width * ratio;
            context.DrawLine(GridPen, new Point(x, plot.Top), new Point(x, plot.Bottom));
            DrawText(
                context,
                FormatValue(minX + (maxX - minX) * ratio),
                new Point(x, plot.Bottom + 8),
                10,
                TextBrush,
                FontWeight.Normal,
                horizontalAlignment: HorizontalAlignment.Center);
        }

        context.DrawLine(AxisPen, new Point(plot.Left, plot.Bottom), new Point(plot.Right, plot.Bottom));
        context.DrawLine(AxisPen, new Point(plot.Left, plot.Top), new Point(plot.Left, plot.Bottom));

        var geometry = new StreamGeometry();
        using (var geometryContext = geometry.Open())
        {
            for (var index = 0; index < _xs.Length; index++)
            {
                var x = plot.Left + (_xs[index] - minX) / (maxX - minX) * plot.Width;
                var y = plot.Bottom - (_ys[index] - minY) / (maxY - minY) * plot.Height;
                var point = new Point(x, y);
                if (index == 0)
                {
                    geometryContext.BeginFigure(point, false);
                }
                else
                {
                    geometryContext.LineTo(point);
                }
            }
            geometryContext.EndFigure(false);
        }
        context.DrawGeometry(null, LinePen, geometry);

        if (_xs.Length == 1)
        {
            var x = plot.Left + (_xs[0] - minX) / (maxX - minX) * plot.Width;
            var y = plot.Bottom - (_ys[0] - minY) / (maxY - minY) * plot.Height;
            context.DrawEllipse(LineBrush, null, new Point(x, y), 3.5, 3.5);
        }

        DrawText(
            context,
            "相对时间（秒）",
            new Point(plot.Left + plot.Width / 2, bounds.Height - 13),
            10,
            TextBrush,
            FontWeight.Normal,
            horizontalAlignment: HorizontalAlignment.Center,
            verticalAlignment: VerticalAlignment.Center);
    }

    private static void DrawText(
        DrawingContext context,
        string text,
        Point origin,
        double fontSize,
        IBrush brush,
        FontWeight fontWeight,
        Avalonia.Layout.HorizontalAlignment horizontalAlignment = Avalonia.Layout.HorizontalAlignment.Left,
        Avalonia.Layout.VerticalAlignment verticalAlignment = Avalonia.Layout.VerticalAlignment.Top,
        bool centered = false)
    {
        var formatted = new FormattedText(
            text,
            CultureInfo.CurrentCulture,
            FlowDirection.LeftToRight,
            new Typeface("Inter", FontStyle.Normal, fontWeight),
            fontSize,
            brush);
        var point = centered
            ? new Point(origin.X - formatted.Width / 2, origin.Y - formatted.Height / 2)
            : new Point(
                horizontalAlignment switch
                {
                    Avalonia.Layout.HorizontalAlignment.Center => origin.X - formatted.Width / 2,
                    Avalonia.Layout.HorizontalAlignment.Right => origin.X - formatted.Width,
                    _ => origin.X
                },
                verticalAlignment switch
                {
                    Avalonia.Layout.VerticalAlignment.Center => origin.Y - formatted.Height / 2,
                    Avalonia.Layout.VerticalAlignment.Bottom => origin.Y - formatted.Height,
                    _ => origin.Y
                });
        context.DrawText(formatted, point);
    }

    private static string FormatValue(double value)
    {
        var magnitude = Math.Abs(value);
        return magnitude is >= 100000 or (> 0 and < 0.001)
            ? value.ToString("0.##E+0", CultureInfo.InvariantCulture)
            : value.ToString("0.###", CultureInfo.InvariantCulture);
    }
}
