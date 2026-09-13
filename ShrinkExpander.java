import java.awt.*;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.geom.AffineTransform;
import java.awt.image.AffineTransformOp;
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

        SwingUtilities.invokeLater(() ->
            new Viewer("Shrink + Gradient Expand  —  " + filename, result).show());
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

    /**
     * Displays an image sized to fit the screen, with zoom and scrolling --
     * adapted from the equivalent view-handling code in DeltaWriter.java
     * (fit_scale/zoom_scale sizing, the ImageCanvas + JScrollPane pairing,
     * the ctrl+wheel zoom-at-cursor behavior, and the Zoom In/Out/Fit/100%
     * menu commands). DeltaWriter's HiDPI font-scale detection isn't
     * carried over here -- that's a separate Linux/X11-specific font
     * workaround unrelated to image display -- so sizing below just uses
     * the screen size directly rather than a detected hidpi_scale factor.
     */
    private static class Viewer {
        private static final double ZOOM_FACTOR = 1.25;
        private static final double ZOOM_MIN    = 0.05;
        private static final double ZOOM_MAX    = 32.0;

        private final String title;
        private final BufferedImage sourceImage;
        private BufferedImage displayImage;
        private JFrame frame;
        private ImageCanvas canvas;
        private JScrollPane scrollPane;
        private double zoomScale;

        Viewer(String title, BufferedImage sourceImage) {
            this.title = title;
            this.sourceImage = sourceImage;
        }

        void show() {
            Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
            int screenW = (int) screen.getWidth();
            int screenH = (int) screen.getHeight();

            int imgW = sourceImage.getWidth();
            int imgH = sourceImage.getHeight();

            // Fit within 70% of the screen, minus a fixed allowance for
            // window chrome (menu bar, borders, scrollbars). Never scale
            // UP past 100% for an image that's already small enough to
            // fit -- "fit to screen" should only shrink, not magnify.
            int maxW = (int) (screenW * 0.70) - 40;
            int maxH = (int) (screenH * 0.70) - 80;
            double fitScale = Math.min(1.0, Math.min((double) maxW / imgW, (double) maxH / imgH));
            zoomScale = fitScale;

            canvas = new ImageCanvas();
            scrollPane = new JScrollPane(canvas,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
            scrollPane.getVerticalScrollBar().setUnitIncrement(16);
            scrollPane.getHorizontalScrollBar().setUnitIncrement(16);
            // Ctrl+wheel zooms, centered on the cursor; a plain wheel scroll
            // is left alone -- JScrollPane's own built-in wheel listener
            // (installed by its UI) still receives and handles that case,
            // since Swing dispatches a wheel event to every listener.
            scrollPane.addMouseWheelListener(e -> {
                if (!e.isControlDown()) return;
                JViewport vp = scrollPane.getViewport();
                Point vpos = vp.getViewPosition();
                Point mpt = e.getPoint();
                int mcx = mpt.x + vpos.x, mcy = mpt.y + vpos.y;
                double old = zoomScale;
                zoomScale = (e.getWheelRotation() < 0)
                    ? Math.min(ZOOM_MAX, zoomScale * ZOOM_FACTOR)
                    : Math.max(ZOOM_MIN, zoomScale / ZOOM_FACTOR);
                if (zoomScale == old) return;
                refreshCanvas();
                double r = zoomScale / old;
                vp.setViewPosition(new Point(
                    Math.max(0, (int) (mcx * r) - mpt.x),
                    Math.max(0, (int) (mcy * r) - mpt.y)));
                updateTitle();
            });

            frame = new JFrame(title);
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            frame.getContentPane().add(scrollPane, BorderLayout.CENTER);
            frame.setJMenuBar(buildViewMenuBar());

            refreshCanvas();
            updateTitle();

            frame.setSize(
                Math.min((int) (imgW * fitScale) + 40, (int) (screenW * 0.70)),
                Math.min((int) (imgH * fitScale) + 80, (int) (screenH * 0.70)));
            frame.setLocationRelativeTo(null);
            frame.setVisible(true);
        }

        private JMenuBar buildViewMenuBar() {
            JMenuBar menuBar = new JMenuBar();
            JMenu viewMenu = new JMenu("View");

            JMenuItem zoomIn = new JMenuItem("Zoom In");
            zoomIn.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_EQUALS, InputEvent.CTRL_DOWN_MASK));
            zoomIn.addActionListener(e -> zoomBy(ZOOM_FACTOR));
            viewMenu.add(zoomIn);

            JMenuItem zoomOut = new JMenuItem("Zoom Out");
            zoomOut.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_MINUS, InputEvent.CTRL_DOWN_MASK));
            zoomOut.addActionListener(e -> zoomBy(1.0 / ZOOM_FACTOR));
            viewMenu.add(zoomOut);

            JMenuItem fit = new JMenuItem("Fit to Window");
            fit.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_0, InputEvent.CTRL_DOWN_MASK));
            fit.addActionListener(e -> {
                Dimension vs = scrollPane.getViewport().getSize();
                zoomScale = Math.min((double) vs.width / sourceImage.getWidth(),
                                      (double) vs.height / sourceImage.getHeight());
                refreshCanvas();
                updateTitle();
            });
            viewMenu.add(fit);

            JMenuItem oneToOne = new JMenuItem("100%");
            oneToOne.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_1, InputEvent.CTRL_DOWN_MASK));
            oneToOne.addActionListener(e -> {
                zoomScale = 1.0;
                refreshCanvas();
                updateTitle();
            });
            viewMenu.add(oneToOne);

            menuBar.add(viewMenu);
            return menuBar;
        }

        // Keyboard-triggered zoom (menu accelerators): keeps the current
        // viewport center fixed rather than the cursor position, since
        // there's no mouse position to anchor to.
        private void zoomBy(double factor) {
            double newScale = Math.max(ZOOM_MIN, Math.min(ZOOM_MAX, zoomScale * factor));
            if (newScale == zoomScale) return;
            JViewport vp = scrollPane.getViewport();
            Point vpos = vp.getViewPosition();
            Dimension vs = vp.getSize();
            double cx = vpos.x + vs.width / 2.0;
            double cy = vpos.y + vs.height / 2.0;
            double ratio = newScale / zoomScale;
            zoomScale = newScale;
            refreshCanvas();
            vp.setViewPosition(new Point(
                Math.max(0, (int) (cx * ratio - vs.width / 2.0)),
                Math.max(0, (int) (cy * ratio - vs.height / 2.0))));
            updateTitle();
        }

        // Rebuilds displayImage at the current zoomScale and resizes/repaints
        // the canvas to match.
        private void refreshCanvas() {
            if (zoomScale == 1.0) {
                displayImage = sourceImage;
            } else {
                int w = Math.max(1, (int) (sourceImage.getWidth() * zoomScale));
                int h = Math.max(1, (int) (sourceImage.getHeight() * zoomScale));
                AffineTransform t = new AffineTransform();
                t.scale(zoomScale, zoomScale);
                displayImage = new AffineTransformOp(t, AffineTransformOp.TYPE_BILINEAR)
                    .filter(sourceImage, new BufferedImage(w, h, sourceImage.getType()));
            }
            canvas.setPreferredSize(new Dimension(
                (int) (sourceImage.getWidth() * zoomScale), (int) (sourceImage.getHeight() * zoomScale)));
            canvas.revalidate();
            canvas.repaint();
        }

        private void updateTitle() {
            frame.setTitle(title + "  [" + Math.round(zoomScale * 100) + "%]");
        }

        private class ImageCanvas extends JPanel {
            ImageCanvas() { setOpaque(true); }

            @Override
            public Dimension getPreferredSize() {
                return displayImage != null
                    ? new Dimension(displayImage.getWidth(), displayImage.getHeight())
                    : new Dimension(1, 1);
            }

            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                if (displayImage != null) g.drawImage(displayImage, 0, 0, this);
            }
        }
    }
}
