import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import javax.imageio.ImageIO;

/**
 * Test driver: takes a reference image and a shifted version of it (e.g.
 * the reference/shifted pair written by getTranslatedImage.java), converts
 * both to a single luminance channel, and estimates the translation
 * between them two ways:
 *
 *   1) ImageMapper.getTranslation() called directly on the full-resolution
 *      luminance images. This is the "simple version" -- per its own
 *      comment and per translate()'s documented "-1 to 1" input range, it
 *      only resolves SUBPIXEL offsets. For anything at or beyond about a
 *      pixel of true shift, expect this to either report status 4 (hit
 *      the 1-pixel boundary) or return something that doesn't match the
 *      applied shift -- that's expected, not a bug, and is exactly the
 *      gap getRefinedTranslation() exists to close.
 *
 *   2) ImageMapper.getRefinedTranslation(), the coarse-to-fine wrapper
 *      that pyramids progressively-tightened overlap windows down near
 *      64x64, using getTranslation() at each level to refine a running
 *      estimate. This is the one that should recover a whole-pixel shift.
 *
 * Printing both side by side is deliberate -- it's the empirical check
 * for whether getRefinedTranslation() is actually doing the job the plain
 * getTranslation() can't.
 *
 * Usage:
 *   java getTranslation <originalImagePath> <translatedImagePath>
 *
 * Expects the two images to be the same size. getTranslatedImage.java's
 * current version produces exactly that: a reference/shifted pair, both
 * cropped down to their mutual overlap at the requested (dx, dy), with no
 * padding in either -- pass its two outputs here as originalImagePath and
 * translatedImagePath respectively. (An earlier version of
 * getTranslatedImage.java instead produced one full-size image with the
 * revealed margin edge-replicated; this program works the same way
 * against that older style of input too, since all it actually requires
 * is that the two images be the same size -- it doesn't care whether that
 * came from a same-size pad or a same-size crop.) If you asked for a
 * (dx, dy) shift, the estimate from getRefinedTranslation() below should
 * come out close to (dx, dy) -- see getTranslatedImage.java's header
 * comment for why the sign should line up directly, with no flip needed.
 */
public class getTranslation
{
    // Below this, ImageMapper.getRefinedTranslation()'s pyramid target
    // (64x64, hardcoded inside that method) leaves little or no room to
    // do more than one refinement round on the shorter axis.
    private static final int RECOMMENDED_MIN_DIMENSION = 128;

    public static void main(String[] args)
    {
        if (args.length != 2)
        {
            System.err.println("Usage: java getTranslation <originalImagePath> <translatedImagePath>");
            System.exit(1);
        }

        String originalPath   = args[0];
        String translatedPath = args[1];

        BufferedImage original   = readImage(originalPath);
        BufferedImage translated = readImage(translatedPath);

        int width  = original.getWidth();
        int height = original.getHeight();
        if (translated.getWidth() != width || translated.getHeight() != height)
        {
            System.err.println("Image size mismatch: \"" + originalPath + "\" is " + width + "x" + height
                    + ", \"" + translatedPath + "\" is " + translated.getWidth() + "x" + translated.getHeight()
                    + ". Both images must be the same size.");
            System.exit(1);
            return;
        }

        if (Math.min(width, height) < RECOMMENDED_MIN_DIMENSION)
        {
            System.out.println("Warning: shorter side is " + Math.min(width, height)
                    + "px. getRefinedTranslation() pyramids down toward 64x64, so an image this small "
                    + "may only get one refinement round (or none) -- results may be less meaningful.");
        }

        int[][] lum1 = toLuminance(original);
        int[][] lum2 = toLuminance(translated);

        System.out.println("Images: " + width + "x" + height
                + "  (\"" + originalPath + "\" vs \"" + translatedPath + "\")");
        System.out.println();

        // 1) The plain, subpixel-only estimator, run once on the full images.
        double[] simple = ImageMapper.getTranslation(lum1, lum2);
        System.out.println("ImageMapper.getTranslation() [subpixel-only, single pass]:");
        System.out.println("    status = " + (int) simple[0] + " (" + describeStatus((int) simple[0]) + ")");
        System.out.println("    x = " + simple[1] + "   y = " + simple[2]);
        System.out.println();

        // 2) The coarse-to-fine wrapper, which should handle a whole-pixel shift.
        double[] refined = ImageMapper.getRefinedTranslation(lum1, lum2);
        System.out.println("ImageMapper.getRefinedTranslation() [pyramided, iterative]:");
        System.out.println("    x = " + refined[0] + "   y = " + refined[1]);
    }

    private static String describeStatus(int status)
    {
        switch (status)
        {
            case 0: return "zero increment on the very first pass -- images already matched";
            case 1: return "converged: increment fell below 1% of the initial increment";
            case 2: return "stopped: increment reversed direction between iterations";
            case 3: return "did not converge within the internal iteration limit";
            case 4: return "stopped: translation reached translate()'s +/-1 pixel boundary";
            default: return "unrecognized status";
        }
    }

    private static BufferedImage readImage(String path)
    {
        BufferedImage img;
        try
        {
            img = ImageIO.read(new File(path));
        }
        catch (IOException e)
        {
            System.err.println("Could not read \"" + path + "\": " + e.getMessage());
            System.exit(1);
            return null;
        }
        if (img == null)
        {
            System.err.println("\"" + path + "\" was not recognized as an image (unsupported format?).");
            System.exit(1);
            return null;
        }
        return img;
    }

    // Standard ITU-R BT.601 luma weights, rounded to nearest. Every
    // ImageMapper algorithm this program calls operates on a single-
    // channel int[][], so full RGB is collapsed to one luminance value
    // per pixel here -- and only here; getTranslatedImage.java never
    // needs to do this, since shifting doesn't touch pixel color.
    private static int[][] toLuminance(BufferedImage img)
    {
        int width  = img.getWidth();
        int height = img.getHeight();
        int[][] lum = new int[height][width];
        for (int row = 0; row < height; row++)
        {
            for (int col = 0; col < width; col++)
            {
                int rgb = img.getRGB(col, row);
                int r = (rgb >> 16) & 0xFF;
                int g = (rgb >> 8) & 0xFF;
                int b = rgb & 0xFF;
                lum[row][col] = (int) Math.round(0.299 * r + 0.587 * g + 0.114 * b);
            }
        }
        return lum;
    }
}
