/**
 * All the image-processing methods for the shrink/expand pyramid demo, plus
 * their helpers: the core operators (shrinkAvg, expandGradient,
 * expandGradientSaddle, refineWithSignBits), boundary handling for
 * dimensions that aren't a clean multiple of 2 (padEdgeReplicate/crop), and
 * error measurement (errorStats). No file I/O, no GUI, no main -- this
 * class only operates on int[][]/boolean[][] pixel arrays that are already
 * in memory. ShrinkExpander.java is the driver that reads an image, calls
 * these methods by their qualified names, and displays the result.
 */
public class ImageMapper {

    // ---- pyramid operators ----

    public static int[][] shrinkAvg(int[][] src) {
        int ydim = src.length;
        int xdim = src[0].length;
        int _xdim = xdim / 2;
        int _ydim = ydim / 2;
        int[][] dst = new int[_ydim][_xdim];
        for (int i = 0; i < ydim - 1; i += 2) {
            int k = i / 2;
            for (int j = 0; j < xdim - 1; j += 2) {
                int m = j / 2;
                dst[k][m] = (src[i][j] + src[i][j + 1] + src[i + 1][j] + src[i + 1][j + 1] + 2) / 4;
            }
        }
        return dst;
    }

    public static int[][] expandGradient(int[][] avg) {
        int _ydim = avg.length;
        int _xdim = avg[0].length;
        int ydim = _ydim * 2;
        int xdim = _xdim * 2;
        int[][] dst = new int[ydim][xdim];

        for (int k = 0; k < _ydim; k++) {
            for (int m = 0; m < _xdim; m++) {
                double A  = avg[k][m];
                double gx = horizGrad(avg, k, m);
                double gy = vertGrad(avg, k, m);

                double tl = A - 0.5 * gy - 0.5 * gx;
                double tr = A - 0.5 * gy + 0.5 * gx;
                double bl = A + 0.5 * gy - 0.5 * gx;
                double br = A + 0.5 * gy + 0.5 * gx;

                int i = 2 * k, j = 2 * m;
                dst[i][j]         = clamp(Math.round(tl));
                dst[i][j + 1]     = clamp(Math.round(tr));
                dst[i + 1][j]     = clamp(Math.round(bl));
                dst[i + 1][j + 1] = clamp(Math.round(br));
            }
        }
        return dst;
    }

    private static double horizGrad(int[][] avg, int k, int m) {
        int _xdim = avg[0].length;
        int m0 = Math.max(m - 1, 0);
        int m1 = Math.min(m + 1, _xdim - 1);
        if (m0 == m1) return 0.0;
        return (avg[k][m1] - avg[k][m0]) / (2.0 * (m1 - m0));
    }

    private static double vertGrad(int[][] avg, int k, int m) {
        int _ydim = avg.length;
        int k0 = Math.max(k - 1, 0);
        int k1 = Math.min(k + 1, _ydim - 1);
        if (k0 == k1) return 0.0;
        return (avg[k1][m] - avg[k0][m]) / (2.0 * (k1 - k0));
    }

    /**
     * Saddle-capable variant of expandGradient: adds one more Taylor term,
     * the mixed partial (cross) derivative gxy, estimated from the four
     * DIAGONAL neighboring block averages. A plane (A + gy*r + gx*c) can
     * only tilt; it can't represent a block sitting at a saddle point,
     * where the surface curves opposite ways along the two diagonals (e.g.
     * neighbors arranged low/high/high/low in a checkerboard). Adding
     * gxy*r*c captures that.
     *
     * Uses no information beyond what's already in avg[][] -- no new
     * bits, no side channel, just a higher-order read of the same
     * neighbor data expandGradient already has. The four correction terms
     * (+,-,-,+) still sum to zero, so the block mean is still preserved
     * exactly, same as the plane-only version.
     */
    public static int[][] expandGradientSaddle(int[][] avg) {
        int _ydim = avg.length;
        int _xdim = avg[0].length;
        int ydim = _ydim * 2;
        int xdim = _xdim * 2;
        int[][] dst = new int[ydim][xdim];

        for (int k = 0; k < _ydim; k++) {
            for (int m = 0; m < _xdim; m++) {
                double A   = avg[k][m];
                double gx  = horizGrad(avg, k, m);
                double gy  = vertGrad(avg, k, m);
                double gxy = crossGrad(avg, k, m);

                double tl = A - 0.5 * gy - 0.5 * gx + 0.25 * gxy;
                double tr = A - 0.5 * gy + 0.5 * gx - 0.25 * gxy;
                double bl = A + 0.5 * gy - 0.5 * gx - 0.25 * gxy;
                double br = A + 0.5 * gy + 0.5 * gx + 0.25 * gxy;

                int i = 2 * k, j = 2 * m;
                dst[i][j]         = clamp(Math.round(tl));
                dst[i][j + 1]     = clamp(Math.round(tr));
                dst[i + 1][j]     = clamp(Math.round(bl));
                dst[i + 1][j + 1] = clamp(Math.round(br));
            }
        }
        return dst;
    }

    // Mixed second difference from the four diagonal block-average
    // neighbors -- same clamp-to-edge boundary handling as horizGrad/
    // vertGrad, generalized to two axes at once.
    private static double crossGrad(int[][] avg, int k, int m) {
        int _ydim = avg.length, _xdim = avg[0].length;
        int k0 = Math.max(k - 1, 0);
        int k1 = Math.min(k + 1, _ydim - 1);
        int m0 = Math.max(m - 1, 0);
        int m1 = Math.min(m + 1, _xdim - 1);
        if (k0 == k1 || m0 == m1) return 0.0;
        double num = avg[k1][m1] - avg[k1][m0] - avg[k0][m1] + avg[k0][m0];
        double denom = (2.0 * (k1 - k0)) * (2.0 * (m1 - m0));
        return num / denom;
    }

    /**
     * Optional refinement pass using one side-information bit per pixel.
     *
     * geq[i][j] == true  means the ORIGINAL pixel at (i,j) was >= the
     *                    average of the block it came from (avg[i/2][j/2]).
     * geq[i][j] == false means it was strictly less than that average.
     *
     * (Always satisfiable: since avg = round(sum/4) now rather than floor,
     * this no longer guarantees at least one pixel is >= avg the way the
     * un-rounded version did, but the four true pixel values and their
     * exact mean are still mutually consistent by construction, so the
     * constraint set below is always feasible for them.)
     *
     * Alternates two projections until the four predicted values are
     * consistent with both the bits and the exact block mean -- a small
     * instance of POCS (alternating projection onto convex sets).
     */
    public static int[][] refineWithSignBits(int[][] avg, int[][] predicted, boolean[][] geq) {
        int ydim = predicted.length;
        int xdim = predicted[0].length;
        int[][] dst = new int[ydim][xdim];

        for (int i = 0; i < ydim - 1; i += 2) {
            int k = i / 2;
            for (int j = 0; j < xdim - 1; j += 2) {
                int m = j / 2;
                double A = avg[k][m];

                double[] p = {
                    predicted[i][j],     predicted[i][j + 1],
                    predicted[i + 1][j], predicted[i + 1][j + 1]
                };
                boolean[] bit = {
                    geq[i][j],     geq[i][j + 1],
                    geq[i + 1][j], geq[i + 1][j + 1]
                };

                for (int iter = 0; iter < 10; iter++) {
                    for (int t = 0; t < 4; t++) {
                        boolean predGeq = p[t] >= A;
                        if (predGeq != bit[t]) {
                            p[t] = bit[t] ? A : A - 1;
                        }
                    }
                    double sum  = p[0] + p[1] + p[2] + p[3];
                    double corr = (sum - 4 * A) / 4.0;
                    for (int t = 0; t < 4; t++) p[t] -= corr;
                }

                dst[i][j]         = clamp(Math.round(p[0]));
                dst[i][j + 1]     = clamp(Math.round(p[1]));
                dst[i + 1][j]     = clamp(Math.round(p[2]));
                dst[i + 1][j + 1] = clamp(Math.round(p[3]));
            }
        }
        return dst;
    }

    private static int clamp(long v) {
        if (v < 0) return 0;
        if (v > 255) return 255;
        return (int) v;
    }

    // geq[i][j] == true means the original pixel at (i,j) was >= the
    // (rounded) average of the block it came from; false means it was
    // strictly less than that average. This is the one bit of side
    // information refineWithSignBits uses per pixel.
    public static boolean[][] buildGeqBits(int[][] orig, int[][] avg) {
        int h = orig.length, w = orig[0].length;
        boolean[][] geq = new boolean[h][w];
        for (int i = 0; i < h; i++) {
            int k = i / 2;
            for (int j = 0; j < w; j++) {
                int m = j / 2;
                geq[i][j] = orig[i][j] >= avg[k][m];
            }
        }
        return geq;
    }

    // ---- boundary handling for dimensions not divisible by a pyramid's PAD_MULTIPLE ----

    // Pads out to the next multiple of `multiple` by replicating the last
    // real row/column. shrinkAvg's loops start at 0 and step by 2, so a
    // leftover row/column can only ever occur at the bottom/right edge --
    // that's the only side that ever needs padding. Replication keeps the
    // local gradient at the seam at ~zero, a neutral assumption, rather
    // than the fake dark edge zero-padding would introduce right where the
    // gradient estimate can least afford it.
    public static int[][] padEdgeReplicate(int[][] src, int multiple) {
        int h = src.length, w = src[0].length;
        int newH = padTo(h, multiple);
        int newW = padTo(w, multiple);
        if (newH == h && newW == w) return src;
        int[][] dst = new int[newH][newW];
        for (int y = 0; y < newH; y++) {
            int sy = Math.min(y, h - 1);
            for (int x = 0; x < newW; x++) {
                dst[y][x] = src[sy][Math.min(x, w - 1)];
            }
        }
        return dst;
    }

    private static int padTo(int dim, int multiple) {
        int rem = dim % multiple;
        return rem == 0 ? dim : dim + (multiple - rem);
    }

    // Crops back down to the original, unpadded dimensions (top-left
    // aligned, since padding was only ever added at the bottom/right).
    public static int[][] crop(int[][] src, int h, int w) {
        int[][] dst = new int[h][w];
        for (int y = 0; y < h; y++) {
            System.arraycopy(src[y], 0, dst[y], 0, w);
        }
        return dst;
    }

    // ---- error measurement ----

    // Returns { meanSignedError, meanAbsError, pixelCount } for one channel.
    public static double[] errorStats(int[][] orig, int[][] recon) {
        int h = orig.length, w = orig[0].length;
        long n = (long) h * w;
        double sumErr = 0, sumAbs = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int e = recon[y][x] - orig[y][x];
                sumErr += e;
                sumAbs += Math.abs(e);
            }
        }
        return new double[]{ sumErr / n, sumAbs / n, n };
    }
}
