import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import javax.imageio.ImageIO;
import javax.swing.*;

/**
 * Opens an image and, for each color channel, runs it through a small
 * detail-preserving 2x2 block-average pyramid, calling ImageMapper's
 * methods by their qualified names:
 *   1. shrinks it with ImageMapper.shrinkAvg (2x2 block averaging),
 *   2. expands it back with ImageMapper.expandGradient (local-gradient-
 *      plane detail reconstruction) and ImageMapper.expandGradientSaddle
 *      (the saddle-capable variant, for comparison),
 *   3. refines it with ImageMapper.refineWithSignBits (one side-
 *      information bit per pixel).
 *
 * ImageMapper.shrinkAvg only works cleanly on even dimensions, and real
 * images are essentially never conveniently sized, so rather than cropping
 * any real image content away, the image is edge-padded up to the next
 * multiple of PAD_MULTIPLE before processing (ImageMapper.padEdgeReplicate),
 * and every result is cropped back down to the original size afterward
 * (ImageMapper.crop). Every original pixel is processed and reconstructed;
 * none are discarded.
 *
 * Prints the average signed error (reconstructed - original) after each
 * pass, and displays the final reconstructed image.
 */
public class ShrinkExpander {

    // How far to edge-pad before shrinking. 2 is enough for the single
    // shrink/expand pass used here; a pyramid chaining n levels would need
    // this to be 2^n instead, so every level still sees even dimensions.
    private static final int PAD_MULTIPLE = 2;

    public static void main(String[] args) throws IOException {
        final String filename;
        if (args.length == 1) {
            filename = args[0];
        } else {
            FileDialog fd = new FileDialog((Frame) null, "Open Image", FileDialog.LOAD);
            fd.setVisible(true);
            if (fd.getFile() == null) { System.exit(0); return; }
            filename = new File(fd.getDirectory(), fd.getFile()).getPath();
        }

        BufferedImage original = ImageIO.read(new File(filename));
        if (original == null) {
            System.out.println("Could not read image: " + filename);
            return;
        }
        int w = original.getWidth();
        int h = original.getHeight();
        System.out.println("Image size: " + w + " x " + h);

        int[][] red   = new int[h][w];
        int[][] green = new int[h][w];
        int[][] blue  = new int[h][w];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int rgb = original.getRGB(x, y);
                red[y][x]   = (rgb >> 16) & 0xFF;
                green[y][x] = (rgb >> 8)  & 0xFF;
                blue[y][x]  =  rgb        & 0xFF;
            }
        }

        int[][] redPadded   = ImageMapper.padEdgeReplicate(red,   PAD_MULTIPLE);
        int[][] greenPadded = ImageMapper.padEdgeReplicate(green, PAD_MULTIPLE);
        int[][] bluePadded  = ImageMapper.padEdgeReplicate(blue,  PAD_MULTIPLE);
        if (redPadded.length != h || redPadded[0].length != w) {
            System.out.println("Padded internally to " + redPadded[0].length + " x " + redPadded.length
                + " (edge-replicated) for processing; results are cropped back to " + w + " x " + h + ".");
        }

        int[][] rAvg = ImageMapper.shrinkAvg(redPadded);
        int[][] gAvg = ImageMapper.shrinkAvg(greenPadded);
        int[][] bAvg = ImageMapper.shrinkAvg(bluePadded);

        int[][] rOut = ImageMapper.expandGradient(rAvg);
        int[][] gOut = ImageMapper.expandGradient(gAvg);
        int[][] bOut = ImageMapper.expandGradient(bAvg);

        printErrorReport("Pass 1 (gradient expand, rounded shrinkAvg)",
            red, ImageMapper.crop(rOut, h, w), green, ImageMapper.crop(gOut, h, w), blue, ImageMapper.crop(bOut, h, w));

        // Saddle-capable variant: same predictor, plus a mixed-partial term
        // read from the diagonal neighbor averages -- no extra bits, just a
        // higher-order read of the same avg[][] data. Printed alongside the
        // plane-only pass for comparison; it's not used for the displayed
        // image below since the improvement is small and not consistently
        // positive once combined with refineWithSignBits (see discussion).
        int[][] rOutSaddle = ImageMapper.expandGradientSaddle(rAvg);
        int[][] gOutSaddle = ImageMapper.expandGradientSaddle(gAvg);
        int[][] bOutSaddle = ImageMapper.expandGradientSaddle(bAvg);

        printErrorReport("Pass 1b (saddle-fit expand)",
            red, ImageMapper.crop(rOutSaddle, h, w), green, ImageMapper.crop(gOutSaddle, h, w), blue, ImageMapper.crop(bOutSaddle, h, w));

        // One bit per pixel: was the (padded) original pixel >= its block's
        // (rounded) average, or < it. Feeds the refinement pass below.
        boolean[][] rGeq = ImageMapper.buildGeqBits(redPadded,   rAvg);
        boolean[][] gGeq = ImageMapper.buildGeqBits(greenPadded, gAvg);
        boolean[][] bGeq = ImageMapper.buildGeqBits(bluePadded,  bAvg);

        int[][] rRefined = ImageMapper.refineWithSignBits(rAvg, rOut, rGeq);
        int[][] gRefined = ImageMapper.refineWithSignBits(gAvg, gOut, gGeq);
        int[][] bRefined = ImageMapper.refineWithSignBits(bAvg, bOut, bGeq);

        int[][] rFinal = ImageMapper.crop(rRefined, h, w);
        int[][] gFinal = ImageMapper.crop(gRefined, h, w);
        int[][] bFinal = ImageMapper.crop(bRefined, h, w);

        printErrorReport("Pass 2 (after refineWithSignBits)",
            red, rFinal, green, gFinal, blue, bFinal);

        int[][] rRefinedSaddle = ImageMapper.refineWithSignBits(rAvg, rOutSaddle, rGeq);
        int[][] gRefinedSaddle = ImageMapper.refineWithSignBits(gAvg, gOutSaddle, gGeq);
        int[][] bRefinedSaddle = ImageMapper.refineWithSignBits(bAvg, bOutSaddle, bGeq);

        printErrorReport("Pass 2b (saddle-fit + refineWithSignBits)",
            red, ImageMapper.crop(rRefinedSaddle, h, w), green, ImageMapper.crop(gRefinedSaddle, h, w), blue, ImageMapper.crop(bRefinedSaddle, h, w));

        BufferedImage result = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int rgb = (rFinal[y][x] << 16) | (gFinal[y][x] << 8) | bFinal[y][x];
                result.setRGB(x, y, rgb);
            }
        }

        SwingUtilities.invokeLater(() -> {
            JFrame frame = new JFrame("Shrink + Gradient Expand  —  " + filename);
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            frame.getContentPane().add(new JScrollPane(new JLabel(new ImageIcon(result))));
            frame.pack();
            frame.setLocationRelativeTo(null);
            frame.setVisible(true);
        });
    }

    // Prints one labeled error report, pooling the three channels together
    // for the "Overall" line. Calls ImageMapper.errorStats for the actual
    // per-channel measurement.
    private static void printErrorReport(String label,
            int[][] red, int[][] rOut, int[][] green, int[][] gOut, int[][] blue, int[][] bOut) {
        double[] rStats = ImageMapper.errorStats(red, rOut);
        double[] gStats = ImageMapper.errorStats(green, gOut);
        double[] bStats = ImageMapper.errorStats(blue, bOut);
        double n = rStats[2] + gStats[2] + bStats[2];
        double avgError = (rStats[0]*rStats[2] + gStats[0]*gStats[2] + bStats[0]*bStats[2]) / n;
        double avgAbs   = (rStats[1]*rStats[2] + gStats[1]*gStats[2] + bStats[1]*bStats[2]) / n;

        System.out.println();
        System.out.println(label + " - average error (reconstructed - original):");
        System.out.printf("  Red:     %+.4f   (mean |error| %.4f)%n", rStats[0], rStats[1]);
        System.out.printf("  Green:   %+.4f   (mean |error| %.4f)%n", gStats[0], gStats[1]);
        System.out.printf("  Blue:    %+.4f   (mean |error| %.4f)%n", bStats[0], bStats[1]);
        System.out.printf("  Overall: %+.4f   (mean |error| %.4f)%n", avgError, avgAbs);
    }
}
