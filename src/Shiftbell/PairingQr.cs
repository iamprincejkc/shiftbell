using System.IO;
using System.Windows.Media.Imaging;
using QRCoder;

namespace Shiftbell;

/// <summary>The pairing code as a QR image for the phone to scan. Medium error correction: it is read off a screen.</summary>
internal static class PairingQr
{
    public static BitmapImage Render(string code)
    {
        using var data = QRCodeGenerator.GenerateQrCode(code, QRCodeGenerator.ECCLevel.M);
        var png = new PngByteQRCode(data).GetGraphic(8);
        var image = new BitmapImage();
        using var stream = new MemoryStream(png);
        image.BeginInit();
        image.CacheOption = BitmapCacheOption.OnLoad;
        image.StreamSource = stream;
        image.EndInit();
        image.Freeze();
        return image;
    }
}
