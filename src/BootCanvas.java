import javax.microedition.midlet.*;
import javax.microedition.lcdui.*;
import javax.microedition.io.*;
import javax.microedition.rms.*;
import javax.microedition.io.file.*;
import java.io.*;
import java.util.Vector;
import java.util.Enumeration;

class BootCanvas extends Canvas {
    private Omega midlet;
    private Image bootIcon = null;

    public BootCanvas(Omega m) {
        midlet = m;
        setFullScreenMode(true);
        try {
            Image rawIcon = Image.createImage("/icon.png");
            bootIcon = scaleImage(rawIcon, 114, 128);
        } catch (Exception e) {}
    }

    private Image scaleImage(Image src, int tw, int th) {
        int sw = src.getWidth();
        int sh = src.getHeight();
        int[] srgb = new int[sw * sh];
        src.getRGB(srgb, 0, sw, 0, 0, sw, sh);

        int[] trgb = new int[tw * th];
        for (int y = 0; y < th; y++) {
            for (int x = 0; x < tw; x++) {
                int sx = (x * sw) / tw;
                int sy = (y * sh) / th;
                trgb[y * tw + x] = srgb[sy * sw + sx];
            }
        }
        return Image.createRGBImage(trgb, tw, th, true);
    }

    protected void paint(Graphics g) {
        int w = getWidth();
        int h = getHeight();
        g.setColor(0x000000);
        g.fillRect(0, 0, w, h);
        if (bootIcon != null) {
            g.drawImage(bootIcon, (w - bootIcon.getWidth()) / 2, (h - bootIcon.getHeight()) / 2, Graphics.TOP | Graphics.LEFT);
        }
    }
}
