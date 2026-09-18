import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import javax.imageio.ImageIO;

/**
 * Reads an RGB image and writes out TWO images: both are crops of the
 * SAME input, offset from each other by a whole-pixel (dx, dy), sized to
 * exactly their mutual overlap -- (width - |dx|) x (height - |dy|). There
 * is no padding anywhere in either output; wherever the shift would have
 * revealed a margin with no real corresponding content, that margin is
 * simply left out of both crops rather than filled in.
 *
 * This replaces an earlier version that produced one full-size shifted
 * image with the revealed margin filled by edge replication. Edge
 * replication is a lot gentler than zero-padding, but it's still not real
 * image content -- it's a flat, gradient-free strip that getGradient()
 * would still treat as legitimate signal. Since there's no padding at all
 * now, there's nothing fabricated for the estimator to pick up.
 *
 * Convention (unchanged from the padded version, and still what
 * ImageMapper.getTranslation()/getRefinedTranslation() assume): the
 * SHIFTED output's content sits dx, dy pixels further along than the
 * REFERENCE output's, in the reference's own coordinate frame. Passing
 * the reference output as source1 and the shifted output as source2 to
 * getTranslation.java should recover approximately (dx, dy).
 *
 * Concretely, both crops come straight out of the original input, at two
 * different offsets:
 *   reference[i][j] = input[max(0,dy) + i][max(0,dx) + j]
 *   shifted[i][j]   = input[max(0,-dy) + i][max(0,-dx) + j]
 * both sized (width - |dx|) x (height - |dy|). It's worth checking
 * directly that shifted[i][j] == reference[i - dy][j - dx] wherever both
 * sides are in range -- that's the same relationship the old shift-and-
 * pad version had within the overlap, just without ever materializing
 * the part outside it.
 *
 * Operates directly on packed RGB pixels (BufferedImage.getRGB/setRGB) --
 * cropping doesn't change any pixel's color, only which ones are kept, so
 * there's no need to split into channels here. That splitting only has to
 * happen on the estimation side, in getTranslation.java, where the
 * ImageMapper algorithms need a single-channel int[][].
 *
 * Usage:
 *   java getTranslatedImage <inputImagePath> <referenceOutputPath> <shiftedOutputPath> <dx> <dy>
 *
 * For now dx/dy are whole pixel values (parsed as integers), matching how
 * getTranslation.java's whole-pixel test case is meant to work.
 */
public class getTranslatedImage
{
    public static void main(String[] args)
    {
        if (args.length != 5)
        {
            System.err.println("Usage: java getTranslatedImage <inputImagePath> <referenceOutputPath> <shiftedOutputPath> <dx> <dy>");
            System.err.println("  dx, dy: whole-pixel offset between the two output crops. Both outputs are");
            System.err.println("          sized to their mutual overlap -- no padding, nothing fabricated.");
            System.exit(1);
        }

        String inputPath          = args[0];
        String referenceOutputPath = args[1];
        String shiftedOutputPath   = args[2];
        int dx, dy;
        try
        {
            dx = Integer.parseInt(args[3]);
            dy = Integer.parseInt(args[4]);
        }
        catch (NumberFormatException e)
        {
            System.err.println("dx and dy must be whole numbers (got \"" + args[3] + "\", \"" + args[4] + "\").");
            System.exit(1);
            return;
        }

        BufferedImage input;
        try
        {
            input = ImageIO.read(new File(inputPath));
        }
        catch (IOException e)
        {
            System.err.println("Could not read \"" + inputPath + "\": " + e.getMessage());
            System.exit(1);
            return;
        }
        if (input == null)
        {
            System.err.println("\"" + inputPath + "\" was not recognized as an image (unsupported format?).");
            System.exit(1);
            return;
        }

        int width  = input.getWidth();
        int height = input.getHeight();

        int overlapWidth  = width  - Math.abs(dx);
        int overlapHeight = height - Math.abs(dy);
        if (overlapWidth <= 0 || overlapHeight <= 0)
        {
            System.err.println("Shift (" + dx + "," + dy + ") leaves no overlap at all in a "
                    + width + "x" + height + " image -- nothing to write.");
            System.exit(1);
            return;
        }

        int refXoff = Math.max(0, dx);
        int refYoff = Math.max(0, dy);
        int shiftXoff = Math.max(0, -dx);
        int shiftYoff = Math.max(0, -dy);

        BufferedImage reference = crop(input, refXoff, refYoff, overlapWidth, overlapHeight);
        BufferedImage shifted   = crop(input, shiftXoff, shiftYoff, overlapWidth, overlapHeight);

        writeImage(reference, referenceOutputPath);
        writeImage(shifted, shiftedOutputPath);

        System.out.println("Wrote two " + overlapWidth + "x" + overlapHeight + " images (overlap of a "
                + width + "x" + height + " input shifted by (" + dx + "," + dy + ")):");
        System.out.println("  reference -> \"" + referenceOutputPath + "\"");
        System.out.println("  shifted   -> \"" + shiftedOutputPath + "\"");
    }

    private static BufferedImage crop(BufferedImage src, int xoff, int yoff, int w, int h)
    {
        BufferedImage dst = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int row = 0; row < h; row++)
        {
            for (int col = 0; col < w; col++)
            {
                dst.setRGB(col, row, src.getRGB(xoff + col, yoff + row));
            }
        }
        return dst;
    }

    private static void writeImage(BufferedImage img, String path)
    {
        String formatName = formatNameFromPath(path);
        try
        {
            boolean wrote = ImageIO.write(img, formatName, new File(path));
            if (!wrote)
            {
                System.err.println("No writer available for format \"" + formatName + "\" -- try a .png output path.");
                System.exit(1);
            }
        }
        catch (IOException e)
        {
            System.err.println("Could not write \"" + path + "\": " + e.getMessage());
            System.exit(1);
        }
    }

    // Picks an ImageIO format name from the output path's extension,
    // defaulting to "png" (lossless, always available) when there's no
    // extension or it isn't one ImageIO recognizes as a hint either way --
    // ImageIO.write will still fail cleanly above if the chosen name truly
    // has no writer.
    private static String formatNameFromPath(String path)
    {
        int dot = path.lastIndexOf('.');
        if (dot < 0 || dot == path.length() - 1)
        {
            return "png";
        }
        String ext = path.substring(dot + 1).toLowerCase();
        if (ext.equals("jpg") || ext.equals("jpeg")) return "jpg";
        if (ext.equals("png")) return "png";
        if (ext.equals("bmp")) return "bmp";
        if (ext.equals("gif")) return "gif";
        return "png";
    }
}
